(ns mail-relay.resend-test
  "Resend の両側。**HMAC も封も本物**で、偽物は KV・R2・fetch だけ。

  主張:
  1. 署名は受け取ったバイト列そのものに対して検証される（parse して再直列化しない）
  2. 署名が違う・古い・無いものは 401
  3. 通ったものは Email Routing と**同じ** save-raw! を通る
  4. 再送は冪等 —— R2 の条件付き put が境界"
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [clojure.string :as str]
            [envelope.seal :as eseal]
            [kotoba.signal.x25519 :as x25519]
            [mail-relay.fakes :as fakes]
            [mail-relay.resend :as resend]
            [mail-relay.worker :as worker]))

(def secret "whsec_dGVzdHNlY3JldGZvcnN2aXh2ZXJpZmljYXRpb24=")
(def svix-id "msg_1")

(defn- hmac-b64 [secret-b64 message]
  (let [raw (js/atob secret-b64)
        key-bytes (js/Uint8Array. (.-length raw))]
    (dotimes [i (.-length raw)] (aset key-bytes i (.charCodeAt raw i)))
    (-> (js/crypto.subtle.importKey "raw" key-bytes #js {:name "HMAC" :hash "SHA-256"}
                                    false #js ["sign"])
        (.then #(js/crypto.subtle.sign "HMAC" % (.encode (js/TextEncoder.) message)))
        (.then (fn [mac]
                 (js/btoa (apply str (map #(js/String.fromCharCode %)
                                          (array-seq (js/Uint8Array. mac)))))))))) 

(defn- signed-headers [body ts]
  (-> (hmac-b64 (subs secret 6) (str svix-id "." ts "." body))
      (.then (fn [sig] {"svix-id" svix-id
                        "svix-timestamp" (str ts)
                        "svix-signature" (str "v1," sig)
                        "content-type" "application/json"}))))

(deftest the-signature-covers-the-exact-bytes
  (async done
    (let [body "{\"type\":\"email.received\",\"data\":{\"email_id\":\"e1\"}}"
          now (js/Math.floor (/ (.now js/Date) 1000))]
      (-> (hmac-b64 (subs secret 6) (str svix-id "." now "." body))
          (.then (fn [sig]
                   (js/Promise.all
                    #js [(resend/verify-webhook secret svix-id now (str "v1," sig) body now)
                         ;; 1 バイトでも変われば通らない。parse して再直列化した
                         ;; ものに対して検証すると、ここが通ってしまう。
                         (resend/verify-webhook secret svix-id now (str "v1," sig)
                                                (str/replace body "e1" "e2") now)
                         ;; 窓の外
                         (resend/verify-webhook secret svix-id now (str "v1," sig) body (+ now 400))
                         ;; 秘密が違う
                         (resend/verify-webhook "whsec_AAAA" svix-id now (str "v1," sig) body now)
                         ;; whsec_ でない
                         (resend/verify-webhook "plain" svix-id now (str "v1," sig) body now)])))
          (.then (fn [[ok tampered stale wrong-secret malformed]]
                   (is (true? ok))
                   (is (false? tampered) "body が 1 バイト変わって通ってはいけない")
                   (is (false? stale))
                   (is (false? wrong-secret))
                   (is (false? malformed))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

(deftest an-unsigned-webhook-is-refused
  (async done
    (-> (resend/handle-webhook
         (fakes/request :post "/webhooks/resend" :body {:type "email.received"})
         (fakes/env :RESEND_WEBHOOK_SECRET secret)
         {:save-raw! (fn [_ _] (js/Promise.resolve {:stored "x"}))
          :read-stream worker/read-stream
          :bytes->binary-string worker/bytes->binary-string})
        (.then (fn [^js r] (is (= 401 (.-status r))) (done)))
        (.catch (fn [e] (is false (str "threw: " (.-message e))) (done))))))

(deftest the-webhook-is-not-configured-until-the-secret-is-bound
  (async done
    ;; 秘密が無いまま 200 を返すと、署名されていない POST を受理する。
    (-> (resend/handle-webhook
         (fakes/request :post "/webhooks/resend" :body {:type "email.received"})
         (fakes/env)
         {:save-raw! (fn [_ _] (js/Promise.resolve {:stored "x"}))
          :read-stream worker/read-stream
          :bytes->binary-string worker/bytes->binary-string})
        (.then (fn [^js r] (is (= 503 (.-status r))) (done))))))

(defn- open-inbox! [env]
  (let [{:keys [pub]} (x25519/generate-keypair)]
    (-> (worker/handle-fetch
         (fakes/request :post "/v1/inboxes"
                        :body {:root "did:key:z6MkOwner" :pub (eseal/b64url pub)
                               :origin "203.0.113.21"})
         env)
        (.then #(.json ^js %))
        (.then #(js->clj % :keywordize-keys true)))))

(deftest a-signed-event-goes-through-the-same-path-and-retries-are-idempotent
  (async done
    (let [now (js/Math.floor (/ (.now js/Date) 1000))
          env (fakes/env :RESEND_WEBHOOK_SECRET secret :RESEND_API_KEY "re_test")
          hooks {:save-raw! worker/save-raw!
                 :read-stream worker/read-stream
                 :bytes->binary-string worker/bytes->binary-string}]
      (-> (open-inbox! env)
          (.then
           (fn [inbox]
             (let [address (:address inbox)
                   raw (fakes/message-to address)
                   body (js/JSON.stringify
                         (clj->js {:type "email.received"
                                   :data {:email_id "e1" :to [address]}}))]
               (aset env "RESEND_FETCH"
                     (fakes/resend-receiving {:email-id "e1" :to [address]
                                              :from "offers@example.com" :raw raw}))
               (aset env "RESEND_RECEIVING_DOMAINS" "relay.itonami.cloud")
               (-> (signed-headers body now)
                   (.then
                    (fn [headers]
                      (-> (resend/handle-webhook
                           (fakes/request :post "/webhooks/resend"
                                          :headers headers :raw-body body)
                           env hooks)
                          (.then (fn [^js r]
                                   (is (= 200 (.-status r)) "署名済みの event が通らない")
                                   (.json r)))
                          (.then (fn [^js b]
                                   (is (= 1 (.-saved b)) "Email Routing と同じ経路で 1 通保管される")
                                   ;; 再送
                                   (resend/handle-webhook
                                    (fakes/request :post "/webhooks/resend"
                                                   :headers headers :raw-body body)
                                    env hooks)))
                          (.then (fn [^js r] (is (= 200 (.-status r))) (.json r)))
                          (.then (fn [^js b]
                                   (is (true? (.-duplicate b))
                                       "再送が 2 通目を作ってはいけない")
                                   ;; 実際に 1 通しか無いことを一覧で確かめる
                                   (worker/handle-fetch
                                    (fakes/request :get (str "/v1/inboxes/" (:inbox inbox) "/messages")
                                                   :headers {"authorization" (str "Bearer " (:key inbox))})
                                    env)))
                          (.then (fn [^js r] (.json r)))
                          (.then (fn [^js b]
                                   (let [m (js->clj b :keywordize-keys true)]
                                     (is (= 1 (count (:messages m)))
                                         "保管された 1 通だけがある"))
                                   (done))))))))))
          (.catch (fn [e] (is false (str "threw: " (.-message e) "\n" (.-stack e))) (done)))))))

(deftest events-for-other-domains-are-acknowledged-not-ingested
  (async done
    ;; webhook は Resend アカウント全体に来る。開いておくと、同じアカウントの
    ;; 別ドメイン宛のメールをこの箱が取り込む。
    (let [now (js/Math.floor (/ (.now js/Date) 1000))
          body (js/JSON.stringify (clj->js {:type "email.received"
                                            :data {:email_id "e9" :to ["x@somewhere.else"]}}))
          env (fakes/env :RESEND_WEBHOOK_SECRET secret :RESEND_API_KEY "re_test")
          touched (atom false)]
      (-> (signed-headers body now)
          (.then (fn [headers]
                   (resend/handle-webhook
                    (fakes/request :post "/webhooks/resend" :headers headers :raw-body body)
                    env
                    {:save-raw! (fn [_ _] (reset! touched true) (js/Promise.resolve {:stored "x"}))
                     :read-stream worker/read-stream
                     :bytes->binary-string worker/bytes->binary-string})))
          (.then (fn [^js r] (is (= 200 (.-status r))) (.json r)))
          (.then (fn [^js b]
                   (is (true? (.-ignored b)))
                   (is (false? @touched) "別ドメイン宛の本文を取りに行ってはいけない")
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

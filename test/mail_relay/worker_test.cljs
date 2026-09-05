(ns mail-relay.worker-test
  "Worker のテスト。Cloudflare の binding は偽物だが、**crypto は本物**
  —— Ed25519 の署名検証も、封も、実物が走る。

  主張は 4 つ:
  1. route 表と dispatch がずれない
  2. root は署名でしか通らない（DID を知っているだけでは通らない）
  3. 受けて封じて index が引ける
  4. 出せる。From は persona が決めたものになる"
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [clojure.string :as str]
            [envelope.seal :as eseal]
            [kotoba.signal.x25519 :as x25519]
            [mail-relay.api :as api]
            [mail-relay.authz :as authz]
            [mail-relay.fakes :as fakes]
            [mail-relay.root-auth :as root-auth]
            [mail-relay.worker :as worker]))

(def json-of #(.json ^js %))
(defn- ->clj [^js o] (js->clj o :keywordize-keys true))
(def req fakes/request)
(def fake-env fakes/env)
(def message-to fakes/message-to)

;; ------------------------------------------------------- 表と dispatch

(deftest the-route-table-and-the-dispatch-cannot-drift
  (testing "表の op と handler の鍵が一致する"
    (is (= (set (map :op api/routes)) (set (keys worker/ops)))
        (str "表にあって handler が無い: " (remove (set (keys worker/ops)) (map :op api/routes))
             " / handler があって表に無い: " (remove (set (map :op api/routes)) (keys worker/ops)))))
  (testing "表に無い path は 404 で、表が唯一の入口だと言う"
    (async done
      (-> (worker/handle-fetch (req :get "/v1/nope") (fake-env))
          (.then (fn [^js r] (is (= 404 (.-status r))) (json-of r)))
          (.then (fn [b] (is (= "no-such-route" (.-error b))) (done)))))))

;; --------------------------------------------------------------- did:key

(deftest did-key-decoding-agrees-with-an-outside-implementation
  ;; base58btc は自前実装なので、外の実装が同意する 1 点で固定する。
  ;; 下の DID は python の base58 で作った [0xed 0x01] + 0..31。
  (testing "既知の did:key から公開鍵のバイト列がそのまま戻る"
    (is (= (vec (range 32))
           (vec (array-seq
                 (root-auth/parse-did-key
                  "did:key:z6MkeTGwHmLmuCmgg4ABYhzWVh6ZX7hTwWt8gguAretUfc9c"))))))

  (testing "署名鍵でないもの・壊れたものは nil。読めるものを全部通さない"
    (is (nil? (root-auth/parse-did-key "did:key:z6LSnotAnEd25519Key")))
    (is (nil? (root-auth/parse-did-key "did:key:zIl0O")))
    (is (nil? (root-auth/parse-did-key "did:web:example.com")))
    (is (nil? (root-auth/parse-did-key nil)))))

;; ----------------------------------------------------------------- root

(defn- b58-encode
  "テスト側の独立実装。`root-auth/b58-decode` と同じ表を、逆向きに。"
  [bytes]
  (let [alpha "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        digits (array 0)]
    (doseq [b bytes]
      (let [carry (atom b)]
        (dotimes [i (alength digits)]
          (let [x (+ (bit-shift-left (aget digits i) 8) @carry)]
            (aset digits i (mod x 58))
            (reset! carry (js/Math.floor (/ x 58)))))
        (while (pos? @carry)
          (.push digits (mod @carry 58))
          (swap! carry #(js/Math.floor (/ % 58))))))
    (str (apply str (repeat (count (take-while zero? bytes)) "1"))
         (apply str (map #(nth alpha %) (reverse (array-seq digits)))))))

(defn- ed25519-did []
  (-> (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
      (.then (fn [^js kp]
               (-> (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
                   (.then (fn [ab]
                            {:kp kp
                             :did (str "did:key:z"
                                       (b58-encode (concat [0xed 0x01]
                                                           (array-seq (js/Uint8Array. ab)))))})))))))

(defn- sign-b64 [^js kp message]
  (-> (js/crypto.subtle.sign #js {:name "Ed25519"} (.-privateKey kp)
                             (.encode (js/TextEncoder.) message))
      (.then (fn [ab] (eseal/b64url (js/Uint8Array. ab))))))

(deftest a-real-signature-verifies-and-a-transplanted-one-does-not
  (async done
    (-> (ed25519-did)
        (.then (fn [{:keys [kp did]}]
                 (let [msg (authz/challenge-string {:method :delete :path "/v1/inboxes/inbox_a"
                                                    :timestamp 1787000000 :nonce "n-1"})
                       other (authz/challenge-string {:method :delete :path "/v1/inboxes/inbox_b"
                                                      :timestamp 1787000000 :nonce "n-1"})]
                   (-> (sign-b64 kp msg)
                       (.then (fn [sig]
                                (-> (js/Promise.all
                                     #js [(root-auth/verify did sig msg)
                                          (root-auth/verify did sig other)
                                          (root-auth/verify did "bm90" msg)])
                                    (.then (fn [[ok transplanted garbage]]
                                             (is (true? ok))
                                             (is (false? transplanted)
                                                 "別の箱に対する署名が通ってはいけない")
                                             (is (false? garbage))
                                             (done)))))))))))))

;; ------------------------------------------------------ 端から端まで

(deftest open-issue-receive-read-and-send
  (async done
    (let [{:keys [pub]} (x25519/generate-keypair)
          env (fake-env :RESEND_API_KEY "re_test" :RESEND_FETCH (fakes/resend-send-ok))
          state (atom {})]
      (-> (worker/handle-fetch
           (req :post "/v1/inboxes" :body {:root "did:key:z6MkOwner"
                                           :pub (eseal/b64url pub)
                                           :origin "203.0.113.7"})
           env)
          (.then (fn [^js r] (is (= 201 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (let [m (->clj b)]
                     (swap! state merge {:inbox (:inbox m) :key (:key m) :base (:address m)})
                     (testing "鍵はここでしか返らないと書いてある"
                       (is (str/starts-with? (str (:key m)) "rk_"))
                       (is (str/includes? (str (:note m)) "root"))))
                   (worker/handle-fetch
                    (req :post (str "/v1/inboxes/" (:inbox @state) "/personas")
                         :headers {"authorization" (str "Bearer " (:key @state))}
                         :body {:party "example.com"})
                    env)))
          (.then (fn [^js r] (is (= 201 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (let [m (->clj b)]
                     (swap! state assoc :persona (:address m))
                     (is (str/ends-with? (str (:address m)) "@mail.itonami.cloud"))
                     (is (= 4 (count (str/split (first (str/split (str (:address m)) #"@")) #"-")))
                         "読み上げられる 4 語"))
                   (worker/save-raw! env {:to (:persona @state)
                                          :from "offers@example.com"
                                          :raw (message-to (:persona @state))
                                          :received-at "2026-08-28T00:00:00Z"})))
          (.then (fn [result]
                   (is (:stored result) (str "受信が落ちた: " (pr-str result)))
                   (worker/handle-fetch
                    (req :get (str "/v1/inboxes/" (:inbox @state) "/messages")
                         :headers {"authorization" (str "Bearer " (:key @state))})
                    env)))
          (.then (fn [^js r] (is (= 200 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (let [messages (:messages (->clj b))
                         index (first messages)]
                     (is (= 1 (count messages)))
                     (testing "index に CID が付き、本文は入っていない"
                       (is (str/starts-with? (str (:cid index)) "bafkrei"))
                       (is (true? (:sealed index)))
                       (is (= "pass" (:spf index)))
                       (is (not (str/includes? (pr-str index) "本文")))
                       (is (not (str/includes? (pr-str index) "hello")))))
                   (worker/handle-fetch
                    (req :get (str "/v1/personas/" (:persona @state) "/senders")
                         :headers {"authorization" (str "Bearer " (:key @state))})
                    env)))
          (.then (fn [^js r] (is (= 200 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (is (= ["example.com"] (:observed_senders (->clj b))))
                   (worker/handle-fetch
                    (req :post (str "/v1/inboxes/" (:inbox @state) "/messages")
                         :headers {"authorization" (str "Bearer " (:key @state))}
                         :body {:address (:persona @state)
                                :to ["alice@example.com"]
                                :subject "Re: hello"
                                :text "こちらこそ"})
                    env)))
          (.then (fn [^js r] (is (= 202 (.-status r)) "送信が 202 で返らない") (json-of r)))
          (.then (fn [^js b]
                   (let [m (->clj b)]
                     (is (= (:persona @state) (:from m))
                         "From は persona が決めたもの。本体でも箱の base でもない")
                     (is (= "resend" (:provider m)))
                     (is (= "re_msg_1" (:message_id m))))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e) "\n" (.-stack e))) (done)))))))

(deftest a-caller-supplied-from-is-refused-over-http
  (async done
    (let [{:keys [pub]} (x25519/generate-keypair)
          env (fake-env :RESEND_API_KEY "re_test" :RESEND_FETCH (fakes/resend-send-ok))]
      (-> (worker/handle-fetch
           (req :post "/v1/inboxes" :body {:root "did:key:z6MkOwner" :pub (eseal/b64url pub)
                                           :origin "203.0.113.9"})
           env)
          (.then json-of)
          (.then (fn [^js b]
                   (let [m (->clj b)]
                     (worker/handle-fetch
                      (req :post (str "/v1/inboxes/" (:inbox m) "/messages")
                           :headers {"authorization" (str "Bearer " (:key m))}
                           :body {:address (:address m) :from "jun@example.invalid"
                                  :to ["a@example.com"] :subject "x" :text "y"})
                      env))))
          (.then (fn [^js r] (is (= 400 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (is (= "mail-relay.send/from-is-not-yours" (.-error b)))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

(deftest root-routes-refuse-a-bare-did
  (async done
    (-> (worker/handle-fetch
         (req :delete "/v1/inboxes/inbox_a"
              :headers {"x-relay-root" "did:key:z6MkOwner"})
         (fake-env))
        (.then (fn [^js r]
                 (is (= 401 (.-status r))
                     "DID は公開の識別子。それだけで通ると誰でも他人の箱を閉じられる")
                 (json-of r)))
        (.then (fn [^js b]
                 (is (= "mail-relay.authz/missing-signature" (.-error b)))
                 (done))))))

(deftest threads-say-why-they-are-not-server-side
  (async done
    (let [{:keys [pub]} (x25519/generate-keypair)
          env (fake-env)]
      (-> (worker/handle-fetch
           (req :post "/v1/inboxes" :body {:root "did:key:z6MkOwner" :pub (eseal/b64url pub)
                                           :origin "203.0.113.11"})
           env)
          (.then json-of)
          (.then (fn [^js b]
                   (let [m (->clj b)]
                     (worker/handle-fetch
                      (req :get (str "/v1/inboxes/" (:inbox m) "/threads")
                           :headers {"authorization" (str "Bearer " (:key m))})
                      env))))
          (.then (fn [^js r] (is (= 501 (.-status r))) (json-of r)))
          (.then (fn [^js b]
                   (is (= "threads-are-client-side" (.-error b)))
                   (is (str/includes? (str (.-basis b)) "References")
                       "なぜサーバでは組めないかを言わずに 501 を返さない")
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

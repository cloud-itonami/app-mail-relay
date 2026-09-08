(ns mail-relay.send-test
  "送信のテスト。中心は 1 つ —— **From を呼び出し側に決めさせない**。
  ここが緩むと、1 通を本体から返した瞬間に全部の顔が結びつく。"
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [mail-relay.account :as account]
            [mail-relay.send :as send]
            [persona.core :as persona]
            [word-id.english :as english]))

(def root "did:key:z6MkExampleOwner")
(def other-root "did:key:z6MkSomeoneElse")
(def domain "relay.itonami.cloud")
(def now "2026-08-28T00:00:00Z")

(defn- entropy [n] (mapv #(mod (* (inc n) (inc %) 97) 256) (range 24)))

(defn- fixture []
  (let [r (persona/issue (persona/directory)
                         {:root root :party "example.com" :domain domain
                          :entropy (entropy 3) :vocabulary english/vocabulary
                          :now now})]
    {:registry (:mail-relay/registry
                (account/open {} {:id "inbox_a" :root root :key-digest "sha256:aaa"
                                  :origin "203.0.113.7" :now 1787000000000}))
     :directory (:persona/directory r)
     :address (:persona/address (:persona/persona r))}))

(defn- op [f & {:as over}]
  (merge {:registry (:registry f) :directory (:directory f)
          :inbox "inbox_a" :key-digest "sha256:aaa" :root root
          :address (:address f)
          :to ["alice@example.com"] :subject "Hello" :text "Hello Alice"}
         over))

;; ---------------------------------------------------------------------------
;; From
;; ---------------------------------------------------------------------------

(deftest from-comes-from-the-persona-never-the-caller
  (let [f (fixture)
        d (send/plan (op f))]
    (is (= :send (:mail-relay/decision d)))
    (is (= (:address f) (:mail-relay/from d))
        "From は persona.relay/outbound が返した値でなければならない")
    (is (= (:address f) (get-in d [:mail-relay/request :http/json :from]))
        "組み立てたリクエストの From も同じもの")))

(deftest a-supplied-from-is-refused-not-ignored
  ;; 黙って無視すると、From を指定したつもりの client が指定できていないことを
  ;; 一生知らない。
  (let [f (fixture)
        d (send/plan (op f :from "jun@example.invalid"))]
    (is (= :refuse (:mail-relay/decision d)))
    (is (= :mail-relay.send/from-is-not-yours (:mail-relay/reason d)))
    (is (not (str/blank? (:mail-relay/basis d))))))

(deftest cannot-send-from-someone-elses-face
  (let [f (fixture)
        d (send/plan (op f :root other-root))]
    (is (= :mail-relay.send/not-yours (:mail-relay/reason d))
        "ここを通すと、なりすましが alias 経由で成立する")))

(deftest burned-face-cannot-send
  (let [f (fixture)
        burned (:persona/directory (persona/burn (:directory f) (:address f) now))
        d (send/plan (op f :directory burned))]
    (is (= :mail-relay.send/address-burned (:mail-relay/reason d)))))

;; ---------------------------------------------------------------------------
;; 認可
;; ---------------------------------------------------------------------------

(deftest the-key-authorises-sending
  (let [f (fixture)]
    (testing "鍵が違えば出せない"
      (is (= :mail-relay/key-mismatch
             (:mail-relay/reason (send/plan (op f :key-digest "sha256:zzz"))))))
    (testing "閉じた箱からは出せない"
      (let [closed (:mail-relay/registry (account/close (:registry f) {:id "inbox_a" :root root}))]
        (is (= :mail-relay/account-closed
               (:mail-relay/reason (send/plan (op f :registry closed)))))))))

;; ---------------------------------------------------------------------------
;; provider
;; ---------------------------------------------------------------------------

(deftest resend-is-the-default-and-the-request-is-executable
  (let [f (fixture)
        {:keys [:mail-relay/request :mail-relay/provider]} (send/plan (op f))]
    (is (= :resend provider))
    (is (= :post (:http/method request)))
    (is (= "https://api.resend.com/emails" (:http/url request)))
    (is (= ["alice@example.com"] (get-in request [:http/json :to])))
    (is (= "Hello" (get-in request [:http/json :subject])))
    (is (= "Hello Alice" (get-in request [:http/json :text])))))

(deftest other-providers-are-reachable-without-changing-this-layer
  (let [f (fixture)]
    (doseq [p [:ses :smtp]]
      (is (= :send (:mail-relay/decision (send/plan (op f :provider p)))) (str p)))
    (is (= :mail-relay.send/unsupported-provider
           (:mail-relay/reason (send/plan (op f :provider :carrier-pigeon)))))))

(deftest the-request-carries-a-secret-name-not-a-secret
  (let [f (fixture)
        req (:mail-relay/request (send/plan (op f)))]
    (is (= :resend-api-key (:http/auth-secret req))
        "鍵そのものではなく名前。差すのは host")
    (is (send/secret-free? req))
    (testing "検出器が働いていることの確認"
      (is (not (send/secret-free? (assoc req :http/authorization "Bearer re_live_abc")))))))

;; ---------------------------------------------------------------------------
;; 相手違い
;; ---------------------------------------------------------------------------

(deftest cross-party-send-is-allowed-but-never-silent
  (let [f (fixture)
        d (send/plan (op f :to ["support@othercorp.example"]))]
    (is (= :send (:mail-relay/decision d)) "正当な場合があるので止めない")
    (is (= [:persona.relay/cross-party]
           (mapv :persona.relay/signal (:mail-relay/signals d)))
        "止めないが黙らない。2 社が同じアドレスを見ると結びつく")))

;; ---------------------------------------------------------------------------
;; 組み立ての妥当性
;; ---------------------------------------------------------------------------

(deftest an-unsendable-message-is-refused-with-what-is-wrong
  (let [f (fixture)]
    (is (= :mail-relay.send/missing-recipient (:mail-relay/reason (send/plan (op f :to [])))))
    (let [d (send/plan (op f :subject "" :text nil))]
      (is (= :mail-relay.send/invalid-message (:mail-relay/reason d)))
      (is (= #{:missing-subject :missing-body}
             (set (map :mail.error/code (:mail-relay/errors d))))))))

(deftest the-effect-records-who-approved-and-with-which-face
  (let [f (fixture)
        effect (:mail-relay/effect (send/plan (op f)))]
    (is (= :mail/send (:mail.effect/type effect)))
    (is (= :approved (:mail.effect/status effect)))))

(deftest a-receipt-comes-back-provider-independent
  (let [f (fixture)
        effect (:mail-relay/effect (send/plan (op f)))
        r (send/receipt effect {:provider :resend :message-id "re_msg_1" :status :sent})]
    (is (some? r))
    (is (str/includes? (pr-str r) "re_msg_1"))))

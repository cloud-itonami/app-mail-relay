(ns mail-relay.inbound-test
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is testing]]
            [mail-relay.account :as account]
            [mail-relay.inbound :as inbound]
            [mail-relay.store :as store]
            [persona.core :as persona]
            [word-id.english :as english]))

(def root "did:key:z6MkExampleOwner")
(def domain "relay.itonami.cloud")
(def now "2026-08-28T00:00:00Z")

(defn- entropy [n] (mapv #(mod (* (inc n) (inc %) 97) 256) (range 24)))

(defn- registry []
  (:mail-relay/registry
   (account/open {} {:id "inbox_a" :root root :key-digest "sha256:aaa"
                     :origin "203.0.113.7" :now 1787000000000})))

(defn- fixture
  "example.com のための顔を 1 本持つ inbox。"
  []
  (let [r (persona/issue (persona/directory)
                         {:root root :party "example.com" :domain domain
                          :entropy (entropy 3) :vocabulary english/vocabulary
                          :now now})
        address (:persona/address (:persona/persona r))]
    {:registry (registry)
     :directory (:persona/directory r)
     :address address
     :base (str "inbox-a@" domain)
     :resolve (fn [a] (cond (= a address) {:inbox "inbox_a" :kind :persona}
                            (= a (str "inbox-a@" domain)) {:inbox "inbox_a" :kind :base}
                            :else nil))}))

;; ---------------------------------------------------------------------------
;; admission
;; ---------------------------------------------------------------------------

(deftest admission
  (let [{:keys [address resolve base]} (fixture)]
    (testing "発行済みの顔は通る"
      (is (= {:inbox "inbox_a" :kind :persona :address address}
             (:mail-relay/accept (inbound/admit {:to address :size 100 :resolve resolve})))))

    (testing "箱そのもののアドレスも通る"
      (is (= :base (:kind (:mail-relay/accept
                           (inbound/admit {:to base :size 100 :resolve resolve}))))))

    (testing "発行していないアドレスは受けない —— 封じる鍵が無いので平文で置く以外に道が無い"
      (is (= :mail-relay.inbound/unknown-address
             (:mail-relay/reject (inbound/admit {:to "nobody@example.com" :size 1 :resolve resolve})))))

    (testing "宛先が読めなければ落とす"
      (is (= :mail-relay.inbound/unparseable-recipient
             (:mail-relay/reject (inbound/admit {:to "  " :size 1 :resolve resolve})))))

    (testing "上限を超える 1 通は受けない"
      (is (= :mail-relay.inbound/too-large
             (:mail-relay/reject
              (inbound/admit {:to address :size (inc store/max-message-bytes) :resolve resolve})))))))

(deftest address-case-and-tags
  (let [{:keys [address resolve]} (fixture)]
    (testing "大文字small違いは同じアドレス"
      (is (some? (:mail-relay/accept
                  (inbound/admit {:to (kotoba.lang.text/upper address)
                                  :size 1 :resolve resolve})))))
    (testing "+tag は落とさない —— 落とすと別々に発行した 2 本が黙って融ける"
      (is (nil? (:mail-relay/accept
                 (inbound/admit {:to (kotoba.lang.text/replace address "@" "+x@")
                                 :size 1 :resolve resolve})))))))

;; ---------------------------------------------------------------------------
;; 振り分け
;; ---------------------------------------------------------------------------

(deftest forward-becomes-store
  ;; persona.relay は転送の判断として書かれている。ここは保管なので写す。
  (let [{:keys [registry directory address]} (fixture)
        d (inbound/route {:registry registry :directory directory
                          :inbox "inbox_a" :kind :persona
                          :address address :sender "noreply@example.com"})]
    (is (= :store (:mail-relay/decision d)))
    (is (= "inbox_a" (:mail-relay/inbox d)))
    (is (= [] (:mail-relay/signals d)))
    (testing "保管の決定に本体の識別子は出てこない"
      (is (= {:persona/address address} (:mail-relay/persona d))))))

(deftest destination-is-always-supplied
  ;; destination を渡し忘れると persona.relay は :no-destination で reject する。
  ;; その 1 通は黙って消えるのではなくバウンスするが、こちらの落ち度で消える。
  (let [{:keys [registry directory address]} (fixture)]
    (is (= :store (:mail-relay/decision
                   (inbound/route {:registry registry :directory directory
                                   :inbox "inbox_a" :kind :persona
                                   :address address :sender "x@example.com"})))
        "inbox id を destination として渡していないと、ここが reject に落ちる")))

(deftest unexpected-sender-is-a-signal-not-a-block
  (let [{:keys [registry directory address]} (fixture)
        d (inbound/route {:registry registry :directory directory
                          :inbox "inbox_a" :kind :persona
                          :address address :sender "offers@datamarket.example"})]
    (testing "想定外の差出人でも保管する —— 遮断ではなく信号"
      (is (= :store (:mail-relay/decision d))))
    (testing "信号は残る。これが『漏れたか売られた』の帰責になる"
      (is (= [:persona.relay/unexpected-sender]
             (mapv :persona.relay/signal (:mail-relay/signals d)))))))

(deftest closed-inbox-stops-receiving
  (let [{:keys [registry directory address]} (fixture)
        closed (:mail-relay/registry (account/close registry {:id "inbox_a" :root root}))]
    (testing "閉じた箱に溜め続けると close が解放にならない"
      (is (= :mail-relay.inbound/account-closed
             (:mail-relay/reason
              (inbound/route {:registry closed :directory directory
                              :inbox "inbox_a" :kind :persona
                              :address address :sender "x@example.com"})))))))

(deftest burned-face-stops-alone
  (let [{:keys [registry directory address]} (fixture)
        burned (:persona/directory (persona/burn directory address now))]
    (testing "止めた顔は受けない"
      (is (= :mail-relay.inbound/address-burned
             (:mail-relay/reason
              (inbound/route {:registry registry :directory burned
                              :inbox "inbox_a" :kind :persona
                              :address address :sender "x@example.com"})))))
    (testing "箱そのものは生きている"
      (is (= :store (:mail-relay/decision
                     (inbound/route {:registry registry :directory burned
                                     :inbox "inbox_a" :kind :base
                                     :address "inbox-a@relay.itonami.cloud"
                                     :sender "x@example.com"})))))))

;; ---------------------------------------------------------------------------
;; 記録
;; ---------------------------------------------------------------------------

(deftest senders-are-remembered-on-the-face-they-hit
  (let [{:keys [directory address]} (fixture)
        d (inbound/note directory {:address address :sender "offers@datamarket.example"
                                   :kind :persona})]
    (is (= #{"datamarket.example"}
           (:persona/observed-senders (persona/persona-at d address))))
    (testing "箱そのもの宛は、記録する相手が無い"
      (is (= directory (inbound/note directory {:address "inbox-a@relay.itonami.cloud"
                                                :sender "x@y.example" :kind :base}))))))

(deftest the-log-line-says-nothing-about-the-body
  (let [line (inbound/summary-line
              {:index (store/index-record {:inbox "inbox_a" :id "d" :size 42
                                           :spf :pass :dkim :pass :custody :self})})]
    (is (kotoba.lang.text/includes? line "inbox_a"))
    (is (kotoba.lang.text/includes? line "custody=self"))
    (doseq [leak ["subject" "件名" "body"]]
      (is (not (kotoba.lang.text/includes? line leak))))))

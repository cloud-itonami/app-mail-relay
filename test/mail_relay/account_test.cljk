(ns mail-relay.account-test
  "このテストの半分は『2026-08-21 に踏んだ失敗が再現しないこと』を主張する。
  仕様の説明ではなく、事故の再演として読めるようにしてある。"
  (:require [clojure.test :refer [deftest is testing]]
            [mail-relay.account :as account]))

(def root "did:key:z6MkExampleOwner")
(def other-root "did:key:z6MkSomeoneElse")
(def origin "203.0.113.7")

(defn- opened
  ([] (opened "inbox_a" "sha256:aaa"))
  ([id digest]
   (:mail-relay/registry
    (account/open {} {:id id :root root :key-digest digest
                      :origin origin :now 1787000000000}))))

(deftest open-basics
  (testing "開ける"
    (let [d (account/open {} {:id "inbox_a" :root root :key-digest "sha256:aaa"
                              :origin origin :now 1})]
      (is (= :open (:mail-relay/decision d)))
      (is (= :active (:mail-relay.account/state
                      (account/account-at (:mail-relay/registry d) "inbox_a"))))))

  (testing "root の無い箱は開かない —— 鍵を失ったとき回復不能になるため"
    (is (= :mail-relay/missing-root
           (:mail-relay/reason
            (account/open {} {:id "inbox_a" :key-digest "sha256:aaa"
                              :origin origin :now 1})))))

  (testing "同じ id は二度開かない"
    (is (= :mail-relay/duplicate-id
           (:mail-relay/reason
            (account/open (opened) {:id "inbox_a" :root root :key-digest "sha256:bbb"
                                    :origin origin :now 2}))))))

(deftest origin-slot-is-held-then-released
  (testing "1 origin 1 箱。2 つ目は拒否され、占有している id を教える"
    (let [d (account/open (opened) {:id "inbox_b" :root root :key-digest "sha256:bbb"
                                    :origin origin :now 2})]
      (is (= :mail-relay/origin-slot-taken (:mail-relay/reason d)))
      (is (= ["inbox_a"] (:mail-relay/held d)))))

  (testing "閉じれば枠は戻る"
    (let [r1 (:mail-relay/registry (account/close (opened) {:id "inbox_a" :root root}))
          d  (account/open r1 {:id "inbox_b" :root root :key-digest "sha256:bbb"
                               :origin origin :now 3})]
      (is (= :open (:mail-relay/decision d))))))

(deftest losing-the-key-does-not-strand-the-inbox
  ;; 2026-08-21 の事故そのもの。account_key を保管し損ね、DELETE は 404、
  ;; 認証もできず、死んだ箱が origin の枠を占有したまま動かせなくなった。
  (let [r (opened)]
    (testing "鍵を失った = 提示できる鍵が無い。読めないのは正しい"
      (is (= :deny (:mail-relay/decision
                    (account/authorize r {:id "inbox_a" :key-digest "sha256:wrong"})))))

    (testing "それでも root で閉じられる —— ここが AgentMail に無かった"
      (is (= :close (:mail-relay/decision (account/close r {:id "inbox_a" :root root})))))

    (testing "それでも root で鍵を回せる —— 箱を捨てずに回復できる"
      (let [d (account/rotate r {:id "inbox_a" :root root :new-key-digest "sha256:new"})]
        (is (= :rotate (:mail-relay/decision d)))
        (is (= "sha256:aaa" (:mail-relay/superseded d)))
        (is (= :allow (:mail-relay/decision
                       (account/authorize (:mail-relay/registry d)
                                          {:id "inbox_a" :key-digest "sha256:new"}))))
        (is (= :deny (:mail-relay/decision
                      (account/authorize (:mail-relay/registry d)
                                         {:id "inbox_a" :key-digest "sha256:aaa"})))
            "回した後、古い鍵は通らない")))))

(deftest root-authorisation-is-not-a-formality
  (let [r (opened)]
    (testing "他人の root では閉じられない"
      (is (= :mail-relay/not-yours
             (:mail-relay/reason (account/close r {:id "inbox_a" :root other-root})))))
    (testing "他人の root では回せない"
      (is (= :mail-relay/not-yours
             (:mail-relay/reason
              (account/rotate r {:id "inbox_a" :root other-root
                                 :new-key-digest "sha256:new"})))))))

(deftest closed-accounts-are-inert
  (let [r (:mail-relay/registry (account/close (opened) {:id "inbox_a" :root root}))]
    (is (= :mail-relay/account-closed
           (:mail-relay/reason (account/authorize r {:id "inbox_a" :key-digest "sha256:aaa"}))))
    (is (= :mail-relay/account-closed
           (:mail-relay/reason (account/rotate r {:id "inbox_a" :root root
                                                  :new-key-digest "sha256:new"}))))
    (is (= :mail-relay/already-closed
           (:mail-relay/reason (account/close r {:id "inbox_a" :root root}))))))

(deftest rotating-to-the-same-key-is-refused
  (is (= :mail-relay/same-key
         (:mail-relay/reason
          (account/rotate (opened) {:id "inbox_a" :root root
                                    :new-key-digest "sha256:aaa"})))))

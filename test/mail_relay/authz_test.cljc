(ns mail-relay.authz-test
  "root を『DID をヘッダに書けば通る』にしないこと。DID は公開の識別子
  なので、それで認可すると、鍵を失った人を救う経路がそのまま誰でも使える
  破壊経路になる。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mail-relay.api :as api]
            [mail-relay.authz :as authz]))

(def now 1787000000)
(def did "did:key:z6MkExampleOwner")

(defn- root-headers [& {:as over}]
  (merge {"x-relay-root" did
          "x-relay-signature" "c2ln"
          "x-relay-timestamp" (str now)
          "x-relay-nonce" "n-1"}
         over))

(deftest the-signed-message-covers-every-axis
  (let [base {:method :delete :path "/v1/inboxes/inbox_a" :timestamp now :nonce "n-1"}
        m (authz/challenge-string base)]
    (testing "method を変えると別の署名対象"
      (is (not= m (authz/challenge-string (assoc base :method :get)))
          "覆っていないと、GET の署名で DELETE が通る"))
    (testing "path を変えると別の署名対象"
      (is (not= m (authz/challenge-string (assoc base :path "/v1/inboxes/inbox_b")))
          "覆っていないと、別の箱に対する署名が通る"))
    (testing "時刻を変えると別の署名対象"
      (is (not= m (authz/challenge-string (assoc base :timestamp (inc now))))
          "覆っていないと、一度観測した署名が永久に通る"))
    (testing "nonce を変えると別の署名対象"
      (is (not= m (authz/challenge-string (assoc base :nonce "n-2")))
          "覆っていないと、同じ秒の中で再生できる"))
    (testing "schema が先頭にある —— 別の用途の署名を流用させない"
      (is (str/starts-with? m authz/schema)))))

(deftest a-did-alone-never-authorises
  (let [r (authz/require-for {:auth :root :method :delete :path "/v1/inboxes/inbox_a"
                              :headers {"x-relay-root" did} :now-seconds now})]
    (is (= :reject (:mail-relay.authz/check r)))
    (is (= :mail-relay.authz/missing-signature (:mail-relay.authz/reason r)))
    (is (not (str/blank? (:mail-relay/basis r))))))

(deftest signatures-expire
  (doseq [[label ts ok?] [["今" now true]
                          ["窓の内側" (- now 299) true]
                          ["古すぎる" (- now 301) false]
                          ["未来すぎる" (+ now 301) false]]]
    (let [r (authz/require-for {:auth :root :method :delete :path "/v1/inboxes/inbox_a"
                                :headers (root-headers "x-relay-timestamp" (str ts))
                                :now-seconds now})]
      (is (= ok? (= :root (:mail-relay.authz/check r))) label)))
  (testing "未来側も見る —— 片側だけ見ると時計を進めた署名が実質無期限になる"
    (is (authz/fresh? (- now 10) now))
    (is (not (authz/fresh? (+ now 3600) now)))))

(deftest root-check-hands-the-host-exactly-what-to-verify
  (let [r (authz/require-for {:auth :root :method :post
                              :path "/v1/inboxes/inbox_a/keys/rotate"
                              :headers (root-headers) :now-seconds now})]
    (is (= :root (:mail-relay.authz/check r)))
    (is (= did (:mail-relay.authz/did r)))
    (is (= "c2ln" (:mail-relay.authz/signature r)))
    (is (str/includes? (:mail-relay.authz/message r) "/keys/rotate"))
    (is (str/includes? (:mail-relay.authz/message r) "POST"))))

(deftest key-auth-reads-the-bearer
  (is (= "k_abc" (:mail-relay.authz/presented-key
                  (authz/require-for {:auth :key :headers {"authorization" "Bearer k_abc"}
                                      :now-seconds now}))))
  (is (= "k_abc" (authz/parse-bearer "bearer   k_abc  ")) "大文字小文字と空白で落とさない")
  (is (= :mail-relay.authz/missing-key
         (:mail-relay.authz/reason
          (authz/require-for {:auth :key :headers {} :now-seconds now})))))

(deftest entry-is-the-only-unauthenticated-route
  (testing "表の :none は入場 1 本だけ"
    (is (= [:inbox/open] (mapv :op (filter #(= :none (:auth %)) api/routes)))))
  (is (= :none (:mail-relay.authz/check
                (authz/require-for {:auth :none :headers {} :now-seconds now})))))

(deftest every-reason-has-a-status
  (doseq [reason (keys authz/status-for-reason)]
    (is (integer? (authz/status-for-reason reason))))
  (testing "資格が無いのは 401、資格はあるが通らないのは 403"
    (is (= 401 (authz/status-for-reason :mail-relay.authz/missing-signature)))
    (is (= 403 (authz/status-for-reason :mail-relay.authz/bad-signature)))))

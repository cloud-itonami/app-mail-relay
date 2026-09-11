(ns mail-relay.api-test
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is testing]]
            [mail-relay.api :as api]))

(deftest routing
  (testing "静的 route"
    (is (= :message/list (:op (api/route-for :get "/v1/inboxes/inbox_a/messages")))))

  (testing "path 変数が束縛される"
    (let [r (api/route-for :get "/v1/inboxes/inbox_a/messages/msg_1")]
      (is (= :message/get (:op r)))
      (is (= {:inbox "inbox_a" :msg "msg_1"} (:params r)))))

  (testing "method 違いは別 route"
    (is (= :message/list (:op (api/route-for :get "/v1/inboxes/x/messages"))))
    (is (= :message/send (:op (api/route-for :post "/v1/inboxes/x/messages")))))

  (testing "無い path は nil。黙って何かに当てない"
    (is (nil? (api/route-for :get "/v1/nope")))
    (is (nil? (api/route-for :get "/v1/inboxes")))))

(deftest recovery-paths-are-authorised-by-root-not-key
  ;; 鍵で認可すると、鍵を失った場面でだけ使えない機能になる。
  (testing "close は root"
    (is (= :root (api/auth-required :delete "/v1/inboxes/inbox_a"))))
  (testing "rotate は root"
    (is (= :root (api/auth-required :post "/v1/inboxes/inbox_a/keys/rotate"))))
  (testing "読み書きは鍵でよい"
    (is (= :key (api/auth-required :get "/v1/inboxes/inbox_a/messages")))))

(deftest every-route-is-fully-specified
  (testing "op と auth の無い route を表に残さない"
    (doseq [r api/routes]
      (is (some? (:op r)) (str (:path r) " に :op が無い"))
      (is (contains? #{:key :root :none} (:auth r))
          (str (:path r) " の :auth が " (pr-str (:auth r))))))

  (testing "同じ method+path が二度出てこない"
    (let [ks (map (juxt :method :path) api/routes)]
      (is (= (count ks) (count (distinct ks)))))))

(deftest novel-routes-carry-their-reason
  ;; 差別化の面は、なぜ要るかが書いていないと次の整理で消される。
  (let [novel (api/novel-routes)]
    (is (seq novel) "AgentMail に無い面が 1 つも無いなら、この製品である理由が無い")
    (doseq [r novel]
      (is (not (kotoba.lang.text/blank? (:basis r)))
          (str (:path r) " が :novel なのに :basis が無い")))))

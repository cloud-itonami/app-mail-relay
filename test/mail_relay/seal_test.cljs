(ns mail-relay.seal-test
  "封の経路を**本物の crypto で**回す。Cloudflare の binding は 1 つも要らない。

  主張は 3 つ:
  1. 保管した暗号文に subject も本文も現れない
  2. 鍵を持つ側は開けて、平文のダイジェストまで鎖が繋がる
  3. envelope descriptor は読み戻せる（読み戻せない descriptor は黙ったデータ損失）"
  (:require [cljs.test :as t :refer [deftest is testing async]]
            [clojure.string :as str]
            [kotoba.signal.x25519 :as x25519]
            [cljs.reader]
            [mail-relay.seal :as seal]
            [mail-relay.store :as store]))

(def subject "テスト件 inbound seal")
(def secret-body "この行は暗号文の外に一度も出てはいけない")

(defn- binary
  "テストの入力を、Worker が実際に渡すもの —— 1 文字 1 バイトの文字列 —— に
  揃える。ここで揃えないと、UTF-8 の本文が二重に符号化されたまま通ってしまう。"
  [s]
  (apply str (map #(js/String.fromCharCode %)
                  (array-seq (.encode (js/TextEncoder.) s)))))

(def raw
  (binary
   (str "Received: from mx.example.com\r\n"
       "Authentication-Results: relay.itonami.cloud; spf=pass; dkim=pass; dmarc=pass\r\n"
       "From: Offers <offers@datamarket.example>\r\n"
       "To: meteor-polar-silken-parable@relay.itonami.cloud\r\n"
       "Subject: =?UTF-8?B?" (js/btoa (str/join "" (map #(js/String.fromCharCode %)
                                                        (array-seq (.encode (js/TextEncoder.) subject)))))
       "?=\r\n"
       "Content-Type: text/plain; charset=utf-8\r\n"
       "\r\n"
       secret-body "\r\n")))

(defn- process [recipient]
  (seal/process {:raw raw
                 :from "offers@datamarket.example"
                 :to "meteor-polar-silken-parable@relay.itonami.cloud"
                 :inbox "inbox_a"
                 :persona "meteor-polar-silken-parable@relay.itonami.cloud"
                 :signals [:persona.relay/unexpected-sender]
                 :received-at "2026-08-28T00:00:00Z"
                 :recipient recipient}))

(deftest the-stored-bytes-do-not-contain-the-message
  (async done
    (let [{:keys [priv pub]} (x25519/generate-keypair)]
      (-> (process {:id "did:key:z6MkOwner" :pub pub :custody :self})
          (.then
           (fn [{:keys [index raw parsed] :as result}]
             (let [ciphertext (str (pr-str (mapv #(vec (array-seq %)) (:chunks raw)))
                                   (pr-str (mapv #(vec (array-seq %)) (:chunks parsed))))
                   visible (str (pr-str index) (seal/envelope-json result))]
               (testing "暗号文にも index にも subject と本文は無い"
                 (doseq [leak [subject secret-body]]
                   (is (not (str/includes? ciphertext leak)))
                   (is (not (str/includes? visible leak)))))

               (testing "平文で残ってよいものは残っている"
                 (is (= "offers@datamarket.example" (:mail/envelope-from index)))
                 (is (= :pass (:mail/spf index)))
                 (is (= [:persona.relay/unexpected-sender] (:mail/signals index))))

               (testing "鍵が受取人自身のものなので zero-access"
                 (is (store/zero-access? index)))

               (testing "CID は暗号文に対して付き、そう言う"
                 (is (str/starts-with? (:mail/cid index) "bafkrei"))
                 (is (= :ciphertext (store/cid-covers index))))

               (done))))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

(deftest the-key-holder-opens-it-and-the-chain-closes
  (async done
    (let [{:keys [priv pub]} (x25519/generate-keypair)]
      (-> (process {:id "did:key:z6MkOwner" :pub pub :custody :self})
          (.then
           (fn [{:keys [parsed]}]
             (-> (seal/open-parsed (:envelope parsed) "did:key:z6MkOwner" priv (:chunks parsed))
                 (.then (fn [opened]
                          (let [payload (cljs.reader/read-string opened)]
                            (testing "開けば本文が戻る"
                              (is (str/includes? opened secret-body))
                              (is (str/includes? opened subject)))
                            (-> (seal/digest-hex (seal/binary->bytes raw))
                                (.then (fn [d]
                                         (testing "封の中の平文ダイジェストが、受け取った生バイト列と一致する"
                                           (is (= d (:mail-relay.store/plaintext-digest payload))))
                                         (done))))))))))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

(deftest the-envelope-descriptor-round-trips
  (async done
    (let [{:keys [pub]} (x25519/generate-keypair)]
      (-> (process {:id "did:key:z6MkOwner" :pub pub :custody :self})
          (.then (fn [result]
                   (let [back (seal/parse-envelope-json (seal/envelope-json result))]
                     (testing "namespace が落ちていない —— clj->js は落とす"
                       (is (= (get-in result [:raw :envelope :envelope/id])
                              (get-in back [:raw :envelope/id])))
                       (is (pos? (get-in back [:raw :envelope/chunks])))
                       (is (= "did:key:z6MkOwner"
                              (:recipient/id (first (get-in back [:parsed :envelope/recipients]))))))
                     (done))))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

(deftest a-server-held-key-is-sealed-but-says-so
  (async done
    (let [{:keys [pub]} (x25519/generate-keypair)]
      (-> (process {:id "svc:relay" :pub pub :custody :server})
          (.then (fn [{:keys [index]}]
                   (testing "封はする。が zero-access ではないと 1 通ごとに言う"
                     (is (:mail/sealed index))
                     (is (not (store/zero-access? index)))
                     (is (= :server (:mail/custody index))))
                   (done)))
          (.catch (fn [e] (is false (str "threw: " (.-message e))) (done)))))))

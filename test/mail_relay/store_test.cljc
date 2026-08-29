(ns mail-relay.store-test
  "保管の形のテスト。半分は『平文で何が残るか』の主張で、仕様の説明ではなく
  正直さの境界が動いていないことの確認として読めるようにしてある。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mail-relay.store :as store]))

(deftest object-naming
  (testing "id は inbox を含む —— 含めないと chunk を別の箱に差し替えられる"
    (is (str/includes? (store/object-id "inbox_a" "2026-08-28T00:00:00Z" "d00d") "inbox_a"))
    (is (not= (store/object-id "inbox_a" "t" "d")
              (store/object-id "inbox_b" "t" "d"))))

  (testing "raw と parsed は別の場所。片方だけにしない"
    (let [k (store/object-keys "inbox_a" "d00d")]
      (is (not= (:raw k) (:parsed k)))
      (is (= 4 (count (distinct (vals k)))))
      (is (every? #(str/includes? % "inbox_a") (vals k))))))

(deftest the-body-is-never-in-the-clear
  ;; この deftest がこの repo で一番動かしてはいけないもの。
  (let [fields (set (map :field store/clear-fields))]
    (testing "subject・本文・添付は平文の表に無い"
      (doseq [f [:subject :text :html :body :attachments :parts]]
        (is (not (contains? fields f))
            (str f " が平文の表に入った。zero-access の意味が 1 段削れている"))))

    (testing "平文で残す 1 行ごとに、なぜ封じられないかが書いてある"
      (doseq [{:keys [field why]} store/clear-fields]
        (is (not (str/blank? why)) (str field " に理由が無い"))))))

(deftest cid-does-not-claim-more-than-it-can
  (testing "CID が指すのは暗号文。本文ではない"
    (is (= :ciphertext (store/cid-covers {:mail/cid "bafy…"}))))

  (testing "平文のダイジェストは封の中に入る —— index ではなく"
    (let [payload (store/sealed-payload {:subject "秘密"} "sha256:abc")
          index (store/index-record {:inbox "inbox_a" :id "d" :cid "bafy…"})]
      (is (= "sha256:abc" (:mail-relay.store/plaintext-digest payload)))
      (is (not (contains? index :mail/plaintext-digest))
          "平文のダイジェストを index に置くと、サービスが平文について言えることが増える"))))

(deftest custody-is-per-message
  (testing "鍵が受取人自身のときだけ zero-access"
    (is (store/zero-access? (store/index-record {:custody :self})))
    (is (not (store/zero-access? (store/index-record {:custody :server}))))
    (is (not (store/zero-access? (store/index-record {}))))))

(deftest chunking
  (testing "空でも 1 chunk。空の object も認証は要る"
    (is (= [""] (store/chunk-string "" 8)))
    (is (= [""] (store/chunk-string nil 8))))
  (testing "size ごとに割れる"
    (is (= ["abcd" "efgh" "i"] (store/chunk-string "abcdefghi" 4)))))

(deftest cid-is-a-real-content-address
  ;; 空ファイルの CIDv1（raw / sha2-256）は公知の値。自前の base32 が
  ;; 合っていることを、外の実装が同意する 1 点で固定する。
  (testing "空バイト列の SHA-256 -> 既知の CID"
    (is (= "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"
           (store/cid-for-digest
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))))

  (testing "hex でない・長さが違うものからは CID を作らない"
    (is (nil? (store/cid-for-digest "deadbeef")))
    (is (nil? (store/cid-for-digest nil))))

  (testing "pin されているかは CID とは別の事実"
    (let [i (store/index-record {:cid "bafy…"})]
      (is (not (store/pinned? i)) "CID があることは、引けることを意味しない")
      (is (store/pinned? (store/with-pin i true)))
      (is (= "bafy…" (:mail/cid (store/with-pin i false)))
          "pin が落ちても、何を保管したかは言える"))))

(deftest the-wire-form-drops-no-namespace-and-carries-no-body
  ;; `clj->js` は `(name k)` で鍵を作るので namespace を黙って落とす。
  ;; 落ちたことは出力から見えないので、境界で明示的に写す。
  (let [index (store/index-record {:inbox "inbox_a" :id "d00d"
                                   :from "offers@example.com"
                                   :to "meteor-polar@relay.itonami.cloud"
                                   :persona "meteor-polar@relay.itonami.cloud"
                                   :size 230 :spf :pass :dkim :pass :dmarc :pass
                                   :signals [:persona.relay/unexpected-sender]
                                   :ciphertext-digest "abc" :cid "bafkrei…"
                                   :custody :self})
        wire (store/index->wire index)]
    (testing "鍵はすべて namespace 無し —— JSON にして潰れるものが無い"
      (is (every? #(nil? (namespace %)) (keys wire))))

    (testing "値は JSON にできるものだけ。keyword は文字列に写す"
      (is (= "pass" (:spf wire)))
      (is (= "self" (:custody wire)))
      (is (= ["persona.relay/unexpected-sender"] (:signals wire))
          "信号は namespace ごと残す —— どの層が言ったかが情報"))

    (testing "本文は写す先にも無い"
      (doseq [k [:subject :text :html :body :parts :attachments]]
        (is (not (contains? wire k)))))

    (testing "CID と pin は別のフィールドのまま"
      (is (= "bafkrei…" (:cid wire)))
      (is (false? (:pinned wire)))
      (is (true? (:pinned (store/index->wire (store/with-pin index true))))))))

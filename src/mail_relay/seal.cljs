(ns mail-relay.seal
  "受けた 1 通 -> 封をした 2 つの object + 平文 index 1 行。

  `mail-relay.worker` から分けてあるのは、parse・封・命名の経路全部を
  **本物の crypto で、Cloudflare の binding 無しに**テストで回すため。
  上の Worker がやるのはストリームを読み、鍵を引き、ここが返したものを
  書くことだけ。

  ## 封の中に平文のダイジェストを入れる

  `parsed` の中に平文の SHA-256 を同梱する。CID は暗号文に対して付くので、
  それだけでは『この箱にこのバイト列が入っていた』しか言えない。鍵を持つ
  側が開いたとき、CID -> 暗号文 -> 平文 -> そのダイジェスト、と鎖が繋がる。

  同梱先が index ではないことが要点。index に置いたらサービスが平文に
  ついて言えることが 1 つ増え、封をした意味がその分だけ減る
  （`mail-relay.store` の docstring）。"
  (:require [envelope.seal :as seal]
            [mail-relay.store :as store]
            [mime.parse :as mime]))

;; -------------------------------------------------------------- digest

(defn- hex [^js u8 n]
  (apply str (map #(.padStart (.toString (aget u8 %) 16) 2 "0")
                  (range 0 (or n (.-length u8))))))

(defn digest-hex
  "バイト列でも文字列でも SHA-256 の完全な hex。-> Promise<string>"
  [x]
  (let [bytes (if (string? x) (.encode (js/TextEncoder.) x) x)]
    (-> (js/crypto.subtle.digest "SHA-256" bytes)
        (.then #(hex (js/Uint8Array. %) nil)))))

(defn- short-digest
  "鍵の path に使う先頭 8 バイト。衝突は object-id 側が inbox と時刻で
  縛るので、path が短いことは安全性の主張ではなく可読性の都合。"
  [full]
  (subs full 0 16))

(defn- concat-chunks [chunks]
  (let [total (reduce + (map #(.-length ^js %) chunks))
        out (js/Uint8Array. total)]
    (reduce (fn [off ^js c] (.set out c off) (+ off (.-length c))) 0 chunks)
    out))

(defn ciphertext-digest
  "保管する暗号文そのもののダイジェスト。CID を払い出せなかった日でも、
  何を保管したかは言える。-> Promise<string>"
  [chunks]
  (digest-hex (concat-chunks chunks)))

;; ---------------------------------------------------------------- 封

(defn binary->bytes
  "1 バイト 1 文字の文字列 -> Uint8Array。`worker/bytes->binary-string` の逆。

  ここを `TextEncoder` で済ませてはいけない。あれは UTF-8 に**符号化する**
  ので、0x80–0xFF の 1 バイトが 2 バイトに膨らむ。膨らんだものを保管すると、
  保管した暗号文は『受け取ったバイト列』の暗号文ではなくなり、平文の
  ダイジェストも `sha256sum` した .eml と一致しなくなる。往復はするので
  テストが緩ければ通ってしまう —— だから往復ではなくダイジェストで見る。"
  [s]
  (let [n (count s)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt s i)))
    out))

(defn- seal-text
  "UTF-8 の文字列を、1 人の受取人宛に、独立した object として封じる。"
  [object-id text recipient]
  (seal/seal-object object-id
                    (map #(.encode (js/TextEncoder.) %)
                         (store/chunk-string text store/chunk-bytes))
                    [recipient]
                    {:chunk-bytes store/chunk-bytes}))

(defn- seal-binary
  "受け取ったバイト列**そのもの**を封じる。1 文字 1 バイトの文字列で来るので、
  chunk に割ってからバイトに戻す。"
  [object-id binary-string recipient]
  (seal/seal-object object-id
                    (map binary->bytes (store/chunk-string binary-string store/chunk-bytes))
                    [recipient]
                    {:chunk-bytes store/chunk-bytes}))

(defn process
  "-> Promise<{:index :keys :raw :parsed :parts}>

  `recipient` は `{:id … :pub … :custody :self|:server}`。custody は
  そのまま index に通す —— server 保持の鍵を渡されれば封じるが、
  **そう書く**。

  `:mail/cid` は暗号文のダイジェストから**ここで**出る。content address は
  保管先の性質ではなく内容の性質なので、kotobase に投げる前から言える。
  引けるかどうかは別の事実で、それは `store/with-pin`。"
  [{:keys [raw from to inbox persona signals received-at recipient]}]
  (let [parts (mime/message-parts (mime/parse raw))]
    (-> (digest-hex (binary->bytes raw))
        (.then
         (fn [plaintext-digest]
           (let [id (short-digest plaintext-digest)
                 object-id (store/object-id inbox received-at id)
                 ks (store/object-keys inbox id)
                 r {:id (:id recipient) :pub (:pub recipient)}
                 payload (store/sealed-payload parts plaintext-digest)]
             (-> (js/Promise.all
                  #js [(seal-binary (str object-id ":raw") raw r)
                       (seal-text (str object-id ":parsed") (pr-str payload) r)])
                 (.then
                  (fn [[sealed-raw sealed-parsed]]
                    (-> (ciphertext-digest (:chunks sealed-raw))
                        (.then
                         (fn [ct-digest]
                           {:index (store/index-record
                                    {:inbox inbox
                                     :id id
                                     :from from
                                     :to to
                                     :persona persona
                                     :received-at received-at
                                     :size (count raw)
                                     :spf (:spf parts)
                                     :dkim (:dkim parts)
                                     :dmarc (:dmarc parts)
                                     :signals signals
                                     :ciphertext-digest ct-digest
                                     :cid (store/cid-for-digest ct-digest)
                                     :custody (:custody recipient :server)})
                            :keys ks
                            :raw sealed-raw
                            :parsed sealed-parsed
                            :parts parts})))))))))))) 

;; ------------------------------------------------------ envelope の保存

(defn- qualified-name
  "`:envelope/id` -> \"envelope/id\"。

  `clj->js` は使えない —— あれは `(name k)` で鍵を作るので namespace を
  **黙って落とす**。落ちると `:envelope/*` と `:recipient/*` が全部短名に
  潰れ、保管した descriptor が読み戻せなくなる。kotobase の mail-worker が
  round-trip テストで踏んだ穴で、同じ穴をここでも踏まないために写した。"
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- ->js-deep [x]
  (cond
    (map? x) (let [o (js-obj)]
               (doseq [[k v] x] (aset o (qualified-name k) (->js-deep v)))
               o)
    (sequential? x) (to-array (map ->js-deep x))
    (keyword? x) (qualified-name x)
    :else x))

(defn envelope-json
  "暗号文の隣に置く wrap descriptor。公開鍵の材料しか入らない —— それが
  この設計の全部。"
  [{:keys [raw parsed]}]
  (js/JSON.stringify
   (->js-deep {:raw (update (:envelope raw) :envelope/recipients vec)
               :parsed (update (:envelope parsed) :envelope/recipients vec)})))

(defn parse-envelope-json
  "`envelope-json` の逆。読み戻せない descriptor は黙ったデータ損失なので、
  仮定せずテストで回す。"
  [s]
  (let [->kw (fn [m] (into {} (map (fn [[k v]] [(keyword k) v])) m))
        ->env (fn [e]
                (-> (->kw e)
                    (update :envelope/recipients #(mapv ->kw %))
                    (update :envelope/alg keyword)
                    (update :envelope/kdf keyword)
                    (update :envelope/kem keyword)
                    (update :envelope/recipients
                            (fn [rs] (mapv #(update % :recipient/kind keyword) rs)))))
        raw (js->clj (js/JSON.parse s))]
    {:raw (->env (get raw "raw"))
     :parsed (->env (get raw "parsed"))}))

(defn- open-bytes [envelope recipient-id priv chunks]
  (seal/open-object envelope (seal/entry-for envelope recipient-id) priv chunks))

(defn open-parsed
  "読む側。鍵を持つ client とテストのために、封じた `parsed` を戻す。
  中身は UTF-8 の EDN なので `TextDecoder` でよい。"
  [envelope recipient-id priv chunks]
  (-> (open-bytes envelope recipient-id priv chunks)
      (.then (fn [opened]
               (->> opened
                    (map #(.decode (js/TextDecoder.) %))
                    (apply str))))))

(defn open-raw
  "封じた `raw` を、受け取ったときと同じ 1 文字 1 バイトの文字列に戻す。

  `open-parsed` と分けてあるのは、`raw` を `TextDecoder` に通してはいけない
  から —— あれは UTF-8 として解釈するので、UTF-8 ではないバイト列（添付の
  base64 の外にある生バイトなど）を置換文字に潰す。"
  [envelope recipient-id priv chunks]
  (-> (open-bytes envelope recipient-id priv chunks)
      (.then (fn [opened]
               (apply str (map (fn [^js u8]
                                 (.apply js/String.fromCharCode nil u8))
                               opened))))))

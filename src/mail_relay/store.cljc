(ns mail-relay.store
  "1 通を保管したときの形 —— どこに置くか、平文で何が残るか、CID は何に
  対して付くか。

  IO は無い。R2 も kotobase の pin も Worker 側の機構で、ここが返すのは
  鍵名と index と『何が平文か』の表だけ。純 `.cljc` なので、bucket も MX も
  無しで全部テストできる。

  ## CID は暗号文に対して付く

  ADR-2608211300 は『受信メール 1 通ごとに検証可能な CID』を求めている。
  ここで正直に書いておかなければならないのは —— **封をしてから保管する以上、
  CID が指すのは暗号文であって、差出人が送った本文ではない**ということ。
  暗号文の CID だけでは『この箱に入っていたのはこのバイト列だ』しか言えず、
  『その中身がこの文面だった』は言えない。

  そこで平文のダイジェストを**封の中に**入れる（`plaintext-digest`）。鍵を
  持つ側だけが開いて、CID → 暗号文 → 平文 → そのダイジェスト、と鎖を
  繋げられる。鍵を持たないサービスは鎖のどこにも手を入れられない。

  この 2 段を分けずに『CID があるので本文が証明できる』と書くのが、この
  種の設計で最もよくある嘘なので、`cid-covers` を関数にして呼び出し側に
  聞かせる。"
  (:require [clojure.string :as str]))

(def schema "mail-relay.store.v1")

(def max-message-bytes
  "Cloudflare Email Routing がハンドラに渡す上限（25 MiB）。これを超える 1 通は
  このコードが動く前にネットワーク側で断られる。admission 規則が何に対して
  書かれているかを示すためにここに置く —— ハンドラの中の裸の定数にしない。"
  (* 25 1024 1024))

(def chunk-bytes
  "封をする単位。1 MiB。メール本文はたいてい 1 chunk で収まるが、添付を
  含む 1 通を Worker の 128 MB の中で並行に封をするので小さめに取る。"
  (* 1024 1024))

;; ---------------------------------------------------------------- 名前

(defn object-id
  "封をした 2 つの object が AAD で縛られる id。

  inbox を含める。含めないと、Message-ID を握った相手が chunk を別の箱の
  1 通に差し替えられる。"
  [inbox received-at digest]
  (str "mail:" inbox ":" received-at ":" digest))

(defn object-keys
  "封をした object と平文 index の置き場所。

  `raw` と `parsed` の 2 つを持つ。`raw` は元のバイト列で、後から DKIM を
  検証し直したり、今より良いパーサで読み直したりするために要る。`parsed` は
  構造化した形で、読む側に MIME パーサを配らないために要る。`parsed` だけを
  封じると証拠が消え、`raw` だけを封じると読む全員にパーサが要る。"
  [inbox id]
  {:raw      (str "mail/" inbox "/" id "/raw")
   :parsed   (str "mail/" inbox "/" id "/parsed")
   :envelope (str "mail/" inbox "/" id "/envelope.json")
   :index    (str "index/" inbox "/" id)})

;; ------------------------------------------------------ 平文で残るもの

(def clear-fields
  "このサービスが平文で持つフィールドと、**なぜ封じられないか**。

  この vector がこの設計の正直さの境界。`:subject`・本文・添付はここに
  無い —— 暗号文の中にあり、そのことをテストが主張する。ここに 1 行足す
  ことは zero-access の意味を 1 段削ることなので、コードで議論する前に
  ADR で議論すること。"
  [{:field :envelope-from  :why "SMTP が言った。ネットワークは既に知っている"}
   {:field :envelope-to    :why "振り分けの判断そのもの。どの鍵で封じるかを決めるのに要る"}
   {:field :persona        :why "envelope-to と同じもの。どの顔で受けたかは to に書いてある"}
   {:field :received-at    :why "Worker が走った時刻。内容から導いていない"}
   {:field :size           :why "object 自身の長さ。隠せない"}
   {:field :spf            :why "このコードが 1 通を見る前に受信 MTA が計算している"}
   {:field :dkim           :why "同上"}
   {:field :dmarc          :why "同上"}
   {:field :signals        :why "差出人ドメインと相手の比較。両方とも上の 2 行から出る"}
   {:field :cid            :why "暗号文のアドレス。中身を言わない（cid-covers を見ること）"}
   {:field :custody        :why "鍵が受取人自身のものか。主張している性質そのもの"}])

(defn cid-covers
  "CID が何を指しているか。**常に `:ciphertext`。**

  定数を返す関数にしてあるのは、呼び出し側に一度は聞かせるため。
  『CID があるので本文を証明できる』は嘘で、平文まで鎖を繋げられるのは
  封を開けられる側 —— つまり鍵を持つ受取人だけ。"
  [_index]
  :ciphertext)

(defn index-record
  "平文の index。意図して狭い —— 一覧に要るもののうち、サービスが本文を
  読まずとも不可避に知っているものだけ。

  `:mail/custody` は 1 通ごと。`:self` は鍵が受取人自身のもので本当に
  zero-access。`:server` はサービスが開けられる —— 封はされているが
  zero-access ではない。どちらだったかを 1 通ごとに言えることが、
  性質と宣伝文句の違い。"
  [{:keys [inbox id from to persona received-at size spf dkim dmarc
           signals ciphertext-digest cid custody]}]
  {:mail/id            id
   :mail/inbox         inbox
   :mail/envelope-from from
   :mail/envelope-to   to
   :mail/persona       persona
   :mail/received-at   received-at
   :mail/size          size
   :mail/spf           spf
   :mail/dkim          dkim
   :mail/dmarc         dmarc
   :mail/signals       (vec signals)
   :mail/ciphertext-digest ciphertext-digest
   :mail/cid           cid
   :mail/custody       custody
   :mail/sealed        true})

(def ^:private base32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn- hex->bytes [h]
  (mapv #(#?(:clj Long/parseLong :cljs js/parseInt) (subs h % (+ % 2)) 16)
        (range 0 (count h) 2)))

(defn- base32
  "RFC 4648 base32、小文字、padding 無し。multibase の `b` はこの並び。"
  [bytes]
  (let [bits (mapcat (fn [b] (map #(if (bit-test b %) 1 0) (range 7 -1 -1))) bytes)]
    (apply str
           (map (fn [g]
                  (let [padded (concat g (repeat (- 5 (count g)) 0))]
                    (nth base32-alphabet (reduce (fn [a d] (+ (* 2 a) d)) 0 padded))))
                (partition-all 5 bits)))))

(def cid-prefix
  "CIDv1 / raw codec / sha2-256 / 32 バイト。この 4 バイトが『生のバイト列を
  SHA-256 で指す』という宣言そのもの。"
  [0x01 0x55 0x12 0x20])

(defn cid-for-digest
  "暗号文の SHA-256（hex）-> CIDv1（`b…`、base32 小文字）。

  **ネットワークを一度も触らずに出る。** content address は保管先の性質では
  なく内容の性質なので、pin する前から言える。pin して初めて CID が出ると
  いう設計にすると、pin が落ちた日に『何を保管したか』を言えなくなる。"
  [hex-digest]
  (when (and (string? hex-digest) (= 64 (count hex-digest)))
    (str "b" (base32 (concat cid-prefix (hex->bytes hex-digest))))))

(defn with-pin
  "kotobase の pin が通ったかを記録する。

  CID とは別の事実。CID は『何を保管したか』、pin は『kotobase から引ける
  か』。1 つにまとめると、pin が落ちた 1 通の CID まで消える。"
  [index pinned?]
  (assoc index :mail/pinned (boolean pinned?)))

(defn pinned?
  "kotobase から引けるか。CID があることとは別。"
  [index]
  (true? (:mail/pinned index)))

(defn zero-access?
  "鍵を受取人が持っているときだけ真。**関数にしてあるのは、呼び出し側に
  仮定させず聞かせるため。**"
  [index]
  (= :self (:mail/custody index)))

(defn index->wire
  "index を HTTP に出す形。

  **namespace 付きの鍵をそのまま JSON にしない。** `clj->js` は `(name k)`
  で鍵を作るので namespace を黙って落とし、`:mail/cid` と
  `:mail-relay.store/cid` が同じ `\"cid\"` に潰れる。潰れたことは出力から
  見えないので、境界で明示的に写す。

  写す先に本文が無いことは、`clear-fields` と同じ主張をこの層でも持つため。"
  [index]
  {:id                (:mail/id index)
   :inbox             (:mail/inbox index)
   :from              (:mail/envelope-from index)
   :to                (:mail/envelope-to index)
   :persona           (:mail/persona index)
   :received_at       (:mail/received-at index)
   :size              (:mail/size index)
   :spf               (some-> (:mail/spf index) name)
   :dkim              (some-> (:mail/dkim index) name)
   :dmarc             (some-> (:mail/dmarc index) name)
   :signals           (mapv #(if (keyword? %) (subs (str %) 1) (str %))
                            (:mail/signals index))
   :ciphertext_digest (:mail/ciphertext-digest index)
   :cid               (:mail/cid index)
   :pinned            (pinned? index)
   :custody           (some-> (:mail/custody index) name)
   :sealed            (true? (:mail/sealed index))})

(defn chunk-string
  "平文を `size` バイトずつに割る。空入力でも 1 chunk 返す —— 空の object も
  認証は要る（`envelope.model/chunk-count`）。"
  [s size]
  (if (str/blank? (or s ""))
    [""]
    (mapv #(apply str %) (partition-all size s))))

(defn sealed-payload
  "封の中に入れる値。`parsed` に平文のダイジェストを**同梱する**ので、
  鍵を持つ側は CID から本文まで鎖を繋げられる。

  同梱先が `parsed` であって index でないことが要点。index に置いたら
  サービスが平文について言えることが 1 つ増え、それは封をした意味を削る。"
  [parts plaintext-digest]
  {:mail-relay.store/schema schema
   :mail-relay.store/parts parts
   :mail-relay.store/plaintext-digest plaintext-digest})

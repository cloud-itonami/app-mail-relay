(ns mail-relay.authz
  "route 表の `:auth` を、実際に検査するものへ翻訳する。

  crypto はここに無い。SHA-256 も Ed25519 も host（`mail-relay.root-auth`）
  の仕事で、ここが返すのは『何を、何と突き合わせるか』という値だけ。

  ## root を『DID をヘッダに入れる』にしない

  `close` と `rotate` は root で認可する（ADR-2608211300）。それを
  『所有者の DID をヘッダに書けば通る』と実装すると、**DID を知っている
  誰でも他人の箱を閉じられる**。DID は公開の識別子なので秘密ではない。
  鍵を失った利用者を救う経路が、そのまま誰でも使える破壊経路になる。

  だから root は署名で認可する。`did:key:z6Mk…` に埋まっている Ed25519
  公開鍵で、この 1 リクエストに対する署名を検証する。

  ## 署名の対象に method と path と時刻を全部入れる

  署名が本文だけを覆っていると、`rotate` に対する署名を `close` に
  付け替えられる。時刻が入っていないと、一度観測した署名を後で再生できる。
  `challenge-string` が 1 つの関数として両方を持つのは、片方だけ入れる
  変更をレビューで見つけられるようにするため。"
  (:require [kotoba.lang.text :as str]))

(def schema "mail-relay.authz.v1")

(def max-skew-seconds
  "署名の有効窓。両側 5 分。Svix の webhook と同じ幅にしてある。"
  300)

(defn challenge-string
  "署名の対象。**method・path・時刻・nonce を全部覆う。**

  1 つでも落とすと、落とした軸で付け替えか再生ができる:
  - method を落とす -> GET の署名で DELETE が通る
  - path を落とす   -> 別の箱に対する署名が通る
  - 時刻を落とす    -> 一度観測した署名が永久に通る
  - nonce を落とす  -> 同じ秒の中で再生できる"
  [{:keys [method path timestamp nonce]}]
  (str/join "\n" [schema
                  (str/upper (name (or method "")))
                  (str path)
                  (str timestamp)
                  (str nonce)]))

(defn fresh?
  "署名の時刻が窓の中か。未来側も見る —— 片側だけ見ると、時計を進めた
  署名が実質無期限になる。"
  [timestamp now-seconds]
  (let [ts (cond (number? timestamp) timestamp
                 (string? timestamp) (parse-long (str/trim timestamp))
                 :else nil)]
    (boolean (and ts (<= (abs (- now-seconds ts)) max-skew-seconds)))))

(defn parse-bearer
  "`Authorization: Bearer <key>` から鍵を取り出す。無ければ nil。"
  [header]
  (let [h (some-> header str str/trim)]
    (when (and h (str/starts-with? (str/lower h) "bearer "))
      (not-empty (str/trim (subs h 7))))))

(defn- reject [reason & {:as attrs}]
  (merge {:mail-relay.authz/check :reject :mail-relay.authz/reason reason} attrs))

(defn require-for
  "route の `:auth` と、提示された資格から、host が実行すべき検査を返す。

  -> `{:check :none}`
     `{:check :key  :presented-key \"…\"}`             host が digest して account/authorize
     `{:check :root :did … :signature … :message …}`   host が Ed25519 で検証
     `{:check :reject :reason …}`"
  [{:keys [auth method path headers now-seconds]}]
  (let [h (fn [k] (get headers k))]
    (case auth
      :none {:mail-relay.authz/check :none}

      :key (if-let [k (parse-bearer (h "authorization"))]
             {:mail-relay.authz/check :key
              :mail-relay.authz/presented-key k}
             (reject :mail-relay.authz/missing-key))

      :root (let [did (some-> (h "x-relay-root") str str/trim not-empty)
                  sig (some-> (h "x-relay-signature") str str/trim not-empty)
                  ts (some-> (h "x-relay-timestamp") str str/trim not-empty)
                  nonce (some-> (h "x-relay-nonce") str str/trim not-empty)]
              (cond
                (not did) (reject :mail-relay.authz/missing-root)
                (not sig) (reject :mail-relay.authz/missing-signature
                                  :mail-relay/basis
                                  "DID だけで通すと、公開の識別子を知る誰でも他人の箱を閉じられる")
                (not ts) (reject :mail-relay.authz/missing-timestamp)
                (not nonce) (reject :mail-relay.authz/missing-nonce)
                (not (fresh? ts now-seconds)) (reject :mail-relay.authz/stale-signature)
                :else {:mail-relay.authz/check :root
                       :mail-relay.authz/did did
                       :mail-relay.authz/signature sig
                       :mail-relay.authz/message (challenge-string {:method method :path path
                                                                    :timestamp ts :nonce nonce})}))

      (reject :mail-relay.authz/unknown-auth-kind))))

(def status-for-reason
  "拒否の理由 -> HTTP status。資格が無いのは 401、資格はあるが通らないのは 403。"
  {:mail-relay.authz/missing-key       401
   :mail-relay.authz/missing-root      401
   :mail-relay.authz/missing-signature 401
   :mail-relay.authz/missing-timestamp 401
   :mail-relay.authz/missing-nonce     401
   :mail-relay.authz/stale-signature   401
   :mail-relay.authz/bad-signature     403
   :mail-relay.authz/unknown-auth-kind 500})

(ns mail-relay.account
  "受信箱アカウントの生存判断 —— 開く・閉じる・鍵を回す。

  機構はここに無い。鍵の生成も、SMTP も、kotobase への保管も host の仕事で、
  ここが答えるのは『この操作を通してよいか』だけ。答えは値として返る。

  1 通ごとの振り分け（誰宛か・出してよいか・差出人が想定内か）は
  `persona.relay` が持っている。ここはその上の層。

  ## なぜ鍵ではなく root で認可するのか

  この設計は 2026-08-21 に実際に踏んだ失敗から来ている。AgentMail 互換の
  受信箱を作り、返ってきた `account_key` を保管し損ねた。鍵は**一度しか
  返らない**ので、その受信箱は読めなくなった。それだけなら自業自得だが、
  問題はその先で —— 解放する経路が無い。`DELETE` は 404、鍵が無いので
  認証もできない。**死んだ受信箱が『1 origin につき 1 箱』の枠を占有した
  まま、こちらからは何もできなくなった。**

  ここから 2 つ引いた。

  **`close` と `rotate` は鍵では認可しない。root（所有者の DID）で認可する。**
  鍵を失うことは起こる。起こったときに箱ごと死ぬのは、鍵とアカウントの
  identity を同一視した設計の欠陥であって、利用者の不注意ではない。

  **`close` を必ず持つ。** 入場制限を課すなら、退場経路を同時に出す。
  片方だけ実装すると、失敗した利用者が自力で回復できない状態に落ちる。

  ## registry の形

      {:mail-relay/accounts
       {\"inbox_...\" {:mail-relay.account/id \"inbox_...\"
                       :mail-relay.account/root \"did:key:z6Mk...\"
                       :mail-relay.account/key-digest \"sha256:...\"
                       :mail-relay.account/origin \"203.0.113.7\"
                       :mail-relay.account/state :active
                       :mail-relay.account/opened-at 1787000000000}}}

  `key-digest` であって鍵そのものではない。ここは判断だけの層なので、
  平文の鍵を持たない。"
  (:require [clojure.string :as str]))

(def schema "mail-relay.account.v1")

(defn- blank? [s] (str/blank? (str s)))

(defn accounts
  "registry の全アカウント。"
  [registry]
  (vals (:mail-relay/accounts registry)))

(defn account-at
  "id で 1 件引く。無ければ nil。"
  [registry id]
  (get-in registry [:mail-relay/accounts id]))

(defn active-at-origin
  "その origin で今 active なアカウント。origin は IP でも DID でも
  『同一利用者を数える単位』なら何でもよい —— ここは文字列として扱う。"
  [registry origin]
  (->> (accounts registry)
       (filter #(and (= origin (:mail-relay.account/origin %))
                     (= :active (:mail-relay.account/state %))))
       (sort-by :mail-relay.account/id)))

(def default-limits
  "1 origin あたりの同時 active 数。AgentMail は 1 だった。ここも既定は 1 に
  するが、**閉じられる**ので詰まらない。"
  {:mail-relay.limit/active-per-origin 1})

(defn open
  "アカウントを開いてよいか。通れば `:mail-relay/registry` に新しい registry が入る。

  `key-digest` は host が生成した鍵のダイジェスト。平文の鍵はここに来ない。"
  [registry {:keys [id root key-digest origin now limits]}]
  (let [limits (merge default-limits limits)
        cap (:mail-relay.limit/active-per-origin limits)
        held (count (active-at-origin registry origin))]
    (cond
      (blank? id)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/missing-id}

      (blank? root)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/missing-root
       :mail-relay/basis "root が無いと、鍵を失ったときに閉じることも回すこともできなくなる"}

      (blank? key-digest)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/missing-key-digest}

      (some? (account-at registry id))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/duplicate-id}

      (>= held cap)
      {:mail-relay/decision :refuse
       :mail-relay/reason :mail-relay/origin-slot-taken
       :mail-relay/held (mapv :mail-relay.account/id (active-at-origin registry origin))
       :mail-relay/basis
       "この origin の枠は埋まっている。close で解放できる —— 鍵を失っていても root で閉じられる"}

      :else
      {:mail-relay/decision :open
       :mail-relay/account-id id
       :mail-relay/registry
       (assoc-in registry [:mail-relay/accounts id]
                 {:mail-relay.account/id id
                  :mail-relay.account/root root
                  :mail-relay.account/key-digest key-digest
                  :mail-relay.account/origin origin
                  :mail-relay.account/state :active
                  :mail-relay.account/opened-at now})})))

(defn close
  "アカウントを閉じ、origin の枠を解放する。**root で認可する。鍵は要らない。**"
  [registry {:keys [id root]}]
  (let [a (account-at registry id)]
    (cond
      (nil? a)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/unknown-account}

      (not= root (:mail-relay.account/root a))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/not-yours}

      (= :closed (:mail-relay.account/state a))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/already-closed}

      :else
      {:mail-relay/decision :close
       :mail-relay/account-id id
       :mail-relay/registry
       (assoc-in registry [:mail-relay/accounts id :mail-relay.account/state] :closed)})))

(defn rotate
  "鍵を差し替える。**root で認可する。現在の鍵は要らない。**

  現在の鍵を要求する設計だと、鍵を失った時点で回すこともできなくなる。
  それは回復手段としての rotate を、回復が要る場面でだけ使えなくする。"
  [registry {:keys [id root new-key-digest]}]
  (let [a (account-at registry id)]
    (cond
      (nil? a)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/unknown-account}

      (not= root (:mail-relay.account/root a))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/not-yours}

      (not= :active (:mail-relay.account/state a))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/account-closed}

      (blank? new-key-digest)
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/missing-key-digest}

      (= new-key-digest (:mail-relay.account/key-digest a))
      {:mail-relay/decision :refuse :mail-relay/reason :mail-relay/same-key
       :mail-relay/basis "同じ鍵に回しても、漏れた鍵は失効しない"}

      :else
      {:mail-relay/decision :rotate
       :mail-relay/account-id id
       :mail-relay/superseded (:mail-relay.account/key-digest a)
       :mail-relay/registry
       (assoc-in registry [:mail-relay/accounts id :mail-relay.account/key-digest]
                 new-key-digest)})))

(defn authorize
  "提示された鍵ダイジェストで読んでよいか。registry は変わらない。"
  [registry {:keys [id key-digest]}]
  (let [a (account-at registry id)]
    (cond
      (nil? a)
      {:mail-relay/decision :deny :mail-relay/reason :mail-relay/unknown-account}

      (not= :active (:mail-relay.account/state a))
      {:mail-relay/decision :deny :mail-relay/reason :mail-relay/account-closed}

      (blank? key-digest)
      {:mail-relay/decision :deny :mail-relay/reason :mail-relay/missing-key-digest}

      (not= key-digest (:mail-relay.account/key-digest a))
      {:mail-relay/decision :deny :mail-relay/reason :mail-relay/key-mismatch}

      :else
      {:mail-relay/decision :allow :mail-relay/account-id id})))

(ns mail-relay.inbound
  "受信した 1 通をどうするか。

  機構はここに無い。MX も Email Routing も Resend の webhook も R2 も、
  すべて host 側。ここが答えるのは『この 1 通を受けてよいか、どの箱の
  どの顔宛か、何を覚えるか』だけで、答えは値として返る。

  ## 転送ではなく保管なので、`persona.relay` の :forward を :store に写す

  `persona.relay/inbound` は Hide My Email の形 —— 本体の実アドレスへ
  **転送する**判断として書かれている。この relay はエージェント向けなので
  本体の実アドレスは無く、箱に**溜めて API で読ませる**。

  そこで `destination` に inbox の id を渡し、返ってきた `:forward` を
  `:store` に写す。`persona.relay` を書き換えないのは、あちらの利用者が
  この repo だけではないから。写す 1 箇所をここに閉じ込めて、写し方を
  テストで固定する。

  `destination` を空で渡すと `persona.relay` は `:no-destination` で
  reject する —— それは『黙って捨てない』ための設計なので、こちらが
  inbox id を渡さずに済ませてはいけない。

  ## 落とすときは必ず reject する

  受けられない 1 通は **reject** であって、黙って捨てない。reject は
  差出人にバウンスするので、差出人は届かなかったことを知る。捨てると、
  誰も気づかないままメールが消える。"
  (:require [kotoba.lang.text :as str]
            [mail-relay.account :as account]
            [mail-relay.store :as store]
            [persona.relay :as relay]))

(def schema "mail-relay.inbound.v1")

(defn normalize-address
  "受信側のアドレスを比較できる形にする。`+tag` は落とさない。

  Gmail は `a.b@` と `ab@` を同じ箱として扱うが、それは Gmail の方針で
  あって SMTP の規則ではない。ここで真似ると、別々に発行した 2 本の顔が
  黙って 1 つに融ける —— この repo が配っているのは『相手ごとに 1 本』
  なので、融けた時点で存在理由が消える。"
  [a]
  (some-> a str str/trim str/lower not-empty))

(def reject-reasons
  "落とす理由と、差出人に何が起きるか。全て bounce する。"
  {:mail-relay.inbound/unparseable-recipient "宛先が読めない"
   :mail-relay.inbound/unknown-address       "発行されていないアドレス"
   :mail-relay.inbound/too-large             "Email Routing の上限を超えている"
   :mail-relay.inbound/account-closed        "箱が閉じている"
   :mail-relay.inbound/address-burned        "この顔は止められている"
   :mail-relay.inbound/address-disabled      "この顔は停止中"})

(defn admit
  "ネットワークから受けた 1 通を、そもそも受けてよいか。

  `resolve` は host が持つ逆引き —— `address -> {:inbox id :kind :persona|:base}`
  または nil。account registry に持たせないのは、registry が inbox id を
  鍵にした**判断**の層だからで、アドレスの逆引き表を混ぜると判断関数が
  全部その表を持ち歩くことになる。

  -> `{:mail-relay/accept {…}}` / `{:mail-relay/reject reason}`"
  [{:keys [to size resolve]}]
  (let [address (normalize-address to)
        hit (when address (resolve address))]
    (cond
      (nil? address)
      {:mail-relay/reject :mail-relay.inbound/unparseable-recipient}

      (nil? hit)
      {:mail-relay/reject :mail-relay.inbound/unknown-address}

      (and size (> size store/max-message-bytes))
      {:mail-relay/reject :mail-relay.inbound/too-large}

      :else
      {:mail-relay/accept (assoc hit :address address)})))

(defn- persona-reject-reason [reason]
  (case reason
    :persona.relay/address-burned   :mail-relay.inbound/address-burned
    :persona.relay/address-disabled :mail-relay.inbound/address-disabled
    :mail-relay.inbound/unknown-address))

(defn route
  "`admit` を通った 1 通を、account registry と persona directory に照らして
  最終決定する。

  -> `{:mail-relay/decision :store   :mail-relay/inbox … :mail-relay/persona …
       :mail-relay/signals […]}`
     `{:mail-relay/decision :reject  :mail-relay/reason …}`"
  [{:keys [registry directory inbox kind address sender]}]
  (let [a (account/account-at registry inbox)]
    (cond
      (nil? a)
      {:mail-relay/decision :reject
       :mail-relay/reason :mail-relay.inbound/unknown-address}

      (not= :active (:mail-relay.account/state a))
      {:mail-relay/decision :reject
       :mail-relay/reason :mail-relay.inbound/account-closed
       :mail-relay/basis "閉じた箱に溜め続けると、close が解放にならない"}

      (= :base kind)
      ;; 箱そのもののアドレス。顔を経由していないので persona の判断は要らず、
      ;; 記録すべき相手も無い。
      {:mail-relay/decision :store
       :mail-relay/inbox inbox
       :mail-relay/persona nil
       :mail-relay/signals []}

      :else
      (let [d (relay/inbound directory {:address address
                                        :sender sender
                                        ;; 転送先ではなく保管先。:no-destination を
                                        ;; 踏まないために必ず渡す。
                                        :destination inbox})
            signals (vec (:persona.relay/signals d))]
        (if (= :forward (:persona.relay/decision d))
          {:mail-relay/decision :store
           :mail-relay/inbox inbox
           :mail-relay/persona (:persona.relay/persona d)
           :mail-relay/signals signals}
          {:mail-relay/decision :reject
           :mail-relay/reason (persona-reject-reason (:persona.relay/reason d))
           :mail-relay/signals signals})))))

(defn note
  "この 1 通について**覚える**こと。`route` と分けてあるのは、判断が状態を
  書き換えないため —— 同じ 1 通について『どうするか』と『何を覚えるか』を
  別々に決められる。

  覚えるのは差出人のドメインだけ。想定外の差出人が並ぶことが
  `GET /v1/personas/{address}/senders` の中身で、そのアドレスが漏れたか
  売られたかの帰責になる。**これは Apple にはできない** —— 向こうは alias を
  どの相手に渡したかを持っていない。"
  [directory {:keys [address sender kind]}]
  (if (= :base kind)
    directory
    (relay/note-sender directory address sender)))

(defn summary-line
  "Worker のログ 1 行。本文も subject も入らない —— それはこの経路が
  知らずに済ませるために存在するもの。"
  [{:keys [index]}]
  (str/join " " ["inbound"
                 (:mail/inbox index)
                 (str "size=" (:mail/size index))
                 (str "spf=" (:mail/spf index))
                 (str "dkim=" (:mail/dkim index))
                 (str "signals=" (count (:mail/signals index)))
                 (str "custody=" (name (or (:mail/custody index) :server)))]))

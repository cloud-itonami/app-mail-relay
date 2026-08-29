(ns mail-relay.send
  "`POST /v1/inboxes/{inbox}/messages` —— 1 通出す判断と、その結果としての
  provider リクエスト。

  IO は無い。HTTP を撃つのは host で、ここが返すのは『出してよいか』と
  『出すなら誰宛にどう組み立てたリクエストか』という値だけ。秘密も持たない
  —— `mailer` が返すリクエストは `:http/auth-secret :resend-api-key` という
  **名前**を持ち、鍵そのものは host が差す。

  ## From を引数で受け取らない

  この API に `from` は無い。呼び出し側が選ぶのは `address`（どの顔で
  出すか）で、実際の From は `persona.relay/outbound` が `:allow` のときに
  **必ず**返す値をそのまま使う。

  受信を alias にするのは簡単で、たいていの実装はそこで終わる。壊れるのは
  返信で、**1 通を本体のアドレスから返した瞬間に全部の顔が結びつく**。
  しかも相手には普通に届くので誰も気づかない。だから From を組み立てる
  自由を呼び出し側に残さない。

  `from` が渡ってきたら**黙って無視せず refuse する**。無視すると、
  『From を指定したつもりの client』が、指定できていないことを一生知らない。"
  (:require [clojure.string :as str]
            [mail-relay.account :as account]
            [mail.draft :as draft]
            [mail.message :as message]
            [mailer.core :as mailer]
            [persona.relay :as relay]))

(def schema "mail-relay.send.v1")

(def default-provider
  "既定は Resend。`mailer` は `:ses` と `:smtp` も組み立てられるので、
  到達性の実測でどれを本番にするかは後から差し替えられる。"
  :resend)

(defn- blank? [s] (str/blank? (str s)))

(defn- refuse [reason & {:as attrs}]
  (merge {:mail-relay/decision :refuse :mail-relay/reason reason} attrs))

(defn- outbound-refusal [reason]
  (case reason
    :persona.relay/unknown-address  :mail-relay.send/unknown-address
    :persona.relay/not-yours        :mail-relay.send/not-yours
    :persona.relay/address-burned   :mail-relay.send/address-burned
    :persona.relay/address-disabled :mail-relay.send/address-disabled
    :mail-relay.send/refused))

(defn plan
  "1 通出す。

  -> `{:mail-relay/decision :send
       :mail-relay/from     \"…@relay.itonami.cloud\"   ← persona が決めた
       :mail-relay/effect   mail/send effect
       :mail-relay/request  provider request（host が撃つ）
       :mail-relay/signals  […]}`
     または `{:mail-relay/decision :refuse :mail-relay/reason …}`"
  [{:keys [registry directory inbox key-digest root address
           to cc subject text html reply-to from
           provider draft-id]}]
  (let [provider (or provider default-provider)
        auth (account/authorize registry {:id inbox :key-digest key-digest})
        to (cond (nil? to) [] (sequential? to) (vec to) :else [to])
        recipient (first to)]
    (cond
      (some? from)
      (refuse :mail-relay.send/from-is-not-yours
              :mail-relay/basis
              "From は persona が決める。ここで受け取ると、1 通を本体から出して全部の顔を結びつける事故が通る")

      (not= :allow (:mail-relay/decision auth))
      (refuse (:mail-relay/reason auth))

      (empty? to)
      (refuse :mail-relay.send/missing-recipient)

      (not (contains? mailer/supported-providers provider))
      (refuse :mail-relay.send/unsupported-provider :mail-relay/provider provider)

      :else
      (let [d (relay/outbound directory {:root root :address address :recipient recipient})
            signals (vec (:persona.relay/signals d))]
        (if (not= :allow (:persona.relay/decision d))
          (refuse (outbound-refusal (:persona.relay/reason d)) :mail-relay/signals signals)
          (let [m (message/message {:from (:persona.relay/from d)
                                    :to to :cc cc :subject subject
                                    :text text :html html
                                    :reply-to reply-to})
                errors (message/validation-errors m)]
            (if (seq errors)
              (refuse :mail-relay.send/invalid-message
                      :mail-relay/errors (vec errors)
                      :mail-relay/signals signals)
              (let [effect (-> (draft/draft (or draft-id (str "draft-" inbox "-" (hash [address to subject])))
                                            m)
                               (draft/approve {:mail-relay.approval/by root
                                               :mail-relay.approval/persona (:persona.relay/from d)})
                               (draft/send-effect {:mail.effect/status :approved}))]
                {:mail-relay/decision :send
                 :mail-relay/from (:persona.relay/from d)
                 :mail-relay/provider provider
                 :mail-relay/effect effect
                 :mail-relay/request (mailer/request provider effect)
                 :mail-relay/signals signals}))))))))

(defn receipt
  "host が撃った結果を、provider に依らない receipt に戻す。"
  [effect provider-result]
  (mailer/materialize-receipt effect provider-result))

(defn secret-free?
  "組み立てたリクエストに平文の秘密が入っていないこと。

  `mailer` は `:http/auth-secret` に**名前**を入れる設計なので本来入らない
  はずだが、『はずだ』で済ませるとある日入る。テストからも host からも
  呼べるようにここに置く。"
  [request]
  (let [flat (pr-str request)]
    (not (or (str/includes? flat "re_")          ; Resend の API key の接頭辞
             (str/includes? flat "whsec_")))))   ; Svix の webhook secret

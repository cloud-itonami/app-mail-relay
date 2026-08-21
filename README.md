# app-mail-relay

**`relay.itonami.cloud` の受信箱アカウント面。** エージェントが自分の受信箱を
持ち、相手ごとに別の顔で受け取り、要らなくなったら 1 本だけ止める —— その
「箱を開き、閉じ、鍵を回す」判断だけをここが持つ。

IO は無い。SMTP も Email Routing も kotobase への保管も鍵の生成も、すべて
host（Cloudflare Email Worker）側の機構。ここが返すのは値だけ。

## 何がどこにあるか

1 通ごとの振り分け —— この宛先は誰のものか、その差出人は想定内か、返信は
どの From で出すか —— は **`kotoba-lang/persona`** が既に持っている。ここで
作り直さない。境界はこう:

| | 何を決めるか |
|---|---|
| `kotoba-lang/persona` | **1 通**をどうするか（`relay/inbound` `relay/outbound`） |
| **`app-mail-relay`（ここ）** | **1 アカウント**をどうするか（開く・閉じる・鍵を回す・API 面） |
| host（Email Worker） | 実際に受ける・保管する・鍵を作る |
| `kotoba-lang/kotobase` | 本文の保管と検索（content-addressed） |

関連して既にあるもの: `kotoba-lang/mail`（可搬なメールモデル）、
`kotoba-lang/mailer`（送信 effect → provider request）、`org-ietf-smtp` /
`-imap` / `-pop3` / `-mime`（プロトコル本体）、`kotoba-lang/onetime`（コード）、
`kotoba-lang/word-id`（読み上げられる 4 語）。

## なぜ鍵ではなく root で認可するのか

2026-08-21、AgentMail 互換の受信箱を作り、返ってきた `account_key` を保管し
損ねた。鍵は一度しか返らないので、その箱は読めなくなった。そこまでは自業自得
だが、問題はその先で —— **解放する経路が無かった**。`DELETE` は 404、鍵が無い
ので認証もできない。死んだ箱が「1 origin につき 1 箱」の枠を占有したまま、
こちらからは何もできなくなった。

ここから 2 つ引いてある。

- **`close` と `rotate` は root（所有者の DID）で認可する。鍵は要らない。**
  鍵を失うことは起こる。起こったときに箱ごと死ぬのは、鍵とアカウントの
  identity を同一視した設計の欠陥であって、利用者の不注意ではない。
- **入場制限を課すなら、退場経路を同時に出す。** PoW も challenge も IP 制限
  も課してよいが、`close` の無い入場制限は、失敗した利用者を回復不能にする。

`test/mail_relay/account_test.cljc` の `losing-the-key-does-not-strand-the-inbox`
がこの事故の再演になっている。復旧設計を退行させると落ちることは実測済み。

## API

route 表は `mail_relay/api.cljc` に**データとして**1 つだけ持つ。Worker の
dispatch も公開ドキュメントもここから出す。別々に書くと、dispatch に足して
表に足し忘れた route は「live に見える dead code」になり、表にあって dispatch
に無い route は「動くと書いてあるのに 404」になる。どちらも出力から気づけない。

```
POST   /v1/inboxes                        :none  入場
GET    /v1/inboxes/{inbox}                :key
DELETE /v1/inboxes/{inbox}                :root  ← 解放経路
POST   /v1/inboxes/{inbox}/keys/rotate    :root  ← 鍵の回復
GET    /v1/inboxes/{inbox}/messages       :key
GET    /v1/inboxes/{inbox}/messages/{msg} :key   応答に CID を含める
GET    /v1/inboxes/{inbox}/threads        :key
POST   /v1/inboxes/{inbox}/messages       :key   送信（mailer 経由）
POST   /v1/inboxes/{inbox}/personas       :key   相手ごとの顔
DELETE /v1/personas/{address}             :key   その 1 本だけ止める
GET    /v1/personas/{address}/senders     :key   誰から来たか = 漏洩の帰責
POST   /v1/inboxes/{inbox}/webhooks       :key
```

`:novel` が付いた route は既存サービスに無い面で、なぜ要るかを `:basis` に
書いてある。宣伝ではなく、次の整理で消されないための理由。

## 使う

```clojure
(require '[mail-relay.account :as account])

(def r (:mail-relay/registry
        (account/open {} {:id "inbox_a" :root "did:key:z6Mk…"
                          :key-digest "sha256:…" :origin "203.0.113.7"
                          :now 1787000000000})))

;; 鍵を失っても、root で閉じられる / 回せる
(account/close r  {:id "inbox_a" :root "did:key:z6Mk…"})
(account/rotate r {:id "inbox_a" :root "did:key:z6Mk…" :new-key-digest "sha256:…"})
```

`key-digest` であって鍵そのものではない。判断だけの層なので平文の鍵は持たない。

## テスト

```bash
nbb --classpath src:test run-tests.cljs   # ClojureScript（Worker と同じ経路）
clojure -M:local:test                     # JVM
```

Worker は CLJS で動くので、JVM で通ることは証拠にならない。両方で回す。

## まだ無いもの

正直に書く。ここにあるのは判断の核だけで、**動くサービスはまだ無い**。

- Cloudflare Email Worker の `email` ハンドラ（受信 → persona.relay → 保管）
- kotobase への保管と CID の払い出し
- 鍵の生成と提示（host 側。ここは digest しか持たない）
- 入場の濫用対策（PoW / challenge）
- 送信経路（`mailer` の `:resend` / `:ses` / `:smtp` のどれかを選ぶ）
- 到達性（SPF / DKIM / DMARC・レピュテーション）—— 外部に売るなら**ここが開発の本体**

設計判断は superproject の ADR-2608211300。

(ns mail-relay.api
  "relay.itonami.cloud の HTTP 面を**データとして**持つ。

  Worker の dispatch も、公開ドキュメントも、この表 1 つから出す。別々に
  書くと必ずずれる —— dispatch に足して表に足し忘れた route は『live に
  見える dead code』になり、表にあって dispatch に無い route は『動くと
  書いてあるのに 404』になる。どちらも出力から気づけない。

  `:auth` の値の意味:

  | 値 | 誰が通れるか |
  |---|---|
  | `:key`  | アカウントの鍵 |
  | `:root` | 所有者の DID。**鍵を失っていても通る** |
  | `:none` | 認証なし（入場のみ） |

  `close` と `keys/rotate` が `:root` なのが、この API の要点。理由は
  `mail-relay.account` の docstring。"
  (:require [clojure.string :as str]))

(def schema "mail-relay.api.v1")

(def base-path "/v1")

(def routes
  "1 route = 1 map。`:novel` は AgentMail に無い面という印で、なぜ要るかを
  `:basis` に書く。宣伝ではなく、消されないための理由。"
  [{:method :post   :path "/v1/inboxes"                        :auth :none
    :op :inbox/open
    :basis "入場。PoW と bot challenge は host 側の機構"}

   {:method :get    :path "/v1/inboxes/{inbox}"                :auth :key
    :op :inbox/get}

   {:method :delete :path "/v1/inboxes/{inbox}"                :auth :root
    :op :inbox/close :novel true
    :basis "解放経路。これが無いと、鍵を失った箱が origin の枠を占有したまま誰も動かせない"}

   {:method :post   :path "/v1/inboxes/{inbox}/keys/rotate"    :auth :root
    :op :inbox/rotate :novel true
    :basis "鍵を失っても回復できる。現在の鍵で認可すると、回復が要る場面でだけ使えない"}

   {:method :get    :path "/v1/inboxes/{inbox}/messages"       :auth :key
    :op :message/list}

   {:method :get    :path "/v1/inboxes/{inbox}/messages/{msg}" :auth :key
    :op :message/get
    :basis "本文は kotobase の content-addressed block。応答に CID を含める"}

   {:method :get    :path "/v1/inboxes/{inbox}/threads"        :auth :key
    :op :thread/list}

   {:method :post   :path "/v1/inboxes/{inbox}/messages"       :auth :key
    :op :message/send
    :basis "mailer の :resend / :ses / :smtp のいずれかへ。Email Routing では送れない"}

   {:method :post   :path "/v1/inboxes/{inbox}/personas"       :auth :key
    :op :persona/issue :novel true
    :basis "相手ごとの顔。読み上げられる 4 語のアドレスを 1 相手に 1 本"}

   {:method :delete :path "/v1/personas/{address}"             :auth :key
    :op :persona/burn :novel true
    :basis "その 1 本だけ止める。本体も他の顔も生きたまま"}

   {:method :get    :path "/v1/personas/{address}/senders"     :auth :key
    :op :persona/senders :novel true
    :basis "誰から届いたか。想定外の差出人は、そのアドレスが漏れたか売られた証拠になりうる"}

   {:method :post   :path "/v1/inboxes/{inbox}/webhooks"       :auth :key
    :op :webhook/create}])

(defn- segments [path]
  (remove str/blank? (str/split path #"/")))

(defn- match-segment [pattern actual]
  (if (and (str/starts-with? pattern "{") (str/ends-with? pattern "}"))
    [true [(keyword (subs pattern 1 (dec (count pattern)))) actual]]
    [(= pattern actual) nil]))

(defn route-for
  "method と実パスから route を引く。見つからなければ nil。
  返る map には束縛した path 変数が `:params` で入る。"
  [method path]
  (let [actual (segments path)]
    (some (fn [r]
            (let [pat (segments (:path r))]
              (when (and (= method (:method r)) (= (count pat) (count actual)))
                (let [pairs (map match-segment pat actual)]
                  (when (every? first pairs)
                    (assoc r :params (into {} (keep second pairs))))))))
          routes)))

(defn novel-routes
  "AgentMail に無い面。ここが減ったら、この製品である理由が減っている。"
  []
  (filterv :novel routes))

(defn auth-required
  "その route を通すのに何が要るか。"
  [method path]
  (:auth (route-for method path)))

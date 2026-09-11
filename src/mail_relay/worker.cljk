(ns mail-relay.worker
  "relay.itonami.cloud の Cloudflare Worker。

  判断はここに無い。account / api / authz / inbound / send / store は全部
  純 `.cljc` で、この ns がやるのは『読む・引く・撃つ・書く』だけ。

  ## dispatch は route 表 1 つから出す

  `mail-relay.api/routes` を引いて、そこから op を得る。dispatch に足して
  表に足し忘れた route は『live に見える dead code』になり、表にあって
  dispatch に無い route は『動くと書いてあるのに 404』になる。どちらも
  出力から気づけないので、表を唯一の入口にして、`worker-test` が
  **表の全 route に handler がある**ことを主張する。

  ## 状態の置き場所

      KV RELAY_DIR
        address:<address>  {:inbox … :kind :persona|:base}   受信の逆引き
        origin:<origin>    account registry                  枠の勘定
        inbox:<id>         {:origin :directory :recipient :address :webhooks}
      R2 MAIL_BUCKET
        mail/<inbox>/<id>/…   封をした本体
        index/<inbox>/<id>    平文の index

  registry を origin ごとに置いてあるのは、`active-per-origin` の上限が
  origin ごとの問いだから。**正直に書くと、KV は結果整合なので上限は
  best-effort** —— 同一 origin から同時に 2 本開くと両方通りうる。厳密に
  するには R2 の条件付き put か DO が要る。濫用対策（PoW / challenge）を
  入れる段でそこを詰める。

  ## 鍵は一度しか返さない。だから root で閉じられる

  `open` と `rotate` が平文の鍵を返すのはその 1 回だけで、保管するのは
  digest。2026-08-21 に踏んだのはここで、鍵を失った箱が origin の枠を
  占有したまま誰も動かせなくなった。だから `close` と `rotate` は root の
  **署名**で通る（`mail-relay.root-auth`）—— 鍵が要らない。"
  (:require [cljs.reader :as reader]
            [kotoba.lang.text :as str]
            [mail-relay.account :as account]
            [mail-relay.api :as api]
            [mail-relay.authz :as authz]
            [mail-relay.inbound :as inbound]
            [mail-relay.resend :as resend]
            [mail-relay.root-auth :as root-auth]
            [mail-relay.seal :as seal]
            [mail-relay.send :as send]
            [mail-relay.store :as store]
            [persona.core :as persona]
            [word-id.core :as word-id]
            [word-id.english :as english]))

(def domain "relay.itonami.cloud")

(def mail-domain
  "メールアドレスのドメイン。API 面は `relay.itonami.cloud`、メール面は
  Resend で検証済みの `mail.itonami.cloud`（2026-09-05 実測: Resend domains API
  で verified、sending/receiving enabled）。inbox の base address と persona の
  From はここで組み立てられる。"
  (or (.-MAIL_DOMAIN js/process.env) "mail.itonami.cloud"))

;; ------------------------------------------------------------ 応答

(defn- json [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status :headers #js {"content-type" "application/json"}}))

(defn- error-name
  "keyword は **namespace ごと** 文字列にする。`name` で落とすと、
  `:mail-relay.send/address-burned` と `:mail-relay.inbound/address-burned` が
  同じ \"address-burned\" になり、client はどの層が断ったか言えなくなる。"
  [reason]
  (if (keyword? reason) (subs (str reason) 1) (str reason)))

(defn- fail [status reason & {:as attrs}]
  (json status (merge {:ok false :error (error-name reason)} attrs)))

;; ------------------------------------------------------------ バイト

(defn- concat-chunks [chunks]
  (let [total (reduce + (map #(.-length ^js %) chunks))
        out (js/Uint8Array. total)]
    (reduce (fn [off ^js c] (.set out c off) (+ off (.-length c))) 0 chunks)
    out))

(defn read-stream
  "ReadableStream -> Promise<Uint8Array>。max-bytes を渡すと、上限を越えた
  時点でストリーミング中に落とす —— 信用できない応答に Worker の 128 MB を
  食わせない。"
  ([stream] (read-stream stream nil))
  ([^js stream max-bytes]
   (let [reader (.getReader stream)
         chunks #js []
         total (atom 0)]
     (letfn [(pump []
               (-> (.read reader)
                   (.then (fn [^js r]
                            (if (.-done r)
                              (concat-chunks (vec (array-seq chunks)))
                              (let [chunk (js/Uint8Array. (.-value r))
                                    n (swap! total + (.-length chunk))]
                                (if (and max-bytes (> n max-bytes))
                                  (-> (.cancel reader "message too large")
                                      (.then #(throw (js/Error. "message exceeds size limit"))))
                                  (do (.push chunks chunk) (pump)))))))))]
       (pump)))))

(defn bytes->binary-string
  "Uint8Array -> 1 バイト 1 文字。

  `(TextDecoder. \"latin1\")` を使わないのは意図。WHATWG の符号化標準では
  `latin1` は **windows-1252 のラベル**で、0x80–0x9F を印字可能文字に
  書き換える。base64 や quoted-printable の中でそれが起きると、復号に
  失敗するまで見えない損傷になる。この写像は 256 値すべてで正確。"
  [^js u8]
  (let [n (.-length u8)]
    (loop [i 0 acc ""]
      (if (>= i n)
        acc
        (recur (+ i 0x8000)
               (str acc (.apply js/String.fromCharCode nil
                                (.subarray u8 i (min n (+ i 0x8000))))))))))

;; ------------------------------------------------------------ 鍵と乱数

(defn- b64url [^js u8]
  (-> (.apply js/String.fromCharCode nil u8)
      js/btoa
      (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))

(defn- random-bytes [n] (js/crypto.getRandomValues (js/Uint8Array. n)))

(defn- sha256-hex [s]
  (-> (js/crypto.subtle.digest "SHA-256" (.encode (js/TextEncoder.) s))
      (.then (fn [ab]
               (let [u8 (js/Uint8Array. ab)]
                 (apply str (map #(.padStart (.toString (aget u8 %) 16) 2 "0")
                                 (range (.-length u8)))))))))

(defn- key-digest
  "平文の鍵 -> `sha256:<hex>`。**保管するのはこちらだけ。**"
  [k]
  (.then (sha256-hex k) #(str "sha256:" %)))

(defn- now-seconds [] (js/Math.floor (/ (.now js/Date) 1000)))
(defn- iso-now [] (.toISOString (js/Date.)))

;; ------------------------------------------------------------ 状態

(defn- kv [^js env] (.-RELAY_DIR env))

(defn- kv-get [^js env k]
  (-> (.get (kv env) k) (.then #(when % (reader/read-string %)))))

(defn- kv-put! [^js env k v] (.put (kv env) k (pr-str v)))

(defn- load-origin-registry [env origin]
  (.then (kv-get env (str "origin:" origin)) #(or % {})))

(defn- load-inbox [env id] (kv-get env (str "inbox:" id)))

(defn- resolve-address [env address]
  (kv-get env (str "address:" address)))

(defn- registry-for-inbox
  "inbox の状態から、その account が入っている registry を引く。"
  [env inbox-state]
  (load-origin-registry env (:origin inbox-state)))

;; ------------------------------------------------------------ 受信

(defn- deliver-webhooks!
  "index を webhook に投げる。**本文は入らない** —— 平文で持っていない。
  待たない: 1 つの購読先が落ちていても保管は済んでいる。"
  [^js env inbox-state index]
  (doseq [url (:webhooks inbox-state)]
    (-> (js/fetch url #js {:method "POST"
                           :headers #js {"content-type" "application/json"}
                           :body (js/JSON.stringify (clj->js index))})
        (.catch (fn [e] (js/console.error "webhook failed:" url (.-message e)))))))

(defn- pin! [^js env cid]
  (let [base (.-KOTOBASE_PIN_URL env)]
    (if (or (str/blank? (str base)) (nil? cid))
      (js/Promise.resolve false)
      (-> (js/fetch base #js {:method "POST"
                              :headers #js {"content-type" "application/json"
                                            "authorization" (str "Bearer " (.-KOTOBASE_TOKEN env))}
                              :body (js/JSON.stringify #js {:cid cid :name "mail"})})
          (.then #(.-ok ^js %))
          (.catch (fn [_] false))))))

(defn- store-objects! [^js env {:keys [keys raw parsed index] :as result}]
  (let [bucket (.-MAIL_BUCKET env)
        chunk-key (fn [base i] (str base "/" i))]
    (js/Promise.all
     (clj->js
      (concat
       (map-indexed (fn [i c] (.put bucket (chunk-key (:raw keys) i) c)) (:chunks raw))
       (map-indexed (fn [i c] (.put bucket (chunk-key (:parsed keys) i) c)) (:chunks parsed))
       [(.put bucket (:envelope keys) (seal/envelope-json result))
        (.put bucket (:index keys) (pr-str index))])))))

(defn- remember-sender!
  "この 1 通について覚えることを書き戻す。判断（`inbound/route`）とは別の
  段にしてあるのは、判断が状態を書き換えないため。"
  [^js env inbox inbox-state {:keys [address from kind]}]
  (kv-put! env (str "inbox:" inbox)
           (update inbox-state :directory inbound/note
                   {:address address :sender from :kind kind})))

(defn- seal-and-store!
  "`:store` に決まった 1 通を、封じて・pin して・書いて・覚える。"
  [^js env inbox-state {:keys [inbox kind address from raw received-at]} decision]
  (-> (seal/process
       {:raw raw :from from :to address :inbox inbox
        :persona (get-in decision [:mail-relay/persona :persona/address])
        :signals (mapv :persona.relay/signal (:mail-relay/signals decision))
        :received-at received-at
        :recipient (:recipient inbox-state)})
      (.then (fn [result]
               (-> (pin! env (get-in result [:index :mail/cid]))
                   (.then (fn [pinned]
                            (assoc result :index (store/with-pin (:index result) pinned)))))))
      (.then (fn [result] (.then (store-objects! env result) (fn [_] result))))
      (.then (fn [result]
               (js/console.log (inbound/summary-line result))
               ;; webhook は待たない。1 つ落ちていても保管は済んでいる。
               (deliver-webhooks! env inbox-state (:index result))
               (-> (remember-sender! env inbox inbox-state
                                     {:address address :from from :kind kind})
                   (.then (fn [_] {:stored (get-in result [:keys :index]) :inbox inbox})))))))

(defn- route-and-store! [^js env accept {:keys [from] :as msg}]
  (-> (load-inbox env (:inbox accept))
      (.then
       (fn [inbox-state]
         (if-not inbox-state
           {:rejected :mail-relay.inbound/unknown-address}
           (-> (registry-for-inbox env inbox-state)
               (.then
                (fn [registry]
                  (let [d (inbound/route {:registry registry
                                          :directory (:directory inbox-state)
                                          :inbox (:inbox accept)
                                          :kind (:kind accept)
                                          :address (:address accept)
                                          :sender from})]
                    (if (not= :store (:mail-relay/decision d))
                      {:rejected (:mail-relay/reason d)}
                      (seal-and-store! env inbox-state (merge msg accept) d))))))))))) 

(defn save-raw!
  "既に受け取った RFC 822 の 1 通を、**どの transport から来ても同じ**
  admission・振り分け・封・保管の経路に通す。

  -> `{:stored key :inbox id}` / `{:rejected reason}`

  Cloudflare Email Routing は rejected を SMTP の reject に、Resend は
  『受理したが無視した webhook』に変えてよいが、この境界を迂回はできない。"
  [^js env {:keys [to from raw received-at size]}]
  (let [address (inbound/normalize-address to)]
    (-> (resolve-address env address)
        (.then
         (fn [hit]
           (let [{:keys [:mail-relay/accept :mail-relay/reject]}
                 (inbound/admit {:to to :size (or size (count raw))
                                 :resolve (constantly hit)})]
             (if reject
               {:rejected reject}
               (route-and-store! env accept {:from from :raw raw
                                             :received-at (or received-at (iso-now))}))))))))

(defn handle-email
  "Cloudflare Email Routing の `email` ハンドラ。

  受けられない 1 通は **reject** であって、黙って捨てない。reject は
  差出人にバウンスするので、差出人は届かなかったことを知る。"
  [^js message ^js env]
  (-> (read-stream (.-raw message) store/max-message-bytes)
      (.then bytes->binary-string)
      (.then (fn [raw]
               (save-raw! env {:raw raw :from (.-from message) :to (.-to message)
                               :size (.-rawSize message) :received-at (iso-now)})))
      (.then (fn [{:keys [rejected] :as result}]
               (when rejected (.setReject message (str domain ": " (name rejected))))
               result))
      (.catch (fn [e]
                (js/console.error "inbound failed:" (.-message e))
                (.setReject message (str domain ": temporary processing failure"))
                {:rejected :exception}))))

;; ------------------------------------------------------------ handler

(defn- body-of [^js request]
  (-> (.text request)
      (.then #(if (str/blank? %) {} (js->clj (js/JSON.parse %) :keywordize-keys true)))
      (.catch (fn [_] ::bad-json))))

(defn- op-inbox-open [^js env body]
  (let [root (:root body)
        pub (:pub body)
        origin (or (:origin body) "unknown")
        id (str "inbox_" (b64url (random-bytes 9)))
        raw-key (str "rk_" (b64url (random-bytes 32)))
        address (str (:word-id/text (word-id/mint english/vocabulary
                                                       (vec (array-seq (random-bytes 24)))))
                     "@" mail-domain)]
    (cond
      (str/blank? (str root))
      (js/Promise.resolve
       (fail 400 :missing-root
             {:basis "root が無いと、鍵を失ったときに閉じることも回すこともできなくなる"}))

      (str/blank? (str pub))
      (js/Promise.resolve
       (fail 400 :missing-public-key
             {:basis (str "封をする相手の X25519 公開鍵が要る。渡されなければサービスが鍵を"
                          "持つことになり、それはこの設計が引き受けないもの")}))

      :else
      (-> (key-digest raw-key)
          (.then
           (fn [digest]
             (-> (load-origin-registry env origin)
                 (.then
                  (fn [registry]
                    (let [d (account/open registry {:id id :root root :key-digest digest
                                                    :origin origin :now (.now js/Date)})]
                      (if (not= :open (:mail-relay/decision d))
                        (fail 409 (:mail-relay/reason d)
                              {:held (:mail-relay/held d) :basis (:mail-relay/basis d)})
                        (-> (js/Promise.all
                             #js [(kv-put! env (str "origin:" origin) (:mail-relay/registry d))
                                  (kv-put! env (str "inbox:" id)
                                           {:origin origin
                                            :address address
                                            :directory (persona/directory)
                                            :recipient {:id root :pub pub :custody :self}
                                            :webhooks []})
                                  (kv-put! env (str "address:" address) {:inbox id :kind :base})])
                            (.then (fn [_]
                                     (json 201 {:ok true :inbox id :address address
                                                :key raw-key
                                                :key_digest digest
                                                :note (str "この鍵はここでしか返らない。"
                                                           "失っても close と rotate は root の署名で通る")})))))))))))))))

(defn- op-inbox-get [inbox-state registry inbox]
  (let [a (account/account-at registry inbox)]
    (json 200 {:ok true :inbox inbox :address (:address inbox-state)
               :state (name (:mail-relay.account/state a))
               :key_digest (:mail-relay.account/key-digest a)
               :opened_at (:mail-relay.account/opened-at a)
               :personas (mapv :persona/address (persona/live (:directory inbox-state)
                                                              (:mail-relay.account/root a)))
               :webhooks (vec (:webhooks inbox-state))})))

(defn- op-inbox-close [^js env inbox-state registry inbox did]
  (let [d (account/close registry {:id inbox :root did})]
    (if (not= :close (:mail-relay/decision d))
      (js/Promise.resolve (fail 409 (:mail-relay/reason d)))
      (-> (kv-put! env (str "origin:" (:origin inbox-state)) (:mail-relay/registry d))
          (.then (fn [_] (json 200 {:ok true :inbox inbox :state "closed"
                                    :note "origin の枠は解放された"})))))))

(defn- op-inbox-rotate [^js env inbox-state registry inbox did]
  (let [raw-key (str "rk_" (b64url (random-bytes 32)))]
    (-> (key-digest raw-key)
        (.then (fn [digest]
                 (let [d (account/rotate registry {:id inbox :root did :new-key-digest digest})]
                   (if (not= :rotate (:mail-relay/decision d))
                     (fail 409 (:mail-relay/reason d) {:basis (:mail-relay/basis d)})
                     (-> (kv-put! env (str "origin:" (:origin inbox-state)) (:mail-relay/registry d))
                         (.then (fn [_]
                                  (json 200 {:ok true :inbox inbox :key raw-key
                                             :key_digest digest
                                             :superseded (:mail-relay/superseded d)}))))))))))) 

(defn- op-message-list [^js env inbox]
  (-> (.list (.-MAIL_BUCKET env) #js {:prefix (str "index/" inbox "/") :limit 100})
      (.then (fn [^js listing]
               (js/Promise.all
                (clj->js (map (fn [^js o] (.then (.get (.-MAIL_BUCKET env) (.-key o))
                                                 #(.text ^js %)))
                              (array-seq (.-objects listing)))))))
      (.then (fn [texts]
               (json 200 {:ok true
                          :messages (mapv #(store/index->wire (reader/read-string %))
                                          (array-seq texts))})))))

(defn- b64 [^js u8] (js/btoa (bytes->binary-string u8)))

(defn- get-chunks [^js bucket base n]
  (js/Promise.all
   (clj->js (map (fn [i] (-> (.get bucket (str base "/" i))
                             (.then #(.arrayBuffer ^js %))
                             (.then #(b64 (js/Uint8Array. %)))))
                 (range n)))))

(defn- op-message-get [^js env inbox msg]
  (let [bucket (.-MAIL_BUCKET env)
        ks (store/object-keys inbox msg)]
    (-> (.get bucket (:index ks))
        (.then (fn [^js o]
                 (if-not o
                   (fail 404 :unknown-message)
                   (-> (.text o)
                       (.then (fn [index-edn]
                                (-> (.get bucket (:envelope ks))
                                    (.then #(.text ^js %))
                                    (.then
                                     (fn [env-json]
                                       (let [envelopes (seal/parse-envelope-json env-json)
                                             n-raw (get-in envelopes [:raw :envelope/chunks])
                                             n-parsed (get-in envelopes [:parsed :envelope/chunks])]
                                         (-> (js/Promise.all
                                              #js [(get-chunks bucket (:raw ks) n-raw)
                                                   (get-chunks bucket (:parsed ks) n-parsed)])
                                            (.then
                                             (fn [[raw-chunks parsed-chunks]]
                                               (json 200
                                                     {:ok true
                                                      :index (store/index->wire
                                                              (reader/read-string index-edn))
                                                      :envelope (js->clj (js/JSON.parse env-json))
                                                      :raw (vec (array-seq raw-chunks))
                                                      :parsed (vec (array-seq parsed-chunks))
                                                      :note (str "本文は暗号文。開けるのは open で渡した "
                                                                 "X25519 公開鍵に対応する私有鍵を持つ側だけ")})))))))))))))))))

(defn- op-thread-list [inbox]
  ;; 正直に 501 を返す。スレッドは References / In-Reply-To から組むもので、
  ;; それはヘッダごと封の中にある —— サービスは読めない。読めるようにする
  ;; には平文で持つしかなく、それは封をした意味を消す。
  ;; 組むのは鍵を持つ client 側。
  (fail 501 :threads-are-client-side
        {:basis (str "スレッドは References / In-Reply-To から組む。それは封の中にあり、"
                     "サービスが読めるようにすることは平文で持つことと同じ。"
                     "messages を開いた client が組む")
         :inbox inbox}))

(defn- op-message-send [^js env inbox-state registry inbox key-digest body]
  (let [root (get-in registry [:mail-relay/accounts inbox :mail-relay.account/root])
        d (send/plan {:registry registry :directory (:directory inbox-state)
                      :inbox inbox :key-digest key-digest :root root
                      :address (:address body)
                      :to (:to body) :cc (:cc body)
                      :subject (:subject body) :text (:text body) :html (:html body)
                      :reply-to (:replyTo body) :from (:from body)})]
    (if (not= :send (:mail-relay/decision d))
      (js/Promise.resolve
       (fail 400 (:mail-relay/reason d)
             {:basis (:mail-relay/basis d) :errors (:mail-relay/errors d)
              :signals (mapv :persona.relay/signal (:mail-relay/signals d))}))
      (-> (resend/send! env (:mail-relay/request d))
          (.then (fn [result]
                   (if (= :sent (:status result))
                     (json 202 {:ok true :from (:mail-relay/from d)
                                :provider (name (:mail-relay/provider d))
                                :message_id (:message-id result)
                                :signals (mapv :persona.relay/signal (:mail-relay/signals d))})
                     (fail 502 :send-failed {:provider (name (:mail-relay/provider d))
                                             :detail (:error result)}))))))))

(defn- op-persona-issue [^js env inbox-state registry inbox body]
  (let [root (get-in registry [:mail-relay/accounts inbox :mail-relay.account/root])
        r (persona/issue (:directory inbox-state)
                         {:root root :party (:party body) :domain mail-domain
                          :entropy (vec (array-seq (random-bytes 24)))
                          :vocabulary english/vocabulary
                          :cap (:cap body)
                          :label (:label body)
                          :now (iso-now)})]
    (if-let [issues (:persona/issues r)]
      (js/Promise.resolve (fail 400 (:persona/issue (first issues))
                                {:basis (:persona/basis (first issues))}))
      (let [address (:persona/address (:persona/persona r))]
        (-> (js/Promise.all
             #js [(kv-put! env (str "inbox:" inbox) (assoc inbox-state :directory (:persona/directory r)))
                  (kv-put! env (str "address:" address) {:inbox inbox :kind :persona})])
            (.then (fn [_]
                     (json 201 {:ok true :address address
                                :party (:persona/party (:persona/persona r))
                                :existing (mapv :persona/address (:persona/existing r))}))))))))

(defn- op-persona-burn [^js env inbox-state inbox address]
  (let [r (persona/burn (:directory inbox-state) address (iso-now))]
    (if-let [issues (:persona/issues r)]
      (js/Promise.resolve (fail 409 (:persona/issue (first issues))
                                {:basis (:persona/basis (first issues))}))
      (-> (kv-put! env (str "inbox:" inbox) (assoc inbox-state :directory (:persona/directory r)))
          (.then (fn [_] (json 200 {:ok true :address address :state "burned"
                                    :note "この 1 本だけ止まった。箱も他の顔も生きている"})))))))

(defn- op-persona-senders [inbox-state address]
  (let [p (persona/persona-at (:directory inbox-state) address)]
    (if-not p
      (fail 404 :unknown-address)
      (json 200 {:ok true :address address
                 :party (:persona/party p)
                 :observed_senders (vec (sort (:persona/observed-senders p)))
                 :basis (str "この顔は " (:persona/party p) " のために作った。"
                             "それ以外の差出人は、漏れたか売られたかの証拠になりうる")}))))

(defn- op-webhook-create [^js env inbox-state inbox body]
  (let [url (:url body)]
    (if (or (str/blank? (str url)) (not (str/starts-with? (str url) "https://")))
      (js/Promise.resolve (fail 400 :invalid-webhook-url))
      (let [next-state (update inbox-state :webhooks (fnil conj []) url)]
        (-> (kv-put! env (str "inbox:" inbox) next-state)
            (.then (fn [_] (json 201 {:ok true :webhooks (:webhooks next-state)
                                      :note "届くのは index だけ。本文は平文で持っていない"}))))))))

;; ------------------------------------------------------------ dispatch

(defn- inbox-id-for
  "route の params から inbox id を出す。`{address}` の route は逆引きを引く。"
  [^js env {:keys [params]}]
  (if-let [inbox (:inbox params)]
    (js/Promise.resolve inbox)
    (if-let [address (:address params)]
      ;; keyword を .then に渡してはいけない —— CLJS の keyword は JS の
      ;; 関数ではないので、Promise は「関数でないもの」を無視して値を
      ;; そのまま素通しする。map がそのまま inbox id になり、404 になる。
      (.then (resolve-address env (inbound/normalize-address address))
             (fn [hit] (:inbox hit)))
      (js/Promise.resolve nil))))

(def ops
  "op -> handler。**表と 1 対 1**。

  `case` ではなく map にしてあるのは、`worker-test` が
  『`api/routes` の op 集合と、この map の鍵集合が等しい』ことを
  主張できるようにするため。`case` は中身を数えられないので、
  dispatch に足して表に足し忘れた route を出力から気づけない。"
  {:inbox/open      (fn [{:keys [env body]}] (op-inbox-open env body))
   :inbox/get       (fn [{:keys [inbox-state registry inbox]}]
                      (js/Promise.resolve (op-inbox-get inbox-state registry inbox)))
   :inbox/close     (fn [{:keys [env inbox-state registry inbox did]}]
                      (op-inbox-close env inbox-state registry inbox did))
   :inbox/rotate    (fn [{:keys [env inbox-state registry inbox did]}]
                      (op-inbox-rotate env inbox-state registry inbox did))
   :message/list    (fn [{:keys [env inbox]}] (op-message-list env inbox))
   :message/get     (fn [{:keys [env inbox params]}] (op-message-get env inbox (:msg params)))
   :thread/list     (fn [{:keys [inbox]}] (js/Promise.resolve (op-thread-list inbox)))
   :message/send    (fn [{:keys [env inbox-state registry inbox key-digest body]}]
                      (op-message-send env inbox-state registry inbox key-digest body))
   :persona/issue   (fn [{:keys [env inbox-state registry inbox body]}]
                      (op-persona-issue env inbox-state registry inbox body))
   :persona/burn    (fn [{:keys [env inbox-state inbox params]}]
                      (op-persona-burn env inbox-state inbox
                                       (inbound/normalize-address (:address params))))
   :persona/senders (fn [{:keys [inbox-state params]}]
                      (js/Promise.resolve
                       (op-persona-senders inbox-state
                                           (inbound/normalize-address (:address params)))))
   :webhook/create  (fn [{:keys [env inbox-state inbox body]}]
                      (op-webhook-create env inbox-state inbox body))})

(defn- run-op [ctx]
  (if-let [f (get ops (get-in ctx [:route :op]))]
    (f ctx)
    (js/Promise.resolve (fail 500 :no-handler-for-op {:op (str (get-in ctx [:route :op]))}))))

(defn- headers-map [^js request]
  (into {} (map (fn [pair] [(str/lower (aget pair 0)) (aget pair 1)])
                (array-seq (js/Array.from (.entries (.-headers request)))))))

(defn- ctx-for [env route body inbox inbox-state registry extra]
  (merge {:env env :route route :params (:params route) :body body
          :inbox-state inbox-state :registry registry :inbox inbox}
         extra))

(defn- run-with-root
  "署名を検証し、通れば op へ。**通らなければ 403**。

  検証が済んでから `account/close` / `rotate` が『その DID がこの箱の
  所有者か』を見る —— 署名の正しさと所有の正しさは別の問い。"
  [env route body inbox inbox-state registry check]
  (-> (root-auth/verify (:mail-relay.authz/did check)
                        (:mail-relay.authz/signature check)
                        (:mail-relay.authz/message check))
      (.then (fn [ok?]
               (if-not ok?
                 (fail 403 :mail-relay.authz/bad-signature)
                 (run-op (ctx-for env route body inbox inbox-state registry
                                  {:did (:mail-relay.authz/did check)})))))))

(defn- run-with-key
  "提示された鍵を digest して account に照合する。**平文の鍵は保管しない**
  ので、突き合わせるのは digest 同士。"
  [env route body inbox inbox-state registry check]
  (-> (key-digest (:mail-relay.authz/presented-key check))
      (.then (fn [digest]
               (let [a (account/authorize registry {:id inbox :key-digest digest})]
                 (if (not= :allow (:mail-relay/decision a))
                   (fail 403 (:mail-relay/reason a))
                   (run-op (ctx-for env route body inbox inbox-state registry
                                    {:key-digest digest}))))))))

(defn- run-authenticated [env route body check]
  (-> (inbox-id-for env route)
      (.then
       (fn [inbox]
         (if-not inbox
           (fail 404 :unknown-inbox)
           (-> (load-inbox env inbox)
               (.then
                (fn [inbox-state]
                  (if-not inbox-state
                    (fail 404 :unknown-inbox)
                    (-> (registry-for-inbox env inbox-state)
                        (.then
                         (fn [registry]
                           (if (= :root (:mail-relay.authz/check check))
                             (run-with-root env route body inbox inbox-state registry check)
                             (run-with-key env route body inbox inbox-state registry check)))))))))))))) 

(defn- authorise-and-run [^js env ^js request route body]
  (let [check (authz/require-for {:auth (:auth route)
                                  :method (:method route)
                                  :path (.-pathname (js/URL. (.-url request)))
                                  :headers (headers-map request)
                                  :now-seconds (now-seconds)})]
    (case (:mail-relay.authz/check check)
      :reject
      (js/Promise.resolve
       (fail (get authz/status-for-reason (:mail-relay.authz/reason check) 401)
             (:mail-relay.authz/reason check)
             {:basis (:mail-relay/basis check)}))

      :none (run-op {:env env :route route :body body})

      (run-authenticated env route body check))))

(defn handle-fetch [^js request ^js env]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)
        method (keyword (str/lower (.-method request)))]
    (cond
      (= resend/webhook-path path)
      (resend/handle-webhook request env {:save-raw! save-raw!
                                          :read-stream read-stream
                                          :bytes->binary-string bytes->binary-string})

      (and (= :get method) (= "/v1/routes" path))
      ;; 公開ドキュメントも表から出す。dispatch とずれた瞬間に見える。
      (js/Promise.resolve (json 200 {:ok true :schema api/schema :routes api/routes}))

      :else
      (if-let [route (api/route-for method path)]
        (-> (body-of request)
            (.then (fn [body]
                     (if (= ::bad-json body)
                       (fail 400 :invalid-json)
                       (authorise-and-run env request route body))))
            (.catch (fn [e]
                      (js/console.error "request failed:" (.-message e))
                      (fail 500 :internal-error))))
        (js/Promise.resolve
         (fail 404 :no-such-route
               {:basis "route 表 (`GET /v1/routes`) に無いものは存在しない"}))))))

(def handler
  #js {:email (fn [^js message ^js env _ctx] (handle-email message env))
       :fetch (fn [^js request ^js env _ctx] (handle-fetch request env))})

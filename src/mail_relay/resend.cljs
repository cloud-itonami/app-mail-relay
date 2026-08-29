(ns mail-relay.resend
  "Resend の両側 —— 受信の webhook と、送信の実行。

  ここが『resend backed』の実体。判断は全部 `mail-relay.inbound` と
  `mail-relay.send`（純 `.cljc`）にあり、この ns がやるのは署名の検証と
  HTTP を撃つことだけ。

  ## 受信側は net-kotobase の mail-worker からの移植

  `verify-webhook` と R2 lease による冪等化は
  `net-kotobase/control-plane/mail-worker` の `kotobase-mail.resend` を
  写したもの。**写しであることを書いておくのは、次に直す人が 2 箇所ある
  ことを知るため。** 正しくは共有ライブラリ（`org-svix` 相当）に括り出す
  べきで、2 つ目の写しが生まれる前にそうする。

  署名は**受け取ったバイト列そのもの**に対して検証する。JSON を parse して
  再直列化したものに対して検証すると、鍵の順序や空白が変わった瞬間に
  検証が意味を失う。

  ## 送信側

  `mail-relay.send/plan` が返した provider request をそのまま撃つ。
  リクエストは `:http/auth-secret :resend-api-key` という**名前**しか
  持たないので、鍵を差すのはこの ns —— 判断の層に平文の鍵は一度も入らない。"
  (:require [clojure.string :as str]))

(def webhook-path "/webhooks/resend")
(def max-clock-skew-seconds 300)
(def lease-seconds 600)
(def max-message-bytes (* 25 1024 1024))

(defn- json-response [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status :headers #js {"content-type" "application/json"}}))

(defn- text-bytes [s] (.encode (js/TextEncoder.) s))

(defn- b64-bytes [s]
  (let [raw (js/atob s)
        out (js/Uint8Array. (.-length raw))]
    (dotimes [i (.-length raw)] (aset out i (.charCodeAt raw i)))
    out))

(defn- same-bytes? [^js a ^js b]
  (if (not= (.-length a) (.-length b))
    false
    (if-let [timing-safe (.-timingSafeEqual js/crypto.subtle)]
      (.call timing-safe js/crypto.subtle a b)
      ;; nbb の Node Web Crypto には Cloudflare の拡張が無い。本番は上の枝を
      ;; 通る。ここは純粋な契約をワーカー外でも走らせるための固定仕事の代替。
      (zero? (reduce bit-or 0
                     (map #(bit-xor (aget a %) (aget b %))
                          (range (.-length a))))))))

(defn- signatures [header]
  (->> (.split (or header "") " ")
       (keep (fn [part]
               (let [[version signature] (.split part "," 2)]
                 (when (and (= version "v1") signature) signature))))))

(defn verify-webhook
  "-> Promise<boolean>。時刻の鮮度と、宣言された v1 署名の**いずれか**を、
  body を parse せずに検証する。"
  [secret id timestamp signature body now-seconds]
  (let [ts (js/Number timestamp)
        fresh? (and (js/Number.isFinite ts)
                    (<= (js/Math.abs (- now-seconds ts)) max-clock-skew-seconds))
        encoded-secret (when (and (string? secret) (.startsWith secret "whsec_"))
                         (.slice secret 6))]
    (if-not (and fresh? encoded-secret (seq id) (seq (signatures signature)))
      (js/Promise.resolve false)
      (-> (js/crypto.subtle.importKey "raw" (b64-bytes encoded-secret)
                                      #js {:name "HMAC" :hash "SHA-256"}
                                      false #js ["sign"])
          (.then #(js/crypto.subtle.sign "HMAC" % (text-bytes (str id "." timestamp "." body))))
          (.then (fn [mac]
                   (let [expected (js/Uint8Array. mac)]
                     (boolean
                      (some (fn [candidate]
                              (try (same-bytes? expected (b64-bytes candidate))
                                   (catch :default _ false)))
                            (signatures signature))))))
          (.catch (fn [_] false))))))

;; ------------------------------------------------------------------ env

(defn- env-fetch [^js env] (or (.-RESEND_FETCH env) js/fetch))
(defn- api-base [^js env] (or (.-RESEND_API_BASE env) "https://api.resend.com"))

(defn receiving-domains
  "webhook は Resend アカウント全体に来る。**閉じた集合を既定にする** ——
  開いておくと、同じアカウントの別ドメイン宛のメールをこの箱が取り込む。"
  [^js env]
  (if-let [configured (.-RESEND_RECEIVING_DOMAINS env)]
    (->> (.split configured ",") (map #(.toLowerCase (.trim %))) (remove empty?) set)
    #{"relay.itonami.cloud"}))

(defn- recipient-domain [address]
  (some-> address (.split "@") last .toLowerCase))

(defn- allowed-recipient? [^js env address]
  (contains? (receiving-domains env) (recipient-domain address)))

;; -------------------------------------------------------------- 送信

(defn send!
  "`mail-relay.send/plan` が返した `:resend` の provider request を撃つ。

  -> Promise<{:provider :resend :status :sent|:failed :message-id …}>

  返す形は `mail-relay.send/receipt` がそのまま食える形。ここで receipt に
  しないのは、receipt を作るのが判断の層の仕事だから。"
  [^js env request]
  (let [key (.-RESEND_API_KEY env)]
    (cond
      (not= :resend (:mailer.request/provider request))
      (js/Promise.resolve {:provider (:mailer.request/provider request)
                           :status :failed
                           :error "this host only executes :resend requests"})

      (str/blank? (str key))
      (js/Promise.resolve {:provider :resend :status :failed
                           :error "RESEND_API_KEY is not bound"})

      :else
      (-> ((env-fetch env)
           (:http/url request)
           #js {:method "POST"
                :headers #js {"authorization" (str "Bearer " key)
                              "content-type" "application/json"}
                :body (js/JSON.stringify (clj->js (:http/json request)))})
          (.then (fn [^js r]
                   (-> (.json r)
                       (.then (fn [^js body]
                                (if (.-ok r)
                                  {:provider :resend :status :sent
                                   :message-id (.-id body)}
                                  {:provider :resend :status :failed
                                   :error (or (some-> body .-message) (str "HTTP " (.-status r)))}))))))
          (.catch (fn [e] {:provider :resend :status :failed :error (.-message e)}))))))

;; -------------------------------------------------------------- 受信

(defn- fetch-received! [^js env email-id]
  (.then ((env-fetch env)
          (str (api-base env) "/emails/receiving/" (js/encodeURIComponent email-id))
          #js {:headers #js {"authorization" (str "Bearer " (.-RESEND_API_KEY env))}})
         (fn [^js r]
           (if (.-ok r) (.json r)
               (throw (js/Error. (str "Resend receiving API HTTP " (.-status r))))))))

(defn- lease-key [email-id] (str "events/resend/" email-id))

(defn- inspect-lease! [^js bucket k started now ^js existing]
  (if-not existing
    (js/Promise.resolve {:retry true})
    (-> (.json existing)
        (.then (fn [^js record]
                 (cond
                   (= "done" (.-state record)) {:duplicate true}
                   (< (- now (or (.-at record) 0)) lease-seconds) {:retry true}
                   :else
                   (-> (.put bucket k started #js {:onlyIf #js {:etagMatches (.-etag existing)}})
                       (.then #(if % {:acquired true :key k :etag (.-etag ^js %)}
                                   {:retry true})))))))))

(defn- acquire-lease!
  "R2 の条件付き put が再送と並行の境界。完了した event は冪等に 200。
  生きた lease は Resend に retry させ、古い lease は etag で守った put で
  引き継ぐ —— 落ちた実行がメールを失わないように。"
  [^js bucket email-id now]
  (let [k (lease-key email-id)
        started (js/JSON.stringify #js {:state "processing" :at now})]
    (-> (.put bucket k started #js {:onlyIf #js {:etagDoesNotMatch "*"}})
        (.then (fn [created]
                 (if created
                   {:acquired true :key k :etag (.-etag ^js created)}
                   (-> (.get bucket k) (.then #(inspect-lease! bucket k started now %)))))))))

(defn- mark-done! [^js bucket key etag now]
  (.put bucket key (js/JSON.stringify #js {:state "done" :at now})
        #js {:onlyIf #js {:etagMatches etag}}))

(defn- persist-mail!
  [^js env ^js mail key etag {:keys [save-raw! read-stream bytes->binary-string]} now]
  (let [^js raw-info (.-raw mail)
        url (when raw-info (.-download_url raw-info))
        bucket (.-MAIL_BUCKET env)]
    (when-not url (throw (js/Error. "Resend received email has no raw download URL")))
    (-> ((env-fetch env) url)
        (.then (fn [^js response]
                 (when-not (.-ok response)
                   (throw (js/Error. (str "Resend raw download HTTP " (.-status response)))))
                 (read-stream (.-body response) max-message-bytes)))
        (.then (fn [^js u8]
                 (let [raw (bytes->binary-string u8)
                       tos (->> (array-seq (.-to mail)) (filter #(allowed-recipient? env %)) vec)]
                   (-> (js/Promise.all
                        (clj->js (map (fn [to]
                                        (save-raw! env {:to to :from (.-from mail) :raw raw
                                                        :size (.-length u8)
                                                        :received-at (.-created_at mail)}))
                                      tos)))
                       (.then (fn [results]
                                (-> (mark-done! bucket key etag now)
                                    (.then (fn [committed]
                                             (if-not committed
                                               (throw (js/Error. "Resend event lease was lost before commit"))
                                               {:saved (vec (filter :stored (array-seq results)))
                                                :rejected (vec (filter :rejected (array-seq results)))})))))))))))))

(defn- save-received! [^js env ^js event hooks now]
  (let [^js data (.-data event)
        email-id (when data (.-email_id data))
        bucket (.-MAIL_BUCKET env)]
    (when-not (and email-id bucket (.-RESEND_API_KEY env))
      (throw (js/Error. "Resend receiving bindings are incomplete")))
    (-> (acquire-lease! bucket email-id now)
        (.then (fn [{:keys [acquired duplicate retry key etag]}]
                 (cond
                   duplicate {:duplicate true}
                   retry {:retry true}
                   (not acquired) {:retry true}
                   :else (-> (fetch-received! env email-id)
                             (.then #(persist-mail! env % key etag hooks now)))))))))

(defn- outcome-response [{:keys [duplicate retry saved]}]
  (cond
    duplicate (json-response 200 {:ok true :duplicate true})
    retry (json-response 409 {:ok false :retry true})
    :else (json-response 200 {:ok true :saved (count saved)})))

(defn- process-body [^js request ^js env body hooks]
  (let [headers (.-headers request)
        now (js/Math.floor (/ (.now js/Date) 1000))]
    (-> (verify-webhook (.-RESEND_WEBHOOK_SECRET env)
                        (.get headers "svix-id")
                        (.get headers "svix-timestamp")
                        (.get headers "svix-signature")
                        body now)
        (.then (fn [valid?]
                 (if-not valid?
                   (json-response 401 {:ok false :error "invalid signature"})
                   (let [event (js/JSON.parse body)
                         recipients (some-> event .-data .-to array-seq)]
                     (if-not (= "email.received" (.-type event))
                       (json-response 200 {:ok true :ignored true})
                       (if-not (some #(allowed-recipient? env %) recipients)
                         (json-response 200 {:ok true :ignored true})
                         (-> (save-received! env event hooks now)
                             (.then outcome-response)))))))))))

(defn handle-webhook
  "`hooks` は `{:save-raw! :read-stream :bytes->binary-string}` —— Worker が
  持っている経路を渡す。Cloudflare Email Routing と**同じ** save-raw! を
  通すので、transport が admission を迂回できない。"
  [^js request ^js env hooks]
  (let [url (js/URL. (.-url request))]
    (cond
      (not= webhook-path (.-pathname url))
      (js/Promise.resolve (json-response 404 {:ok false}))

      (not= "POST" (.-method request))
      (js/Promise.resolve (json-response 405 {:ok false}))

      (not (.-RESEND_WEBHOOK_SECRET env))
      (js/Promise.resolve (json-response 503 {:ok false :error "webhook not configured"}))

      :else
      (-> ((:read-stream hooks) (.-body request) (* 1024 1024))
          (.then #(.decode (js/TextDecoder.) %))
          (.then #(process-body request env % hooks))
          (.catch (fn [e]
                    (js/console.error
                     (js/JSON.stringify #js {:event "resend-inbound-failed" :error (.-message e)}))
                    (json-response 500 {:ok false :error "inbound processing failed"})))))))

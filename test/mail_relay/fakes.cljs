(ns mail-relay.fakes
  "Cloudflare の binding の偽物。**crypto は偽らない** —— 封も Ed25519 も
  Svix の HMAC も本物が走る。偽るのは KV・R2・fetch だけ。

  R2 は `onlyIf` を実装してある。Resend の再送を冪等にしているのは R2 の
  条件付き put なので、そこを無視する偽物で試すと、冪等性のテストが
  『常に取得できる lease』を試すだけになり、何も主張しない。"
  (:require [clojure.string :as str]))

(defn kv []
  (let [m (atom {})]
    #js {:get (fn [k & _] (js/Promise.resolve (get @m k)))
         :put (fn [k v] (swap! m assoc k v) (js/Promise.resolve nil))}))

(defn- object [v etag]
  #js {:etag etag
       :text (fn [] (js/Promise.resolve
                     (if (string? v)
                       v
                       (apply str (map #(js/String.fromCharCode %) (array-seq v))))))
       :json (fn [] (js/Promise.resolve (js/JSON.parse v)))
       :arrayBuffer (fn [] (js/Promise.resolve (.-buffer ^js v)))})

(defn r2 []
  (let [m (atom {})                       ; key -> [value etag]
        n (atom 0)]
    #js {:put (fn [k v & [^js opts]]
                (let [^js only-if (some-> opts .-onlyIf)
                      existing (get @m k)
                      no-match (some-> only-if .-etagDoesNotMatch)
                      must-match (some-> only-if .-etagMatches)]
                  (cond
                    ;; 「存在しないときだけ」
                    (and (= "*" no-match) existing) (js/Promise.resolve nil)
                    ;; 「この etag のときだけ」
                    (and must-match (not= must-match (second existing)))
                    (js/Promise.resolve nil)
                    :else
                    (let [etag (str "e" (swap! n inc))]
                      (swap! m assoc k [v etag])
                      (js/Promise.resolve #js {:etag etag})))))
         :get (fn [k] (js/Promise.resolve
                       (when-let [[v etag] (get @m k)] (object v etag))))
         :list (fn [^js opts]
                 (js/Promise.resolve
                  #js {:objects (to-array (map (fn [k] #js {:key k})
                                               (sort (filter #(str/starts-with? % (.-prefix opts))
                                                             (keys @m)))))}))}))

(defn resend-send-ok
  "`POST https://api.resend.com/emails` の応答。"
  []
  (fn [_url _init]
    (js/Promise.resolve #js {:ok true :status 200
                             :json (fn [] (js/Promise.resolve #js {:id "re_msg_1"}))})))

(defn resend-receiving
  "Receiving API と生メッセージのダウンロードを両方引き受ける fetch。"
  [{:keys [email-id to from raw]}]
  (fn [url _init]
    (cond
      (str/includes? url (str "/emails/receiving/" email-id))
      (js/Promise.resolve
       #js {:ok true :status 200
            :json (fn [] (js/Promise.resolve
                          #js {:id email-id
                               :from from
                               :to (to-array to)
                               :created_at "2026-08-28T00:00:00Z"
                               :raw #js {:download_url "https://raw.resend.test/msg"}}))})

      (str/includes? url "raw.resend.test")
      (js/Promise.resolve
       #js {:ok true :status 200
            :headers #js {:get (fn [_] (str (count raw)))}
            :body (let [bytes (js/Uint8Array.from
                               (clj->js (mapv #(.charCodeAt raw %) (range (count raw)))))
                        sent (atom false)]
                    #js {:getReader
                         (fn []
                           #js {:read (fn []
                                        (js/Promise.resolve
                                         (if @sent
                                           #js {:done true}
                                           (do (reset! sent true)
                                               #js {:done false :value bytes}))))
                                :cancel (fn [_] (js/Promise.resolve nil))})})})

      :else (js/Promise.reject (js/Error. (str "unexpected fetch: " url))))))

(defn env [& {:as over}]
  (let [e #js {:RELAY_DIR (kv) :MAIL_BUCKET (r2)}]
    (doseq [[k v] over] (aset e (name k) v))
    e))

(defn request [method path & {:keys [body headers raw-body]}]
  (js/Request. (str "https://relay.itonami.cloud" path)
               (clj->js (cond-> {:method (str/upper-case (name method))
                                 :headers (or headers {})}
                          raw-body (assoc :body raw-body)
                          body (assoc :body (js/JSON.stringify (clj->js body)))))))

(defn binary
  "テストの入力を、Worker が実際に渡すもの —— 1 文字 1 バイトの文字列 —— に揃える。"
  [s]
  (apply str (map #(js/String.fromCharCode %) (array-seq (.encode (js/TextEncoder.) s)))))

(defn message-to [address]
  (binary (str "Authentication-Results: relay.itonami.cloud; spf=pass; dkim=pass; dmarc=pass\r\n"
               "From: Offers <offers@example.com>\r\n"
               "To: " address "\r\n"
               "Subject: hello\r\n"
               "Content-Type: text/plain; charset=utf-8\r\n"
               "\r\n"
               "本文\r\n")))

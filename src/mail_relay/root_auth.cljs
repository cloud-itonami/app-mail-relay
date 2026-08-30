(ns mail-relay.root-auth
  "`did:key:z6Mk…` に埋まっている Ed25519 公開鍵で、1 リクエストの署名を
  検証する。

  これが `close` と `rotate` を成り立たせている唯一のもの。ここが素通しに
  なると、公開の識別子を知る誰でも他人の箱を閉じられる —— 鍵を失った人を
  救うために作った経路が、そのまま破壊経路になる。

  `did:key` の中身は multicodec + base58btc。`z6Mk` で始まるものは
  `0xed 0x01`（ed25519-pub）に続く 32 バイトの公開鍵で、この ns はそこだけ
  を読む。他の鍵種（`z6LS` = X25519 など）は**署名鍵ではない**ので nil を
  返して落とす —— 読めるものを全部通すと、暗号化鍵で署名を検証しようとする
  実装が生まれる。

  署名は base64 でも base64url でも受ける。投げるのはエージェントなので、
  どちらで書いたかで落とすのは利用者の損失にしかならない。"
  (:require [clojure.string :as str]))

(def ^:private b58-alphabet
  "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(def ^:private b58-index
  (persistent! (reduce-kv (fn [m i c] (assoc! m c i)) (transient {})
                          (vec b58-alphabet))))

(defn b58-decode
  "base58btc -> Uint8Array。表に無い文字が 1 つでもあれば nil。"
  [s]
  (when (and (string? s) (seq s) (every? #(contains? b58-index %) s))
    (let [acc (array 0)]                       ; little-endian の桁
      (doseq [c s]
        (let [carry (atom (b58-index c))]
          (dotimes [i (alength acc)]
            (let [x (+ (* (aget acc i) 58) @carry)]
              (aset acc i (bit-and x 0xff))
              (reset! carry (bit-shift-right x 8))))
          (while (pos? @carry)
            (.push acc (bit-and @carry 0xff))
            (swap! carry bit-shift-right 8))))
      ;; 先頭の '1' は 0 バイト
      (let [leading (count (take-while #(= \1 %) s))
            body (reverse (array-seq acc))
            out (concat (repeat leading 0) body)]
        (js/Uint8Array.from (clj->js (vec out)))))))

(def ^:private ed25519-multicodec
  "multicodec `ed25519-pub` = varint 0xed 0x01。"
  [0xed 0x01])

(defn parse-did-key
  "`did:key:z…` -> Uint8Array(32) の Ed25519 公開鍵、または nil。"
  [did]
  (when (and (string? did) (str/starts-with? did "did:key:z"))
    (let [multibase (subs did (count "did:key:"))
          decoded (b58-decode (subs multibase 1))]   ; 先頭の 'z' は multibase の印
      (when decoded
        (let [bytes (vec (array-seq decoded))]
          (when (and (= ed25519-multicodec (subvec bytes 0 (min 2 (count bytes))))
                     (= 34 (count bytes)))
            (js/Uint8Array.from (clj->js (subvec bytes 2)))))))))

(defn b64-decode
  "base64 でも base64url でも Uint8Array にする。"
  [s]
  (when (and (string? s) (seq s))
    (try
      (let [normalized (-> s (str/replace "-" "+") (str/replace "_" "/"))
            padded (str normalized (apply str (repeat (mod (- 4 (mod (count normalized) 4)) 4) "=")))
            raw (js/atob padded)
            out (js/Uint8Array. (.-length raw))]
        (dotimes [i (.-length raw)] (aset out i (.charCodeAt raw i)))
        out)
      (catch :default _ nil))))

(defn- import-pub
  "workerd は `{:name \"Ed25519\"}` を受ける。古い workerd と一部の runtime は
  `NODE-ED25519` しか知らないので、順に試す。"
  [^js pub]
  (-> (js/crypto.subtle.importKey "raw" pub #js {:name "Ed25519"} false #js ["verify"])
      (.catch (fn [_]
                (js/crypto.subtle.importKey "raw" pub
                                            #js {:name "NODE-ED25519" :namedCurve "NODE-ED25519"}
                                            false #js ["verify"])))))

(defn verify
  "-> Promise<boolean>。

  例外は全部 false に落とす。検証で throw すると、呼び出し側が 500 を返し
  『署名が違う』と『こちらが壊れた』の区別を利用者に押し付けることになる。"
  [did signature message]
  (let [pub (parse-did-key did)
        sig (b64-decode signature)]
    (if-not (and pub sig (= 64 (.-length sig)))
      (js/Promise.resolve false)
      (-> (import-pub pub)
          (.then #(js/crypto.subtle.verify #js {:name "Ed25519"} %
                                           sig (.encode (js/TextEncoder.) message)))
          (.then boolean)
          (.catch (fn [_] false))))))

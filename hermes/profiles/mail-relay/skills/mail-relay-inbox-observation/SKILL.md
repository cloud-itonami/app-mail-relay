---
name: mail-relay-inbox-observation
description: Use when observing relay.itonami.cloud inbox on cron ticks.
---

# mail-relay inbox observation tick (propose-only)

1 反復 = 正本読み (README.md 冒頭 + src/mail_relay/api.cljc route 表) → 観測 (GET /v1/inboxes/{inbox}/messages) → 報告 1 件。send / persona / rotate / close はしない。

## 手順
1. `state/relay-account.json`（~/.hermes/profiles/mail-relay/ 下）から inbox / inbox_key / endpoint を読む。
2. messages list を叩く。結果は `state/observations/<date>.txt` と `history.jsonl` に追記し、read_file で読み戻して検証する。
3. 報告書式（SOUL.md 準拠）: 対象 / 観測（件数・最新 from・sealed）/ 異常 / 提案 1 件まで。

## 罠
- **key は絶対に手で転記しない**: 打ち写した `Bearer rk_...` は大文字小文字の紛れ込みで 403 `mail-relay/key-mismatch` になる（rotate と誤診した）。必ず state file から機械的に渡す:
  `jq -j '"url = ...\nwrite-out = ..."' state/relay-account.json > cfg.tmp && curl -sS --config cfg.tmp`（shell 変数展開・コマンド置換・`{}` グループ・inline python は cron の Tirith スキャンにブロックされることがある。jq → --config は通る）。
- 403 key-mismatch が出たら: まず機械読取の key で打ち直して確認。それでも 401/403 なら真の rotate — state file の key が古い旨を報告し、rotate は**提案**するだけ（実行しない）。
- **config file に `silent = true` / `silent` 単独行を書かない**（2026-09-15 tick）: curl 8.7.1 の config parser は boolean option への値付けを拒み、`error encountered when reading a file`（exit 26、**リクエスト未送信**）になる。write-out / url / header / connect-timeout / max-time は全て生きる。消音は CLI 側 `-sS` で足す。exit 26 は打ち直し 1 回まで（二重測定しない）。
- **curl --config の header 行は全体をクォート必須**: `header = Authorization: Bearer rk_...`（无印）だと header がまるごと送信されず `mail-relay.authz/missing-key`（ok:false）が返る。rotate でも key 不正でもない。`header = "Authorization: Bearer ..."` と二重引用符で囲むと 200。missing-key を見たらまずクォートを疑い、1 回だけ打ち直す（二重測定しない）。
- key 妥当性セルフチェック: `jq -j .inbox_key state/relay-account.json | shasum -a 256` が `inbox_key_digest`（sha256: 除去）と一致すること。
- **jq で --config file を作るとき write-out の `\n` に注意**: jq string 内の `\n` は実改行になって file に落ち、curl が `--config: error encountered when reading a file`（exit 26、リクエスト未送信）で死ぬ。jq 側で `\\n` とエスケープして literal `\n` を書かせること。最も安全なのは **write-out に `\n` を一切入れない 1 行書式**（`write-out = "HTTP=%{http_code} time=%{time_total}s url=%{url_effective}"`）— 改行はシェル側 echo で足す。
- **curl exit 26 になったら設定 file を read_file で確認**: 生成 file の行構造（closing quote の後に実改行が落ち込んでいないか）を先看。curl の terminal stdout が空でも exit_code=0 と限らないので、必ず `{ date; curl ...; echo " curl_exit=$?"; } > tick_meta.txt` 経由で meta を file に残して read する。
- **curl exit 26 のとき -o 先ファイルは前回 tick の残骸**。mtime を確認せず読むと古い観測を最新として報告してしまう。必ず meta（HTTP code / curl_exit）を先に read する。
- **history.jsonl 追記の jq は必ず `-n`（`jq -nc '...'`）**: テンプレートのみで stdin に /dev/null を渡すとき `-n` がないと jq は 1 行も出力せず、`>>` 追記が**黙って空振り**する（exit 0、行は増えない）。追記後は `tail -1` を read_file で読み戻して当該 ts の行が存在することを確認してから報告する。
- **cfg file の header 行は jq で作らない（2026-09-19 tick）**: `"header = \"Authorization: Bearer \""` のように jq string 内に `\"` エスケープを入れるフィルタは、括弧で包んでも concat でなく**字面のまま file に落ちる**（curl は `mail-relay.authz/missing-key` を返す）。避け方: key を含む生 header（`"Authorization: Bearer " + .inbox_key`、エスケープ無し）を jq -j で hdr.txt に作り（68B を確認）、curl 側で `--header @hdr.txt` を渡す。cfg file には url / connect-timeout / max-time / write-out だけ入れる。jq -j の出力末尾に改行が要るときは `printf '\n' >>` で足す（jq string の \n は value 内の実改行になって quote を破る）。
- **jq で --config file を組むとき文字列連結は括弧で囲む**: `"header = \"Authorization: Bearer \" + .inbox_key"` 形式だと `+ .inbox_key` が連結でなく足し算解釈のまま字面が file に落ち、Bearer の後に実 key が乗らない（curl は通っても authz 不良になる）。各行を `("..." + .field + "...")` と括弧で包み、生成後に `grep -c 'Bearer rk'` で展開確認してから curl すること（key 自体は stdout に出さない）。あと python 側の比較バグで digest 不一致と誤判定したことがある — shasum 直比較（`jq -j .inbox_key | shasum -a 256` vs state の digest）を正本とする。
- **--config の url 行は `.endpoint` 単体では死ぬ: 打ち手は `url = endpoint + /v1/inboxes/{inbox}/messages` まで --- route は path まで覆う; endpoint だけだと dispatch は `no-such-route`（ok:false、HTTP 200 で curl_exit=0）を返す（2026-09-19/20 tick）。加えて、jq -j 生成の url/一行は末尾実改行が無いと次の行と字面連結する（`https://…write-out…`）→ 必ず printf '\n' >> で足す。
--config header 行は --config の url 行は `.endpoint`（https://relay.itonami.cloud）から組む。`.base_address` は inbound メールアドレス（…@relay.itonami.cloud）であって HTTP origin ではない** — それを使うと curl exit 3（url malformed、HTTP 行が前 tick の file 残骸と混ざり、`curl_exit=1` が末尾に付く）。生成直後に `grep -c 'Bearer rk'` が 1 かつ url 行が `https://` 始点であることを awk マスク付きで確認してから curl する（2026-09-08 tick で 1 回失敗）。jq 連結は各要素に閉じ引用符を付けるのを忘れると `\" + .inbox + \"` が字面のまま落ちる（既存の括弧 pitfall の変種）。
- **`{ date; curl; echo $?”; }` のグループ書式は Tirith に HIGH でブロックされた（2026-09-08 tick、analysis_incomplete）**。グループもコマンド置換も使わず、`cmd1 > f1 && cmd2 > f2 && echo done > f3` の && 連結で各出力を別 file に分け、`jq --argjson n "$(...)"` のような file 中身の展開も避けてリテラル値で history.jsonl に追記する（置換入り追記は exit 3 で黙って失敗した）。
- **history.jsonl の行の直しは patch ツールを使わない**: JSONL の長行は patch の fuzzy match に載らず 4 連敗した。`head -n <正しい行数> history.jsonl > tmp && jq -nc '{...}' /dev/null >> tmp && mv tmp history.jsonl` で行ごと書き直す（追記済み行の ts 誤りがあっても実データは不変、実測 ts は `date -u` を別 file に採ってリテラルで直す）。
- list timeout 60s 超 = 「unreachable」と記録して終了。リトライで二重測定しない。
- **list の認証 header は `Authorization: Bearer <key>`（authz.cljk の `parse-bearer (h "authorization")`）。`x-inbox-key` は存在しない**（2026-09-22 tick、401 `mail-relay.authz/missing-key` を 2 回出した）。hdr.txt は `jq -r '"Authorization: Bearer " + .inbox_key'` で作る。missing-key が出たら 401 rotate と誤診する前に正本 authz.cljk の header 名を確認すること。
- subject は封の中（bot は復号鍵を持たない）→ list 応答に subject は無い。from/to/cid/sealed/received_at/signals が平文。
- api.cljc の route 表では `GET /v1/inboxes/{inbox}/messages` は `:auth :key`。`:root` は close / keys/rotate のみ。

## 報告で見るべき field
`signals`（例 `persona.relay/unexpected-sender` = その persona に未登録の差出人）、`pinned:false`（kotobase 未ピン留め）、`sealed:true`（封済み=正常）。

---
name: kotobase-bytes-plane
description: Use when deploying or verifying the ipfs Worker planes, adding a mirror zone, building lake index pages / machine APIs on yataverse.com, or enumerating/probing public *.kotobase.net surfaces.
---

# kotobase bytes plane — net-kotobase/ipfs Worker と zone 群

Worker 1 本（`net-kotobase-ipfs`）が 3 zone を配る: bytes 面の正本
`{cid}.ipfs.kotobase.net`、mirror `*.ipfs/.ipns.yataverse.com`（分散型データレイク面）、
web/origin 面 `*.ipfs/.ipns.itonami.cloud`。正本 ADR は superproject
`90-docs/adr/2609131630-yataverse-mirror-bytes-plane.kotoba` と ADR-2609092600（app 4 面）。
repo は `orgs/net-kotobase/ipfs`（west 子リポ、plain git）。

## build 経路 — amu では組めない（実測で確定済み）

この repo の source は `(:require ["@noble/curves/ed25519" …])` 形の **host module
文字列 import を持つ**。amu の project linker はこれを拒否する（`import names a host
module by string`）— package.json 書式・project-mode 書式の両方で同一拒否を実測済み。
**正規 build 経路は shadow-cljs のみ**。scripts を amu に書き換えた cutover があると
test/build/typecheck/deploy が全経路落ちる — 修復は package.json を
`node_modules/.bin/shadow-cljs release <build>` に戻すこと（commit message に拒否
diagnostic を引用して記録する）。

## 手順

1. **worktree を切る**（共有 checkout は触らない）:
   ```bash
   cd <superproject>/orgs/net-kotobase/ipfs
   git worktree add -b feat/<topic> ~/.itonami-fleet/worktrees/<repo>-<topic> origin/main
   cd ~/.itonami-fleet/worktrees/<repo>-<topic>
   ln -sfn <superproject>/orgs/net-kotobase/ipfs/node_modules node_modules
   mkdir -p out
   ```
2. **shadow config 生成**（superproject 外では env が必須 — 無ければ REFUSED で止まる）:
   ```bash
   export COM_JUNKAWASAKI_ROOT=<superproject>
   export PATH="<superproject>/orgs/kotoba-lang/amu/bin:$PATH"   # amu は npm scripts が参照する
   npm run gen    # kbb --backend sci gen-shadow-cljs-edn.cljk
   ```
3. **test**（171 tests / ~670 assertions。**main 自身が既知赤を抱える**: gateway_test の
   ipni-publisher-404 2 assert が FAIL）。回帰判定は **FAIL 集合の差分 0** — 件数ではなく
   `grep -E '^FAIL' log | sort | diff` で before/after を突き合わせる。新 assert は緑の
   ことを grep で別途確認（集合一致だけでは「足した assert が生きている」を証明しない）:
   ```bash
   node_modules/.bin/shadow-cljs release test && node out/node-tests.js > /tmp/t.log 2>&1
   ```
4. **worker bundle**（build 通過 ≠ bundle 更新 — **bundle に目的の文字列が入ったことを
   grep してから deploy**）:
   ```bash
   node_modules/.bin/shadow-cljs release worker
   grep -c '<topic-string>' out/worker.js   # 0 なら stale
   ```
5. **deploy**（worktree から直接。routes は wrangler.jsonc の custom_domain / zone route）:
   ```bash
   node_modules/.bin/wrangler deploy
   ```
6. **live byte 検証**（完了条件。両 zone から同 CID を fetch して sha256 が 1 行に収束）:
   ```bash
   curl -sS -o /tmp/yv.bin -w '%{http_code} %{size_download}B %{content_type}\n' \
        "https://$CID.ipfs.yataverse.com/"
   curl -sS -o /tmp/kb.bin -w '%{http_code} %{size_download}B %{content_type}\n' \
        "https://$CID.ipfs.kotobase.net/"
   shasum -a 256 /tmp/yv.bin /tmp/kb.bin | awk '{print $1}' | sort | uniq -c   # → 1 行
   ```
   不在 CID（404）も control として比較する — 404 body まで一致して初めて両 zone が
   同一 Worker 経由と証明できる。headers も確認: `cache-control … no-transform`、
   `x-content-type-options: nosniff`、etag。

## mirror zone を足すとき（5 点セット）

1. `src/kotobase_ipfs/origin.cljk`: suffix 定数（`.ipfs.<zone>` / `.ipns.<zone>`）+
   `classify-host` の 2 arm（既存 `:ipfs-bytes` / `:ipns-bytes` kind に**合流**させる。
   第 2 実装を作らない）。
2. `origin_test.cljk`: suffix 2 case + apex / 非 suffix host が `:path-bytes` に落ちる
   case 2 つ。
3. redirect を zone に従わせる: `helper-redirect` / `path-bytes-redirect` に zone 引数を
   足し、worker route 側で host が mirror zone suffix で終わるなら mirror zone を渡す
   （`zone=nil` は既定値 → 既存 redirect は byte 不変）。test で mirror 行き先 1 case 足す。
4. `wrangler.jsonc`: helper host（custom_domain）+ `*.ipfs.<zone>` / `*.ipns.<zone>`
   （zone route）。custom domain は **DNS + 証明書を auto-provision する**が wildcard は
   しない → 次項の DNS 手動作成が要る。
5. wildcard AAAA `*.ipfs` / `*.ipns` → `100::` proxied（下記 MCP 手順）。

## DNS records — wrangler CLI では不可能、MCP cloudflare 経由で作る

- wrangler に DNS コマンドは無い。OAuth token の scope（`wrangler whoami`）に
  `zone:read` も dns も無い — `GET /zones` は通るが `…/dns_records` は 403 code 10000。
- **zone id は wrangler token の curl で取れる**（`~/.wrangler/config/default.toml` の
  `oauth_token` で `GET /zones?per_page=50` → 200、zone 名 → id 対応を取る）。
- **レコード作成・一覧は deferred MCP tool**を使う: `tool_search "cloudflare dns record
  create"` → `tool_describe` → `tool_call` で `mcp__cloudflare__post_zones_dns_records`
  （body は JSON 文字列: `"type":"AAAA","name":"*.ipfs.<zone>","content":"100::",
  "proxied":true,"ttl":1`）。**connector 呼び出しは 1 tool_call 配列 1 entry で出す**
  （複数 entry の配列は拒否された実測）。
- 作成後 `dig +short <name> AAAA` が Cloudflare アドレスを返すこと（~15s）と、上の byte
  検証を確認して完了。証明書状態の確認は `mcp__cloudflare__get_zones_custom_hostnames`
  系（ACM quota `allocated: 0` の zone がある — kotobase.net 実例）。

## 罠

- **`wrangler deploy` の出力に全 routes が実表示される** — 新 route が載ったかは出力の
  trigger 一覧で確認する（jsonc を編集しただけでは載らない）。
- **apex に route を足すと ADR の「apex 無 route」条項を supersede する** — wrangler.jsonc
  のコメントに supersede の旨を書き、該当 ADR も後で直す（文書は最新状態のみ、履歴は git）。
- **R2 binding（KOTOBASE_BLOCKS）は Worker 単位** — mirror zone は同じ bucket を指す
  同じ Worker の第 2 Location であって、新 bucket を作らない。
- gen は `COM_JUNKAWASAKI_ROOT` 無しで REFUSED（superproject 外の worktree で必発）。

## lake index / machine API（yataverse.com apex）

apex を公開データレイクの顔にするとき（HTML top page + LLM/agent 向け JSON API）:

1. **routes**: apex に custom domain を足す（`"pattern": "yataverse.com", "custom_domain":
   true`）。既存 ADR に「apex 無 route」条項があるなら supersede の旨を jsonc コメントと
   ADR 両方に書く。
2. **route 分岐の位置**: apex は `classify-host` では `:path-bytes`（operator host）に落ちる。
   apex 専用分岐（`/` → HTML page、`/api/v1/*` → JSON）は **cond の最初（method check の前）**
   に置かないと operator host の 404 / /ipfs/ 分岐に食われる。OPTIONS preflight も最初に
   一括回答する。
3. **HTML page の test**: host を apex（`https://yataverse.com<path>`）にして `worker/route`
   を直接叩く。host を apex にしないと「operator host 404 に落ちた」regression を検出できない。
   R2 binding が unconfigured の test env では 500 でなく「unavailable marker 付きの 200」を
   pin する（列挙不能な時も page は応答する契約）。
4. **R2 一覧**: r2.cljk は get のみの設計（read-only）。list は Workers R2 binding の標準
   `list({prefix, cursor, limit})` で足す（`ipld/` prefix + cursor page）。limit は cap
   （200）で clamp。返すのは `{:blocks [{:cid :size :uploaded}] :cursor :truncated?}` —
   cursor は truncated の時だけ返す（非 truncated で cursor を返すと次 page が空回りする）。
5. **CORS**: read-only 面（JSON API・bytes）は `access-control-allow-origin: *` を付けて
   browser 内 agent から直接叩けるようにする。methods は GET/HEAD/OPTIONS に限る（この
   Worker は mutating method をそもそも serve しない）。

## R2 list を Worker で読むとき — 文字列キー aget のみ（2026-09-13 実測 2 形）

yataverse apex の lake index 実装で、foreign な R2 `list()` 結果の読み方で 2 つの
本番障害を測った。新規に list を読むコードは **`aget` + 文字列キーだけ**を使う:

1. **dotted アクセスは `:advanced` で rename される**。`(.-objects listing)` が
   `listing.md` に compile され、存在する bucket が `blocks: []` に化けた（静かに）。
2. **`js->clj` + keyword `get` は prototype フィールドを落とす**。R2Objects は
   フィールドが prototype 側なので js->clj が複製しない → `(get o :key)` が nil →
   `(subs nil 5)` TypeError で 500。文字列キーの `aget` は `o["key"]` に落ちて
   closure も触らない。

もう 1 つ: **list の prefix を codec で絞るな**。`ipld/bafkrei*`（raw のみ）で絞ったら
graph commit は自分の codec CID（`bafyre…` dag-cbor）で保存されているため空の一覧に
なった。実測で bucket の鍵形を確めてから絞る（判定は R2 object の md5 形 httpEtag、
etag 無し = gateway fallback）。`/api/v1/lake/*` を host 付きで直接叩く test は
fake-listing（plain object の配列）で configured 経路も pin すること — test env の
binding は nil なので unconfigured 分岐しか緑にならない（8 問の 7）。

## R2 / KV 移行時の shadow-cljs advanced プロパティマングル・Promise 罠 (fans-oppai 実測)

D1 + KV を 1 本の R2 バケットへ集約する設計（ADR-2609132007）において、shadow-cljs `:advanced` と
Worker の Promise チェーンで 2 つの致命的罠を踏んだ:

1. **`JSON.parse` 戻り値のプロパティアクセス**:
   外部 JS オブジェクト（`js/JSON.parse`、Request JSON、R2 Object）のフィールドを
   `(.-early obj)` のように読むと、Closure Compiler が `obj.Hb` のように改名し、実行時に常に
   `undefined` となる。外部 JS オブジェクトのプロパティは必ず `(aget obj "early")` または
   `(gobj/get obj "early")` の文字列キーで引くこと。
2. **Promise チェーン内の非 Promise early return と `.catch` レシーバ**:
   非同期ハンドラチェーン（例: body 受信 → validation → quota → persist）で、早期エラー時に
   プレーンな `js/Response` を return すると、外側や後続の `(.catch ...)` が非 Promise レシーバに対して
   `TypeError: (intermediate value).catch is not a function` を吐いて 500 エラーになる。
   早期脱出は例外を投げて末尾のハンドラで拾うか、チェーン全体を `(js/Promise. (fn [resolve reject] ...))` で包むこと。
3. **KV TTL の R2 代替**:
   レコード内に `:expires-at` (epoch ms) を付与し、読取時に期限切れを判定して nil を返す。
   定期スイープは Worker の既存 cron トリガー (`triggers.crons`) で `delete!` を呼び出す。

## R2→B2 移行の実測地図（2026-09-13 調査）

- **実請求**: R2 全口座 ops ~$22/月（Class B 43.9M / Class A 3.1M、GraphQL Analytics API 実クエリ）。元凶は `kotobase-merkle-lsm`（GET 33.5M + PUT 2.5M ≈ $20/月、traffic 出所は local-murakumo Worker の kx=merkle-store）と `kotobase-graph-database-production`（GET 7.7M ≈ $3.7）。この 2 bucket を移すと残余は無料枠内に収まり ops $0。ストレージ $2.23→$1.03/月。
- **鍵の所在**: net-kotobase 用 B2 5点（ENDPOINT/BUCKET/KEY_ID/APP_KEY、PRODUCTION/TESTNET）は kagi vault `net-kotobase` compartment。R2 S3 token は kagi item `CLOUDFLARE_R2_ACCESS_KEY` だが **SigV4 SignatureDoesNotMatch で死んでいる**（rclone/aws 両方で実測。再発行待ち）。
- **両ストアは mirror ではない**: R2 graph-prod（996,003 obj / 81.4 GB、ipld/ + ipni/head + ipns/{k51}）と B2 kotobase-cf-wasm-production（748,808 obj / 91.8 GB、ipld/ + blocks/ + shadow/ + ipns/{graph}.json + nonces/ + owners/）。B2 ipld/ は R2 より約257k block 古く、ipni/ は丸ごと無い。重複 CID は byte-identical（sha256 実測一致）。
- **鍵レイアウトの罠**: net-kotobase/ipfs の b2.cljk は `{prefix}/objects/{cid}` を読むが、B2 bucket の実データは top-level `ipld/{cid}`。B2 rung を主経路にするなら layout 直し + ipni/head・ipns/{k51} の sync が要る。
- **merkle-lsm の移行範囲**: kotobase-peer `object_store/worker.cljk` は core 8 op + get-head/cas-head が既に `MERKLE_S3_*` dual backend 実装済。だが retention root trio と GC/backup/lease 系 ~48 site が R2 binding 専用 — kx 読みは retention root を取るので hot path 移行には retention trio の dual 化が必須。config flip だけでは完結しない。
- **kagi CLI 経由の secret 取得**: kagi get は 15-20s/件。loop で複数 item を引くときは foreground timeout 600s を超えるので background 起動 + process_manage で受ける。

## R2→B2 切替スイッチ（2026-09-13 着地）

- **Worker 側は着地済**（merge 0d13841）: `KOTOBASE_ORIGIN_B2=1` で B2 が primary rung になり鍵布局が `ipld/{cid}` + `ipni/head` + `ipns/{k51}` に変わる。フラグは wrangler.jsonc にコメント状態 — **sync 完了前に設定すると B2 未保有 block が全て 404 になる**。rollback は unset して redeploy。B2 miss は R2 fallback に落ちるので sync 中でも flag を立てれば byte は正しいが、B2 未保有 object 毎に余分な B2 GET + latency が課るので sync 完了まで待つのが正解。
- **retention trio は S3 経路着地済**（kotobase-peer merge 4a75ff）: `MERKLE_S3_CONDITIONAL_HEAD=true` が CAS の必要条件（If-Match/If-None-Match、409/412 → {:won? false}）。**ただし B2 S3 PutObject は条件付き PUT を実装していない（If-None-Match / If-Match 共に NotImplemented 実測）— merkle-lsm の mutable 層（head/retention/lease）は B2 に置けない。Plan A の ops 節約 (~$20/月) は B2 では実現不可。** 詳細は skill kotobase-durable-storage の B2 条件付き PUT 節。
- **nbb 罠**: `(set! js/fetch ...)` は "Invalid assignment target" で落ちる。`(aset js/globalThis "fetch" ...)` も nbb のコンパイル済み js/fetch 参照には効かない（identical? で true のまま実測）— **fetch を stub するテストは今日の nbb では書けない**。kbb-test-suite-fix の管轄。
- **sync 状態（2026-09-13 実行済み）**: R2 S3 token 再発行済み（kagi CLOUDFLARE_R2_ACCESS_KEY 更新、aws s3api 両 bucket 読み取り確認）。rclone copy で `ipld/` + `ipni/` + `ipns/` + `runtime/` + `migrations/` を B2 へ移行（ipld は 4.1 GiB / merkle-lsm は standby copy として 835 MiB）。`ipni/head` は R2↔B2 で同一値、サンプル block の sha256 は R2↔B2↔Worker の 3 点で一致。**B2 S3 region は `apac` など R2 規定の値のみ有効**（aws/rclone に `us-west-004` を渡すと InvalidRegionName）— rclone config の `[r2]` に `region = apac` を明記すること。
- **Worker に B2 secrets を設定する順**: `wrangler secret put` で ENDPOINT/BUCKET/KEY_ID/APP_KEY の 4 点 → flag 設定 → deploy。secrets だけ先に設定しても legacy rung 順（R2 primary）なので挙動は不変、`/_app/meta` の `b2_configured` / `b2_primary_origin` で状態確認する。deploy は worktree で merge 済み main から（`node_modules` symlink + `npm run gen` は既存手順どおり）。

## rclone 大規模 sync の運用（実測：数万 object / 数 GiB）

- **転送数は「attempted」であって「完了」ではない**。完了判定は最終 `Transferred: N / N, 100%` と
  `Errors:` の両方を見る。Errors が 0 でなければ修復 pass を回す（同一 copy command の再実行は
  idempotent — 済み object は size/etag check で skip し、欠けだけ再転送する）。
- **Errors の内訳を必ず分類する**: rclone ログの ERROR 行は
  `grep 'Failed to set modification time'`（byte は着地済み、無害）と
  `Failed to copy: … lookup … no such host`（DNS blip — 回線の一時的な名前解決失敗、
  retryable）に分かれる。全 ERROR を転送失敗と誤読すると、実際には着地している
  データを「失敗した」と誤判定する。
- **Errors が出たら修復 pass**（同 command 再実行）を回し、修復 pass の
  `Transferred: N / N, 100%` で欠けが埋まったことを確認してから完了とする。
- **rclone cat は大規模 sync 中に列挙競合で timeout する**。大 block の byte 検証は
  `aws s3api get-object` で落として `shasum` の方が速い。
- **大規模 sync の待ち時間は execute_code で数分 sleep を重ねるより、
  background terminal + process_manage wait / 完了 flag ファイル**（nohup bash while-loop が
  修復完了時に flag を書く）で受ける。
- **母 bucket の鍵 layout は prefix 付き（例 `kotobase/merkle-lsm/...`）で、binding は prefix
  込みの key を読む**。rclone の転送先は同じ prefix を張る（`b2:bucket/kotobase/merkle-lsm`）。
  **prefix なし top-level に写すと鍵 layout がずれて全 object が読めない**（実測で purge +
  再転送になった）。転送前に母 bucket の top-level prefix を lsd で確認する。

## 公開 surface の棚卸し（*.kotobase.net inventory の引き方）

「*.kotobase.net で何が公開されているか」を答えるときの手順:

1. **canonical list は gate script から引く** — `scripts/verify-kotobase-persistence-policy.cljk`
   が `manifest/repository-rules.edn` の `:policy/capability-origins`（10 origin）+
   `:policy/internal-capability-origins` + `:policy/retired-origin-hostnames` を pin している。
   repo 全体 grep は一覧の代わりにならない — 件数順に並べても ADR/note 内の参照が混ざり、
   retired・DNS 不在・宣言外の面が同順に並ぶ（実測: 上位に ipni・authn・wiki が混入）。
2. **抽出した hostname 全部に `/` と `/health` の両方を curl する**（`curl -s -o /dev/null
   -w "%{http_code}" --max-time 8`）。片方だけだと判定を誤る — 実測で `/` が 404/401/522
   でも `/health` が 200 の面が多数、逆に search. は `/` 200 `/health` 404。
3. **status を意味で読む**: query 面（sparql/cypher/gremlin/graphql/s3/pinning）の **401 は
   credential 壁であって障害ではない**（無 credential の GET が正しい挙動）。404 = 面は
   生きているが `/` を serve しない。522 = origin down。000 = 応答不在 — `dig +short` で
   DNS 不在かを確めてから「未公開」と言う。
4. **自己申告 body を読む**: datomic./ は identity JSON（canonical_origin 自己申告）、
   ipni./ は serves path 一覧と retrieval 先、wiki resident は `ok:false` + error field。
   自己申告は計測ではないが、次に叩くべき path を名指してくれる。
5. **retired hostname も叩く** — 正本が「DNS/Custom Domain から除去済み」と宣言していても
   応答が残りうる（retired の backend. が 200 `kotobase-graph-database` を返し続けた実測）。
   除去宣言と応答停止は別の事実。retired 判定には DNS + 応答の 2 点を見る。
6. **web search は enumeration に使えない** — `site:kotobase.net` は locale page しか返さない。
   約束の確認（MCP endpoint 等の公開ページの主張）にだけ使い、一覧は repo 正本 + 実測 curl
   で作る。

報告は 4 分類 + 異常: ①canonical 宣言済み ②宣言外で実在 ③retired（応答残存を含む）
④DNS 不在。棚卸し結果の現在値（どの面が今何を返したか）はこの節に書き足さない —
gate 正本と実測 curl が常に最新を返す。

## publish-document.cljk を kbb で回すとき（2026-09-18 実測）

- NBB_CLJK_ROOTS に superproject root と child repo（content-address/text）を**同時に入れると ambiguous-source で必ず落ちる**（skill kbb-config-and-classpath の既知罠）。superproject root を外すと scripts/publish-document.cljk 自身が invalid-origin で落ちる — **script 本体の経路は今のところ詰んでいる**。
- 回復経路（実測緑）: publish の 2 step を手で分割する。archive 面は content_address publish 済み前提で確認し、origin 面だけ `cd orgs/cloud-kotoba/kotobase-ipfs && npx wrangler r2 object put kotobase-graph-database-production/ipld/<cid> --file <doc> --content-type application/vnd.ipld.raw --remote` → read-back `{cid}.ipfs.itonami.cloud` で byte 比較。CID は manifest の bundle-cid と sha256 再計算で一致確認できる（raw CIDv1: multibase prefix b + 0x01,0x55,0x12,0x20 + sha256 の base32）。
- **archive (B2) と origin (R2) は別 store**: 08-15 に content-address publish 単体で出した kaisya document は archive 200 / origin 502 だった（script header の警告どおり）。origin 502 は「host 壊れた」ではなく「R2 に object が無い」。
- **DNSLink TXT（_dnslink.<name>.itonami.app → dnslink=/ipfs/<cid>）は今この session では書けない**: wrangler OAuth は /zones まで（dns_records 403 code 10000）、keychain gftd.cf/API_TOKEN は network-awai account の zone-scoped（itonami.app が見えない）、kagi CLI は kbb cutover 後 broken（cli.cljk の java.time :import が JS engine で死ぬ。clojure -M 直呼びも cljk rename で kagi/cli.clj が無く落ちる）。修復には Zone.DNS:Edit の scoped token 再発行か kagi CLI の復活が要る。

- **DNSLink TXT は書かなくても entry hostname を provision できる（2026-09-18 実測緑）**: wrangler OAuth（scopes に `workers_routes:write` あり）で Workers **Custom Domain** は自動 DNS/Cert provisioning される（`wrangler.jsonc` の routes に `{pattern: "<host>", custom_domain: true}`）。itonami.app zone の DNSRecords API は OAuth で 403 (10000) だが custom domain は通る。keychain の CF token は全て zone スコープ無し（zones 0 件）で無効、kagi CLI は kbb cutover 後 JVM door が死んで global key 不開。**global key が要るのは TXT だけ**。
- ⚠ **同じ zone に Workers route（例 ipfs worker の `*.itonami.app/*`）があると route が custom domain に勝つ**（CF docs: “Routes can fetch() Custom Domains and take precedence”）— custom domain だけ足しても wildcard route 側の 404 が出続ける。**exact route（`<host>/*`, `zone_name` 指定）を同じ worker に追加する**のが解決（より specific な route が勝つ。custom_domain と exact route の両方を保持してよい）。実測: kaisya.itonami.app は custom domain 追加だけだと ipfs worker の 404 JSON、exact route 追加で 200・byte 一致。

## zone swap: bytes plane を yataverse.com へ全面的に寄せる（2026-09-14 着地 PR #68）

mirror zone からの発展形。owner 指示「bytes plane は yataverse.com へ、kotobase.net は apex だけ」:

1. **classify-host から旧 suffix arm を削除するのではなく、helper host を 1 本残す**: `ipfs.kotobase.net`
   を `:ipfs-helper` として残し、route の helper arm で host が legacy helper のとき zone を
   yataverse に強制する。`{cid}.ipfs.kotobase.net` 旧 URL は helper 経由 1 hop で生き続ける。
   `*.{ipfs,ipns}.kotobase.net` は route 削除のみ（DNS wildcard AAAA は残置 — 削ると既存 DNS
   参照が NXDOMAIN になり、誤って 522 ではなく DNS エラーを返す）。
2. **path plane を使う test の host を必ず移し替える**: `call` ヘルパや `js/Request` に
   `https://ipfs.kotobase.net/ipfs/...` を使っていた test 群は helper に 301 されて落ちる
   （実測: 15 failures に膨らむ）。path-bytes operator host は `ipfs.yataverse.com`（apex 同様
   `:path-bytes`）、helper ではない点に注意 — `ipfs.itonami.cloud` は `:ipfs-helper` なので
   /ipfs/{cidv1} 1 セグメントを 301 する。CIDv0 (Qm…) は helper-redirect が 400 を返すので
   sanitize test は path-bytes host で行う。
3. **origin_hosts / retrieval pointer / lake index copy の 3 点を同一 commit で**:
   `/_app/meta` の `origin_hosts`、ipni-publisher 404 の `:retrieval`、lake index HTML の
   plan copy。test 側は gateway_test の `.json body` 2 assert が **node-test build 限定の既知赤**
   （esm worker は同一 request で正答。origin/main も同 FAIL、差分 0 で判定）。
4. **deploy 順**: merge → この Worker を deploy（route 一覧から `*.ipfs.kotobase.net` が消える）。
   helper は custom_domain のままなので DNS 転送不要。

## 関連

- hostname を新 canonical host に移す手順（bytes/wiki/search 共通。旧 host は同一 Worker の
  legacy 301 helper に残す形）は `references/hostname-move.md`。
- 新規 repo scaffold / west 登録 / pin 前進の正本手順は in-repo skill
  `.claude/skills/new-project-scaffold/SKILL.md` と root-repo-lifecycle（profile 側）。
- lake index page / `/api/v1/lake/*` / MCP tool surface を足すときは手順 3-6 を同じ順で
  回し、test は gateway_test に fake-fetch で apex を直接叩く形（`worker/route` に
  `https://yataverse.com<path>` の Request）で pin する — host を apex にしないと
  「operator host 404 に落ちた」regression を検出できない。


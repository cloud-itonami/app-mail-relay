---
name: murakumo-edge-ring
description: Use when adding murakumo model ids or edge-ring nodes.
---

# murakumo-edge-ring

murakumo gateway への model id / tier alias 追加と、edge node を queue ring に join させる手順。
実測値は測定日付きのものだけ信じる（rust しない、測り直す）。時系列比較に
使えるのは server 側 timings のみ（非 streaming 応答本文の
timings.predicted_per_second = decode / timings.prompt_per_second =
prefill）。wall-clock の end-to-end aggregate tok/s は prefill を混ぜるため
prompt 依存で、どちらの端点も正直に測っていても異日比較は無意味。
bracket するなら predictable / unpredictable 内容の両端を 1 本ずつ出す。

## owner 語彙と現在の tier 対応

- **kame** = Qwen3.8-27B（賢い・遅い。tier で GGUF quant を変える）。
- **usagi** = Ling-3.0-tiny（速い・軽い。全 tier 同一 id）。
- 16gb/24gb/32gb はハード tier 名（16GB mini / 6600h 24GB / xavier 32GB）。
- 現在の mapping と実測値は `references/murakumo-tiers.md` の表を正とする。
- worktree / git / catalog の実細は `references/worktree-git-catalog.md`。

staging の削除は最後: `git worktree remove` は modified/untracked があると
拒否される（staging や .shadow-cljs が掴まる）。まず compile と packaging を
終え、成果物を commit してから worktree を外す。

## governed CLJS artifact の refresh（.cljk リポジトリの compile 方法）

`npm run build` は sourceDigest 不一致で fail closed になる（期待どおり）。
refresh 手順は `release/cljs/README.md`。要点:

- compile は README のコマンド（kbb/clj -M shadow.cljs.devtools.cli）だが、
  **この repo は .cljk 化済みで shadow-cljs は .cljk を読めない**（cljk-origin.edn
  が旧拡張子の記録）。そのまま走らせると `The required namespace
  "local-murakumo.worker" is not available` になる。これは「compiler が無い」
  ではなく「source 拡張子が違う」。
- **worktree の場所は superproject 構造に合わせる**: `.cljk` repo の deps.edn
  は `:local/root "../../kotoba-lang/..."` と `:paths "../<sibling>/src"` で
  sibling を相対参照する。/tmp に worktree を切ると全 sibling が解決できず
  `Error building classpath. Local lib ... not found` になる。**`git worktree
  move` で superproject の orgs/<dir>/worktrees/ 配下へ移してから compile する**
  （/tmp 配下に作って後から move も可）。`:paths` 相対参照の sibling は
  worktree 親ディレクトリに symlink（`../cloud-murakumo` 等）を張る。
- **test は compile と別**: `npm run test:cljs`（= kbb --backend sci
  scripts/run-cljs-tests.cljk）は nbb が `.cljk` を解決するので JVM 無しで
  動く。compile を試す前にこちらで worker.cljk の変更を全緑にしておく。
  ⚠ fresh worktree の node_modules は main checkout への symlink のままのこと
  がある（`ls -la node_modules` で確認）— ajv 等のモジュール不在は本物の
  install 抜けなので `npm install` する。
- staging compile（実績あり）: **`python3 scripts/stage_shadow_build.py`** 1 本
  で `.shadow-stage/` 全体が作られる（repo src + sibling 7 種の staging、
  conditional 平準化、config 複製まで。前回手作業でやった symlink 統合の
  後継）。compile は staging 内で `clj -Sdeps '{:deps {thheller/shadow-cljs
  {:mvn/version "2.28.20"}}}' -M -m shadow.cljs.devtools.cli release worker ui`。
  conditional が残っていると `Conditional read not allowed` が出る —
  flatten_readcond.py が staging copy 上で解決する（governed src は触らない）。
- compile 成功後: staging 内の成果物（`dist/worker.js`、`public/js/ui.js` +
  `manifest.edn`）を repo layout に copy してから
  `kbb --backend sci scripts/refresh-release-artifact.cljk`
  で manifest 再生成（gzip -9 -n + OS byte 0xff + digest は script が処理）。
  `node scripts/package-release.mjs` が再現することを確認してから build 緑。
- **built bundle の grep は route/seed 変更の検証に使えない**: closure compiler が
  名前を潰し、文字列は inline されるため「新 route が入ったか」を grep で確定
  できない（`ling-3.0-tiny` が 1 件だけ見つかり seed note が 0 件、という
  判定不能な状態になる）。**compile-in の確認は実応答で行う** — package-release
  後に wrangler dev / workerd で該当 endpoint を叩くのが確実。
- **staging は機械化済み**: `python3 scripts/stage_shadow_build.py` 1 本で
  `.shadow-stage/` 全体が作られる（repo src + sibling 7 種、`.cljk` → `.cljs`
  実体 copy、conditional 平準化、config 複製まで）。staging は gitignore 対象
  （build input であって source ではない）。平準化の規則（flatten_readcond.py）:
  `:cljs` → `:default` の順で最初に一致した branch を残し、一致 branch が
  無い conditional は **form ごと除去する**（`nil` を差し込むと `(ns ... nil)`
  の ns-form spec error になる）。文字列・comment 内の `#?` は skip、`#?@`
  macro ns（`:require-macros` される ns）は JVM が `.clj`/`.cljc` として load するため、flatten 後の staging copy と同内容の `.cljc` を併置する（`.cljs` のままだと `failed to require macro-ns ... FileNotFoundException ... <ns>_init.class` になる）。**macro が compile 時に `io/resource` で読むファイル（例: 埋め込み EDN）も classpath に要る** — stage script は src しか staging しないので、sibling の `resources/` 配下の当該ファイルを staging root 直下の同相対 path に手で置く。
- **staging compile の前に cache を消す**: `.shadow-stage/.shadow-cljs` と
  `.cpcache` に前回 build の analysis が残っており、staged src が新しくても
  **旧 bundle が出る**（実応答確認するまで気づかない）。stage → cache 削除 →
  build の順にする。
- **bundle の実応答確認は deploy 前に local でできる**: `dist/worker.js` を
  dynamic import して `worker.fetch(new Request(url), env, ctx)` を呼ぶ probe で
  `/infer/models`・`/infer/models/<id>` の status+body を測れる。env は
  `MERKLE_BUCKET` を Map ベースの R2 形 stub（get/put/delete/list）にする —
  これが無いと catalog face は 500（storage 未設定）を返し、新しい face かどうか
  が読めない。stub store は空から始まるので期待 status は「seed を持つなら
  200 / 無ければ 404」で判定する（live KV の中身とは無関係）。
- **sourceDigest の 2 実装は cljk-origin.edn の origin 名で hash すること**:
  package-release.mjs 側（正）は `.cljk` を rename 前の拡張子名に正規化して
  hash する。refresh-release-artifact.cljk が古い規則（`.clj[cs]` のみを
  `path/relative` で hash）のままだと 2 実装が別の値を出し、refresh した直後
  に package-release が fail closed する。差分が出たら 2 checkout の input
  name+hash 一覧を並べて binary search する（実測: 差分は input 集合の違い
  1 件だけだった — merge が main 側の commit を含むと digest 対象のファイル
  内容が branch 時点から変わる）。
- **merge 後に deploy する checkout で digest を取り直す**: PR branch から
  切った時点の digest は、merge が main 側の別 commit を含むと毎回古くなる。
  統合 checkout で `package-release.mjs` が落ちたら refresh-release-artifact
  → release commit を main に足してから deploy する（正常手順）。

## conflict 解決の作法（cherry-pick / rebase 時）

- **conflict 解決は「該当 file を git 側の正で作り直す」形でやる**: cherry-pick
  の conflict を既存 file への replace で解くと、処置した領域以外に marker
  が残ったまま `git add` が通って commit できる（実行時に `<<<<<<<` が syntax
  error で test が落ちるまで気づかない）。両側追加の file は
  `git show <upstream>:<path>` で upstream 版を取り直し、意図した差分（alias
  追加等）だけを手で足す。commit 前に
  `grep -rn '<<<<<<<\|>>>>>>>' <resolved-files>` を挟む。
- main が並行で動く repo では PR が着く前に conflict 化するのが常態。
  AGENTS 規則どおり rebase はせず **fresh branch から cherry-pick** で v2/v3
  を切り直す。cherry-pick 中の conflict 解消漏れは上の grep で拾う。
- 旧 PR は close 時に後継 PR への pointer を comment として残す（追跡可能性。
  複数 vN が飛ぶと何が正かが追えなくなる）。

## gateway 側: alias 追加の 3 点セット

新 model id・alias は **JS entry 側に置く**。routing core は
`src/local_murakumo/worker.cljk`（`model-context-windows` /
`resolve-endpoint` / `hosted-model-ids` の在り処）。worker.cljs は governed
artifact refresh 無しでは ship 不可（`package-release.mjs` が sourceDigest
不一致で fail closed）。`src/*.js` + `worker_entry.js` は deploy される半分
である。

1. **alias table `src/murakumo_tier_aliases.js`**（純 rewrite）。**存在を仮定しない — `git log --all --follow -- src/<file>.js` と `ls src/` の両方で確かめてから書く。実測: import 文と test だけ先に landed して module 本体が存在しないまま main に着地したことがある（deploy が import 時点で crash）。** module が無い場合は test ファイルが契約を規定しているので、test の全 assert をそのまま実装にする（tierAliases map / 非 alias は同一 object pass-through / 対象 path 限定 / invalid JSON pass-through / budget 前呼び出し / limits 行 pin）。**commit 前に `git status --porcelain -- src/` で untracked を確認する** — 新規 file は `git add` の対象に入りにくく、漏れると deploy が落ちる（実測 2 度目以降を防ぐ）。
2. **`model_token_limits.js`** DEFAULTS に concrete id の行を足す。UNKNOWN
default のまま出さない — admission が不正な窓/出力上限で clamps する。
3. **`src/worker_entry.js`**: 呼び出しは **1 箇所** — azure rewrite の直後、
   **`applyModelTokenBudget` の直前**に
   `request = await rewriteTierAliases(request);`（import も足す）。
   budget が body.model で token policy を引くので budget より後だと alias id
   が UNKNOWN default に落ちる。この 1 箇所の上流 rewrite で
   chat/completions・messages・responses の全分岐と budget・admission・
   短絡判定が全て解決後の id で通る — 分岐ごとの「先頭」に置く必要はない。
4. **テスト pin**（`test/murakumo_tier_aliases.test.mjs`）を書き
`package.json` の `test:origin-auth` に登録。⚠ pass-through 系の pin で
`new Request(url, {method: "GET", body: ...})` を書かない — undici は GET/HEAD
に body を許さず throw する。他経路 untouched の検証は POST /v1/embeddings で
行い、GET は単に返り request が同一であることだけを見る。
5. **concrete id は worker.cljk 側にも要る**: `model-context-windows` は
   ALLOWLIST — ここに無い id の応答は edge モデル名で返る。`resolve-endpoint`
   も同様。alias rewrite は routing に届く前に行われるので、rewrite 先
   concrete id の窓と経路は cljk 側で正として置く。

pin すべき 5 方向:
- mapping 正（alias → concrete id）
- 非 alias は **同一 object** で pass-through（書換の副作用ゼロ）
- 経路限定（POST chat/completions と messages のみ、他は untouched、
  invalid JSON は pass-through）
- 呼び出し箇所が **applyModelTokenBudget より前**（budget が body.model で
  policy を引くため。これより前なら admission / research / gad 短絡判定も
  全て解決後の id で判定される）
- limits 行の存在（regex pin。alias 行と target 行の同値も assert する）

## gateway 側: model id の退役（追加の逆方向）

id を「削除」の前に **write 経路を grep** する。その id を body に「書く」
経路がある id は公開表面ではなく **wire contract** なので保持する。判定基準:
「この id を読む者が、公開 caller だけか、それとも gateway 自身の routing /
probe か」。後者がいる id は消さず公開面から外す（wrangler.toml の
`MURAKUMO_MODELS` からは外す）。実例: b70 id。

- `body-for-origin` が main pool → b70 head へ振る際 body.model を
  `qwen3.8-27b-throughput-b70` に書き換える（b70 id は内部書換先として生存。
  公開面から外しても消さない）。
- `probe-b70` はノード側 /v1/models がこの id を返すこと + n_params 一致で
  identity 検証する — id を消すと正しいノードが「別モデル」判定になる。
- `with-model-request-defaults` の set から id を落とすと、内部書換 → この
  呼び出し、の順序のため enable_thinking 既定がその head で黙って効かなく
  なる。set は「公開 id」ではなく「body に書き得る id」で読む。
- 同一 id 文字列の第三の用途があり得る: `provider_catalog.cljk` の
  qualification-model（OpenRouter projection の棚 id, is_ready false）。
  **worker.cljk だけ grep して終わりにしない** — `src/local_murakumo/*.cljk`
  全体と test tree を grep する。

手順:

1. worker.cljk から除去する箇所: id 定義 / `hosted-model-ids` /
   `model-context-windows` / `resolve-endpoint` 分岐 / probe 関数と呼び出し /
   timeout の `(= model ...)` 分岐 / `record-model-generation-success!` の
   set / `inference-headers` の token 分岐と origin 判定 helper。
   退役理由は定義の跡地にコメントで残す（後で「なぜ無い」に答えられる）。
2. **/ready の `js/Promise.all` から probe を抜いたら `(aget values N)` の
   index を必ず振り直す。** 振り直し漏れは隣の probe の結果を別の capacity
   変数に読ませ、/ready は 200 のまま静かに嘘を列挙する。
3. 公開 caller の保護: 削除 id を `src/dead_hosted_model.js` の
   `deadHostedModels` に足して murakumo-main へ rewrite する（既存の
   fastmtp rewrite と同型。test の pass-through pin も書き換え側に直す）。
   これを忘れると旧 id を送る既存 caller が解決先の無い 503 に変わる。
4. `wrangler.toml` を同時 update: `MURAKUMO_MODELS`（advertise 面）から外し、
   死んだ `MURAKUMO_*_ENDPOINT` 変数を削除。コードだけ消して advertise に
   残すと「広告していて routing 残骸の表面」が /v1/models に残る。
5. readiness.cljk 側の汎用 helper（`runpod-*` 等の一般名）は readiness_test
   が直接使う — **worker 側の呼び出し箇所だけ消し、readiness.cljk の helper
   は残す**。helper 名は一般名であって退役 id に結びついていない。
6. **test tree は挙動でなく worker.cljk のソース行を regex pin している** —
   編集する行の literal を `grep -rn "<symbol>" test/` で先に引いて pin を
   同時に直す（例: chat_fallback_timeout.test.mjs の hosted-model-ids /
   context window の pin）。pin 直し漏れは一見無関係な test で落ちる。

## alias の構造的制約（守る理由）

- alias は **body.model の純 rewrite に限定**し、chat-completions と
  messages の両方で admission の前に 1 回呼ぶ。既存の
  admission / token budget / queue / routing を全て通すので、alias が
  target の能力を超えることは構造的に無い。
- **解決先の無い alias を出さない。** queue に model 登録が無い alias は
  503 を名前付きで返すだけになる。model 登録と同時に出す。
- 24GB tier はより良い quant（IQ3_S 11.8GB 等）に更新する余地がある。
  更新時は node 実測を取り直してから limits を変える（16GB mini の数値を
  写さない — Metal/Apple unified memory と Vulkan/AMD hybrid は別物）。

## gateway の生存確認は 3 面で読む（alias / id を出す前に）

- `GET /v1/models` — live pool 面。`is_ready` と `murakumo.capacity-members`
  （head/slots/ctx/decode 実測）を読む。ここに載っていても is_ready=false は
  routing 残骸。
- `GET /infer/models`（catalog）と `GET /infer/models/<id>` — catalog READ 面。
- 3 面は一致しない: alias が実応答していても解決先 concrete id が catalog 404
  のことがある（murakumo-main がそう）。**最終判断は
  `POST /v1/chat/completions` の実応答本文 + `x-murakumo-served-model`。**
  「解決先の無い alias を出さない」の解決先の有無はこの 3 面で測る。

## edge 側: queue ring への join

wrangler.toml `MURAKUMO_EDGE_MODELS_JSON` に id:output-cap 追加 + ノード側
llama-server に `--alias <その id>`。join worker（infer-join / poll_worker）の
heartbeat は local /v1/models の **id 一致**で ready を判定する — alias 無し
起動は ready=false のまま停滞する。

**1 worker = 1 model。** 複数 model は worker を `--name` 変えで分ける。

レシピ（ノード上で。詳細は `references/murakumo-tiers.md`）:

```bash
MURAKUMO_SERVICE_TOKEN=<join.env から> kbb --backend sci scripts/infer-join.cljk \
  --model <queue-model-id> --name <node名> \
  --local-url http://127.0.0.1:<port>/v1 --trust-tier community
```

流れ: POST /infer/nodes 登録 → heartbeat（local /v1/models probe）→
GET /infer/queue?model=&did= → claim 201 / 既 claim 409 で次へ →
local completion → POST /infer/queue/<id>/result。

認証は MURAKUMO_SERVICE_TOKEN（dan の `~/.murakumo/edge/join.env` に実績あり）。
CACAO の場合は --did に issuer DID 必須。恒久化: Linux は systemd unit
（Restart=always）、macOS は LaunchAgent（dan 先例: `~/.murakumo/edge/` に
runner + join.env）。

## ノード側の落とし穴

- **Vulkan iGPU は `--device` 明示でしか使われない。** `-ngl 99` だけでは
  log に Vulkan が現れず CPU 速度に落ちる。`--list-devices` で確認し unit に
  `--device Vulkan0` を書く。
- 重み DL 後は必ず **byte 数 + sha256 を infer.edn の pin と照合**
  （truncated-but-padded を byte 数だけでは拾えない）。
- GGUF の quant 名は「size budget」であって tensor 形式ではない（GSQ-RCO の
  IQ2_XS は 851 tensors 中 53 しか IQ2_XS でない）。
- 6600h は SSH root のみ許可（他 user は tailnet policy で拒否）。
- server restart ループ診断の順路は `references/murakumo-tiers.md` の
  dan 記録を参照（print+raise で握り潰しを分離 → safetensors header 直読で
  重み vs loader を切り分け → prune 済み fallback CDN は剥がす →
  watchdog cron は先に no-op 化）。
- **staging の削除は最後**: `git worktree remove` は modified/untracked が
  あると拒否される（compile staging や .shadow-cljs が掴まる）。成果物を
  commit してから worktree を外す。

## usagi worker（ling-3.0-tiny）— 永続的な知見

実測詳細（decode 実測、KV 計算、systemd unit 形、workload-fit 条件、本番
E2E 値）は `references/murakumo-tiers.md` の「ling-3.0-tiny（usagi）K16
replica 実測」節を正とする。SKILL.md に置く不変条件だけ:

- ling-3.0-tiny は reasoning-first（`reasoning_content` を吐いてから
  content）。小さい max_tokens だと全部 reasoning に食われて content が空に
  なる。token_limits は maxOutputTokens 1024。
- **gateway 側の advertise が無いと外部 POST は hang する**: `MURAKUMO_MODELS`
  と `MURAKUMO_EDGE_MODELS_JSON` の両方に id を足す。node 側 join と gateway
  advertise は 1 セット。検証は応答 `model` field で確認する。
- deploy 前でもノード側 local port の直叩きで挙動検証は可能。node→gateway へ
  の payload 転送は **base64 経由**が確実（ssh inline JSON は quoting が破損
  する）。
- **usagi の systemd unit は既存 join unit を鏡写しする**: llama unit
  （`--alias <queue-model-id>`、port 変え、`-ngl 99 --device Vulkan0`）+ join
  unit（`--model <id> --name <node名>-usagi --trust-tier awai-secure`、当該
  port の health を待つ ExecStartPre）。enrolled 201 → ring ready/live/fresh
  を /infer/nodes で確認してから gateway 側に進む。
- **「Ling-3.0-mini」等の新規 model id は murakumo registry / OpenRouter /
  HF の全てで在り処を確認してから足す**（実測: 存在しない id が chat endpoint
  で 200 を返したことがある — registry と応答 model field の両方で確認する）。
- **Refresh は 1 コマンドに集約済み**: `kbb --backend sci
  scripts/refresh-governed-artifact.cljk` が全手順（stage → prices_embed
  .cljc twin + resource staging → stale cache clean → shadow release → copy →
  digest refresh → package-release 検証）を順に実行し byte-identical 再実行を
  実測。README も更新済み。bundle の JVM-free 化は未解決（amu 側に測定
  マトリクス付きで起票済み — amu esm target 未実装、amu cljs target は
  kotoba subset のみで js interop 拒否、kbb engine bundle は loadString
  wrapper で sibling ns を disk から解決するため workerd 不可、engine closure
  の toString は $APP/var 依存で単独不可能）。
- **bot fallback への寄せは workload fit で選ぶ**: fallback に ling-3.0-tiny
  を指定すると実際に選ばれるが、**window を超える session は
  「Context length exceeded」で死ぬ**。scout/crawl 系（小 session）は適合、
  compression/aux や長文 crawl は寄せない。rollout は job 単位で workload fit
  を見て選ぶ（agent.log の model= 行と tokens 数で判定）。失敗したら fallback
  を元に戻してから次へ。
- **window はモデルの上限ではなく llama-server の `-c` 起動 flag**: モデル本来
  の ceiling は HF config の `max_position_embeddings` で確認する。KV cache は
  `2×n_kv_heads×head_dim×n_layers×2` bytes/token から算出、`--cache-type-k/v
  q8_0` で半分。window を上げたら **node 上で実応答 probe（失敗 case より大きい
  token 数の needle 探し）を通してから gateway face を動かす**。
- **Hermes profile の fallback 変更は hand-edit せず `HERMES_HOME=<profile>
  hermes config set fallback_providers '<edn>'` で行う**（bare `hermes` は PATH
  に無い — venv 内 binary を使う）。反映確認は config.yaml の
  fallback_providers 節を grep。`hermes cron run <id> --accept-hooks` は
  dispatch で返らず job 完了まで block する — 進行は agent.log の `model=` 行
  と jobs.json の last_status で読む。
- **usagi window は 65536（q8_0 KV）に引き上げ済み**: 8192 で compression session が死んだのを 32768 で直し、Hermes の 64K init gate が 32768 を拒むのを 65536 で直した（node probe 実測 41,936-token 通過）。**窓の正は node unit の `--ctx-size` のみ** — gateway 3 面（worker.cljk model-context-windows / model_token_limits.js DEFAULTS / model_registry.cljk seed の `:context`）と KV descriptor は写しで、deploy 前の写しが旧値のままでも node 側は正しく動く（逆に node probe 通過だけでは caller は死ぬ — 3 段階障害の順路は murakumo-tiers.md を読む）。**KV descriptor は operator PUT が seed に勝つ** ので、seed を変えたら KV descriptor も同じ値で PUT する（実測: PUT なしだと live catalog が旧値を返し続けた）。rollout 完了: 402 profiles 30 件 + default の fallback を murakomo/ling-3.0-tiny に設定。⚠ 「murakomo/usagi」の typo は UNKNOWN 16384 で正しく拒否される（silent-rewrite fix は未着地 — usagi で失敗したらまず typo を疑う）。
- **窓変更は個別面の手編集でやらない（ADR-2609131642）**: 正（node unit sed + restart）→ node 上で旧値超え needle probe（fail なら全後続中止）→ KV PUT → gateway 3 面は worktree/PR（seed の窓値を忘れない — `grep -rn 'ling-3.0-tiny\|32768' test/` で隣の pin から発見できる）→ Hermes cache 清掃、の順で 1 本の伝播にする。乖離の常時監視は fleet-model-watch の `usagi_window` ledger field（node 実値 vs gateway 実 fetch、node 到達不能は UNMEASURED を返す — 一致を偽装しない）。
- **⚠ routes.cljk は pure/test 面のみ**。live gateway の `/infer/models` は
  **worker.cljk 独自の face**（list: `(:list kx) "infer.models"`、per-id
  descriptor face もある）— seed merge は **両方**に足すこと。routes のみ
  変えて gateway が 404 のままになった実測あり。
- **worker.cljk の per-id fallback は `js/Promise.resolve` で包む** — `(or m
  seed)` を素で .then に渡すと promise でない値で chain が断てる（実測: 500
  "(intermediate value).then is not a function"）。
- **bundle 再生成の JVM-free 化は未解決**: amu esm target 未実装（exit 64）、
  amu cljs target は kotoba subset のみ（js interop 拒否）、kbb engine bundle
  は loadString wrapper（named exports 無し、自己完結しない）。engine lib は
  2.5MB / node: require 1 file — workerd nodejs_compat で動く可能性あり。
  提案 P1 = engine 評価結果の closure を named export 化した自己完結 ESM。
  着手は amu 側の仕事。
- **owner 指示: artifact refresh に JVM を依存させない**（refresh の shadow-cljs step は JVM）。.cljk を触らなくて済む形を先に設計する: 数値・表は **env / KV 由来の JS 面に置く**（`MURAKUMO_MODEL_TOKEN_LIMITS_JSON` の operator override と同型。`.js` と wrangler.toml は sourceDigest 対象外 = refresh 不要で deploy 可）。.cljk 変更が merge 済みでも refresh できない間は **KV PUT が catalog 面 (④) の唯一の ship 経路**（/v1/models と admission は bundle 依存のまま残る — Hermes init gate は通らない）。**bundle を手で書き換えない** — package-release の sourceDigest 照合が fail-closed で落とす。
- **sourceDigest は `*.js` と wrangler.toml を含まない**（deps.edn /
  shadow-cljs.edn / `*.clj[csk]` のみ）— **JS 面のみ・env のみの変更は refresh
  不要で deploy 可能**（package-release が unchanged digest で exit 0 になる
  ことで実測確認してから commit する）。model selection からの除去（advertise
  gate + external env を空）はこの経路で着地した。
- **実応答検証は wrangler dev で entry chain ごと**（dist/worker.js 単体 grep
  では足りない — tier alias は worker_entry.js 側で wrap されるため bundle に
  現れない）。本番反映後は `POST /v1/chat/completions` model=<alias> の応答
  `model` field と finish/content で測る。

## モデル選択からの除去（advertise gate パターン）

owner が「/v1/models の選択肢から外して」と言うとき、**routing capability と advertise は分ける**: DEFAULTS エントリ削除は routing も消し、merge-over-defaults の override 語彙も壊す（実測 7 tests fail）。正しい形は:

- **外部 relay id は `MURAKUMO_EXTERNAL_MODELS` env を空にする**（advertise 面のみ。relay 自体は直接指定で変わらず回答する）。prices も `{}` に。
- **JS 側で自動追加される面（modalHostedModels → augmentModalHostedModels）は advertise gate を足す**: env flag（例 `MURAKUMO_MODAL_ADVERTISE="true"`）が無ければ response をそのまま返す — 既定 OFF が owner の意図なら gate の既定を OFF に。routing table は生きる。
- test は「既定 OFF の新 test（選択肢に載らない + routing table は知っている）」と「advertise-on の成長 test」の 2 本を足す。
- **`.js` と wrangler.toml は sourceDigest 対象外**なのでこの種の変更は refresh なしで deploy まで回る（実測確認済み）。
- **hidden-state な除去バグに注意**: 対象 id が複数の面から /v1/models に乗る場合（external env / modal augment / hosted env / provider decorate）、除去後に **live `/v1/models` を実 fetch して全 id が消えたことを確認する**（grep ではなく）。残った面が他に在ることを grep だけでは拾い切れない。

## catalog を code-managed にする（seed merge パターン）

- **catalog の face は 2 箇所ある**: `routes.cljk`（純粋層 — nbb test のみが通す）と
  `worker.cljk` 本体（live gateway が `(:list kx) "infer.models"` を直読する面）。
  **routes.cljk だけ直すと test は全緑のまま gateway は旧挙動の bundle を ship する。**
  着手前に live face の在り処を worker.cljk 側で grep して特定する
  （`grep -n '"infer.models"' src/local_murakumo/worker.cljk`）し、seed merge は両方に足す。
  per-id descriptor face も同様（worker.cljk の `no such model` 応答箇所）。

KV store 直読の READ 面（routes.cljk が `st/get store "infer.models"` する形）を
code 管理にする標準形:

- **seed は `.cljk` ns の `def` で持つ**（bundled cljs に載る。実行時 fs read の
  seed .edn は bundle に入らない）。live 応答を verbatim 取り込み + 新規 id を
  来歴（測定値、sha256、注意点）付きで足す。
- **merge 規則: KV（operator PUT）を per-id で勝たせ、seed を下敷きに、`:id`
  で sort、malformed seed は throw**（fail-closed — 空の catalog を pass と同じ
  にしない）。
- **seed 化で「壊れていた path 処理」が露見する**: `segments()` が blank segment
  を落とすため `/infer/models//x` が flat id `x` として届く。KV が空なら 404 で
  隠れていたが、seed が id を持つと 200 になる。**raw path の `//` を
  clause guard で明示的に落とす**（blank guard だけでは足りない — id 自体は
  non-blank だから）。
- **既存 test の「空 store → 404」契約は seed 導入で壊れる** — 契約変更を
  同一 commit で test 側に書く（seed 由来 id が 200 になる新しい正）。
- test runner に新 ns を登録する時は **`require` block と `test-namespaces`
  def の両方**（片方だけだと require は通るが run-tests に乗らず、Testing 行が
  出ない — 出力で実測確認すること）。
- **KV は deploy なしの ship 経路になる**: catalog READ face が KV store を直読
  する repo では、operator PUT 1 件で seed 待ちの変更を live に先行表示できる
  （descriptor は seed と同内容にする — refresh 着地後は seed 由来が引き継ぐ）。
  token はリポジトリ外の box から直接 Bearer に使い、値を転送しない（box 上で
  curl 実行、スクリプト/JSON は base64 経由で転送し実行後削除）。

## 簡素化規則（owner 指示）

経路が複雑化したら層を足すのではなく**既存経路への純 rewrite で収める**。
既存 murakumo-main pool と basho toggle は bot fleet が依存しているので
触らない。alias 追加は既存経路に一切触らず層を足すだけの形。

## RSIAgent を ao/basho profile へ反映 (2026-09-17 着地)

arXiv:2609.15364 (RSIAgent) は GLM-5.3=actor / Kimi-K3=verifier+curriculum の
training-free draft/rever。実 repo の config/roles/*.yaml が正 (公開)。
論文の数値 (OSWorld-v2 78.98 等) は harness 結果で、自 fleet では未再現 —
引用時に「自分の測定」と読ませない。

- owner 選択: **member 変更なし・profile 文だけ更新**。intended draft
  (awai-network/basho) は 402 identity gate / insufficient credits で member call
  不可 (09-16, 09-17 両方実測)。repoint は identity 前提が解決してから。
- ao profile 更新の定石: src/ao_organism.js の profile.system / profile.reviewer
  を書く (tests は system 長さと接頭辞だけ pin)。JS-only なので governed refresh
  不要で deploy 可能。
- **deploy blocker の実例: orphaned provisioned DO namespace**。79fdfa7 の DO→R2
  移行 (ADR-2609132007) が NetworkQueue class を code から消しただけで、provisioned
  namespace が残り、全 deploy が `orphaned_provisioned_namespace` で fail closed。
  修復は wrangler.toml に `[exports.NetworkQueue] type="durable-object" state="deleted"`
  の tombstone (merge 0770e05)。**class を code から消す移行は tombstone を同一
  PR で足す**のが教訓。
- deploy 手順実績: worktree (network-awai/main) → npm i → npm run build →
  npx wrangler deploy → live POST /v1/chat/completions model=ao/basho で 200 +
  応答本文確認 → west-pin-put (⚠ 409 west.yml moved が出たら 1 回 rerun)。

## 残課題の常駐化（2026-09-14、owner 指示「それぞれ bot profile に任せる」）

残課題を既存 shell profile に割り付け、no_agent script job として常駐させた。全て
profile-scope cron・実測 ledger 付き・propose-only:

| 残課題 | profile | job | 頻度 | ledger |
|---|---|---|---|---|
| ao review 分岐の live 実測（trigger/adoption 率） | `usagi-verify` | `ao-review-probe`（bcacfbf2cf4b） | 2h（23分） | `workspace/ao-review-ledger.jsonl` |
| 動画 delegate 計測（seam が空である事の継続観測） | `murakumo-eng` | `video-delegate-probe`（065d205b4efa） | 日次 06:41 | `workspace/video-delegate-ledger.jsonl` |
| murakumo-main 直指定残存（profile residue / advertise / routing 生存） | `murakumo-qa` | `direct-call-residue`（c329209d873f） | 日次 07:17 | `workspace/direct-call-residue-ledger.jsonl` |

実測ベースライン（2026-09-14）: ao/kame near_limit = trigger ✓/adopt ✓。
ao/usagi near_limit = **502 再現**（"organism members did not answer" — draft が cap に
達した後の review 経路で落ちる。cloud-murakumo-api 側の修正対象、bot は観測のみ）。
動画 delegate = absent（seam 閉が正当値）。profile residue = 0、direct call = 200 生存。

usagi-verify は SOUL.md に field 語義（`review_triggered` = 呼び出された、`reviewed` = 採用された）を
書いてある — 採用率（triggered=true reviewed=false の割合）がボットの注視信号。

## ⚠ fleet-wide blocker: kotobase-peer @8bc6cbf paren imbalance（2026-09-14 実測）

`orgs/kotoba-lang/kotobase-peer` `src/kotobase_peer/object_store/worker.cljk` の B2 Phase A
（306d34d + 8bc6cbf）は top-level form が複数破損。reader 実測:

- 全 file は parse "する"（imbalance が互いに相殺）が **form 境界が間違い**: `defn cancel-resumable-execution!`（2606 行）が EOF まで後続 defn を呑む（`EOF while reading, expected ) to match ( at [2606,1]`）
- 分離実測: claim 領域は `Unmatched delimiter: ]`、cancel は 1 close 不足、advance/finish も内部 imbalance
- **f3ffa30（B2 Phase A 直前）は完全に parse OK** —— 破損は Phase A だけ
- worktree `~/worktrees/peer-b2-transport` が `8bc6cbf [main]` で **並行 session が在宅** —— 手を付けず報告のみ。直すならその session の branch で

**判別手順（他 repo で require が EOF/Unmatched で落ちたら）**: edamame で
`parse-string-all {:all true :read-cond :allow}` を prefix 逐步に回す。
`UNMATCHED` = 実 imbalance、`EOF-TRUNC` = 単なる prefix 切断。defn 単位の
chunk 分離で最初に落ちる defn が犯人。tokenizer は regex/`#_`/文字リテラルで
誤算するので edamame（実 reader 系）で確定する。

## CUA (computer-use) model 候補 — 調査 2026-09-14（未着地、propose-only）

itonami.cloud / murakumo の画面操作を担う VLM を ≤32GB で運用する候補調査（スコアは
第三者・自己申告ベンチ、自前実測は別物として扱う）:

| 候補 | OSWorld-Verified | ≤32GB 適合 | weights |
|---|---|---|---|
| **Holo3.1-35B-A3B**（35B MoE / 3B active、Qwen3.5 base） | 74.2%（BF16 自己申告、quant で ~-2pt） | ✅ 公式 Q4 GGUF ~12GB + mmproj 858MB（APEX 系は I-Mini ~13GB / Compact 17GB） | 公式 GGUF 在り（Hcompany/Holo-3.1-35B-A3B-GGUF） |
| **UI-Mate-27B**（27B dense、Qwen3.6 base） | 77.0% open-weight SOTA | ✅ bartowski GGUF 実在実測 (HF API 2026-09-14): IQ4_XS 15.33GB / Q4_K_M 17.53GB / IQ2_M 10.6GB + mmproj 0.93GB | HF Apache-2.0 — **UI-Mate-27B IQ4_XS が CUA 本命に昇格**（Holo3.1 公式 Q4_K_M 21.3GB を上回る: スコア高く・小さい）。UI-Mate-9B GGUF (bartowski) も在り |
| GUI-Owl-1.5-32B | 56.5%（Instruct） | ❌ BF16 のみ | HF 在り |
| Qwen-UI-Agent 27B/35B-A3B | 79.5% | — | **weights 非公開**（MAI-UI 8B/2B のみ）→ 除外 |

本命は **UI-Mate-27B IQ4_XS**（Holo3.1 公式 Q4_K_M 21.3GB を上回る: スコア高く・小さい）。
**node 実測 2026-09-14 完了**（詳細は itonami workspace `cua-uimate-node-probe-20260914.md`）:

- **gad**（Ryzen AI Max+ 395 / 8060S iGPU, 47.8GB RAM）✅ 実測成功 — llama.cpp b9873
  Vulkan build, IQ4_XS 15.3GB + mmproj 0.9GB, qwen3.8-27b(8090)+bge-m3(8091) と同居、
  VRAM used 37.9/51.5GB。GUI step JSON 生成 **gen 8.0〜10.1 tok/s、1 step 7〜13s**。
  出力は指示どおり strict JSON（thought+action+target）。chat completion 200、
  sha256 pin: IQ4_XS `109f07983d…200c` / mmproj `991376d8…4fbb`。
- **xavier**（Jetson Orin 32GB UMA）❌ OOM 5 回で測定不能 — qwen3.8 常駐 12.5GB 統一メモリ
  と ui-mate 15.3GB が排他。systemd unit が Restart=always で即復帰、sudo 不可のため
  unit stop できず。**xavier で cua を運用するには qwen3.8 と排他（operator sudo で unit stop）**。
- 接続案: alias `murakumo/cua`（性格名は owner 語彙で決める）→ concrete `ui-mate-27b-iq4xs`
  on gad :8093。未測定: 実スクリーンショット（mmproj 画像入力）step latency、ctx 8192 の VRAM 定量。
⚠ 事前 gate の残り: ②mmproj が llama.cpp build で screenshot 入力できること ③itonami.cloud 実画面での click 坐標 mini probe。
ao 接続は `ao_organism.js` の delegate seam（現在 empty・未計測のため広告しない）に
cua member を入れる形 — delegate probe 実測後に広告。性格名は owner 語彙で決める
（kame/usagi の次。提案時は決め打ちしない）。

## ao（Artificial Organism）tier — 2026-09-14 着地済み

基本の呼び出し対象は concrete model ではなく **ao id**（`src/ao_organism.js`）。
且つ **model 省略/空の chat call の default は ao/kame**（entry が budget 前に
rewrite、`/v1/chat/completions` のみ。messages/responses は cljk 側の自分の
default を保持）。MURAKUMO_MODELS からは murakumo-main を除去したが
**routing は生存**（dead-model rewrite 先・b70 toggle 基底・owned pool 経路 —
advertise 除去と routing 除去は別物）。draft→review の 2 段協調で、同一
`POST /v1/chat/completions` と同一 origin-auth admission を通る（entry intercept
順は test pin）。`GET /v1/models` は `ao_models.js` で広告する。既存: ao/usagi
（ling-3.0-tiny draft + murakumo-main review）/ ao/kame（pool draft + ling
review）。画像/動画 delegate は未計測のため member table の empty seam のまま
（広告しない）。

- **verify 実測**: live 省略 call → `model: ao/kame` 200。直指定
  murakumo-main も 200（routing 生存の確認）。bundle grep は効かない — 実応答で測る。
- **stream:true は非 stream JSON で返る**（member 呼び出し stream:false 強制、
  organism は完成答を組み立てる設計）— 既知仕様、caller 側で受容。
- **⚠ kotoba-lang/edn は現 main の全 sha が JVM 読めない**: ns 外の top-level
  `(:export …)` は Clojure が `export` 未束縛関数として評価し、ns attr-map
  `{:kotoba/export …}` 形は attr-map を code 位置で評価してシンボル解決で死ぬ。
  対処は `scripts/stage_shadow_build.py` の `bridge_edn_for_jvm()` — gitlibs
  copy から export 節を剥いだ edn-bridge を stage :paths 先頭に置く（ upstream
  が直したら bridge は fail loud で外す）。nbb/kbb は両形式とも読める。
- **stage sibling source は env override 可**（`MURAKUMO_STAGE_SRC` 等）: 共有
  orgs/ checkout が dirty でも worktree は clean west-pin worktree を向けて
  compile できる。未 commit の sibling 変更（`(:export)` 追加、paren 不均衡）が
  全 repo の cljs suite を壊す —「無い」と結論する前に sibling の dirty 状態を
  `git status` で確認する。
- **Hermes profile の一括 model 切替は backup 先作り → 置換 → YAML 全件 parse
  検証の 3 ステップ**: 2026-09-14 の ao/kame 切替は 259 profile に対し
  `model:/default: murakumo-main` → `ao/kame` + 直後 block の
  `context_length: 262144` → `65536`（ao の token row は min-members）を同時
  置換。openrouter 等 murakumo 以外の provider 行は触らない。

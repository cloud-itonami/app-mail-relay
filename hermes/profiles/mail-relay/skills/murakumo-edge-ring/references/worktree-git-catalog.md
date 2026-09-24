# worktree / git / catalog 操作 — murakumo gateway 作業の実細

## worktree 操作は repo 自身から行う

`git worktree add -b jk/<topic> /tmp/wt-<topic> network-awai/main` のような
remote 名付き参照は **superproject 側からは解決しない**（remote 設定は子 repo
のもの）。初回は子 repo 内で `git fetch network-awai main` してから worktree を
切る。この repo に `origin` は無い。

**worktree 操作・編集・test は全て子 repo に入ってから行う。** superproject
ルートから出すと path が superproject 相対で解決され、「worktree 内の path が
読めない」症状に見える。

**worktree の場所は superproject 構造に合わせる（.cljk repo では必須）。**
deps.edn が `:local/root "../../kotoba-lang/..."` や `:paths "../<sibling>/src"`
で sibling を相対参照する repo を /tmp に切ると classpath が全滅する
（`Error building classpath. Local lib ... not found`）。/tmp に作ったら
`git worktree move` で orgs/<org>-worktrees/<name>/ 等の正規位置へ移し、
`:paths` 相対参照の sibling には worktree 親ディレクトリから symlink を張る
（`../cloud-murakumo` → 本体 checkout）。sibling の中身をコピーしない —
west sibling は本体 checkout を参照させる。

**`nbb.edn` の `:paths` 相対参照は kbb にも同じ影響が出る（別症状）**: /tmp の
worktree で `npm run test:cljs` を回すと `Could not find namespace:
murakumo.kotoba.oracle` になる（west 相対 layout が解決できない）。test を
回す前に正規位置へ worktree を切ってそちらで実行する。compile staging 用の
`MURAKUMO_RESOURCES_DIR` / sibling `resources/` も同様 — /tmp worktree では
staging 前段の stage_shadow_build.py が sibling dir 不在で fail する。

**sourceDigest の対象は `deps.edn` / `shadow-cljs.edn` / `src/*.clj[csk]` のみ**
（package-release.mjs の filter 実装）。**`src/*.js` と `wrangler.toml` は対象
外** — この 2 系統のみの変更（JS 面の routing、advertise env）は governed
artifact refresh 無しで package-release が unchanged digest で exit 0 になる。
反対に `.clj[csk]` を 1 文字でも触ったら refresh 必須 — package-release が
fail closed で教えてくれるので、確認のために一度実行して exit code を見る。

## tier alias 実装の実細（SKILL.md の手順の裏付け）

- 呼び出しは 1 箇所: `src/worker_entry.js` の azure rewrite 直後、
  `applyModelTokenBudget` の直前に 1 行。分岐ごとに置かない — 上流 1 行で
  budget・admission・research・gad 短絡の全経路が解決後 id で通る。- test の pass-through pin で `new Request(url, {method: "GET", body: ...})`
 を書かない — undici は GET/HEAD に body を許さず throw する。他経路
  untouched の検証は POST /v1/embeddings で行い、GET は「同一 request が
  そのまま返る」ことだけを見る。
- DEFAULTS の alias 行は「rewrite が走らなかった経路」の防御。alias 行と
  target 行を同値に保ち、test で同値を pin する。
- concrete id は worker.cljk 側にも要る: `model-context-windows` は
  ALLOWLIST（無い id は edge 名で返る）、`resolve-endpoint` も同様。

## model id の退役（追加の逆方向）

id を「削除」の前に **write 経路を grep** する。gateway 自身が body に「書く」
id（`body-for-origin` の内部書換先、`probe-*` の identity 検証先）は wire
contract なので消さず、公開面（`MURAKUMO_MODELS` / catalog）から外す。
`with-model-request-defaults` の set から落とすと内部書換→既定付与の順序が
崩れ、enable_thinking 既定がその head で黙って効かなくなる — set は「公開 id」
でなく「body に書き得る id」で読む。同一 id 文字列の第三用途
（provider_catalog の qualification-model 等）があるので
`src/local_murakumo/*.cljk` 全体と test tree を grep する。

削除した id を `deadHostedModels`（dead_hosted_model.js）に足して
murakumo-main へ rewrite する — これを忘れると旧 id を送る既存 caller が
解決先の無い 503 に変わる。`DEDICATED_HOSTED_MODELS`
（gad_private_fetch.js）からも抜くが、wire contract の id は残す。

/ready の `js/Promise.all` から probe を抜いたら `(aget values N)` の index
を必ず振り直す — 振り直し漏れは隣の probe の結果を別の capacity 変数に
読ませ、/ready は 200 のまま静かに嘘を列挙する。readiness.cljk の汎用 helper
（`runpod-*` 等の一般名）は readiness_test が直接使うので残す。

test tree は挙動でなく worker.cljk のソース行を regex pin している — 編集する
行の literal を `grep -rn "<symbol>" test/` で先に引いて pin を同時に直す。
pin 直し漏れは一見無関係な test で落ちる。

token budget の overflow 応答は窓の数値を含める
（"maximum context length is N tokens"）— Hermes 等 caller は 4xx 本文から
N を parse して採用する。N 無しの拒否は caller に窓を更新させられない。

tier alias 実装の env モデル:

- `MURAKUMO_MODEL_TOKEN_LIMITS_JSON`（operator override）は DEFAULTS より優先。
- UNKNOWN default（`contextWindow 16384 / maxOutputTokens 4096`）に落ちた id は
  admission が窓/出力を勝手に細める — 新 id は DEFAULTS 行を必ず足す。

## catalog 面の 3 読み（id/alias を出す前の生存確認）

1. `GET /v1/models` — live pool 面。`is_ready` と
   `murakumo.capacity-members`（head/slots/ctx/decode 実測）を読む。
   `is_ready=false` は routing 残骸。
2. `GET /infer/models` と `GET /infer/models/<id>` — catalog READ 面。
   `/v1/models` に在っても catalog 404 の id がある（登録漏れか残骸か）。
3. `POST /v1/chat/completions` の実応答 + `x-murakumo-served-model` —
   最終判断。alias が解決していても target が catalog 404 のことがある。

## merge・deploy 前後の check 顺序（新規 file 漏れを deploy で落とさない）

- **commit 前に `git status --porcelain -- src/` で untracked を確認する**。
  新規 file は `git add` の対象に入りにくく、import 文だけが landed して
  module 本体が欠けたまま merge すると wrangler deploy が import 解決で落ちる。
- **PR merge 後に統合 checkout を FF 同期してから digest を取り直す**: merge
  が main 側の別 commit を含むと digest 対象の src 内容が branch 時点から変わ
  るため、branch で出した manifest の digest は必ず古い。落ちたら
  refresh-release-artifact → release commit → deploy の順（正常手順）。
- deploy 後の live verify は「新 alias の 200」と「retire した id の /v1/models
  消失」の 2 点。retire id への request は待ち行列に入るため timeout で観測
  され、死んだ id が生きているように見える — 消失確認の方が完全性の判定になる。

## cleanup の順序

1. 成果物が全て commit・merge 済みであることを確認してから worktree を外す
   （`git worktree remove` は modified/untracked があると拒否される — staging
   や .shadow-cljs が掴まる）。worktree remove → branch -d の順。
2. worktree 親ディレクトリに張った `:paths` 用 symlink は撤去する（rm 1 本
   ずつの小さな操作として。一括 destructive command は user 同意を block され
   るので分割して出す）。

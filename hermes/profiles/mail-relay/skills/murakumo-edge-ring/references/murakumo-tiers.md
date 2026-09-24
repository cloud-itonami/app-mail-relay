# murakumo サイズ tier alias と edge ring — 実測詳細

SKILL.md 本体のルールの裏付けと手順。実測値は測定日を書いてあるものだけ信じる。
時系列比較に使えるのは server 側 timings のみ（decode =
timings.predicted_per_second、prefill = timings.prompt_per_second、非
streaming 応答本文から読む）。wall-clock end-to-end aggregate tok/s は
prefill を混ぜるので prompt 依存 — どちらの端点も正直に測っていても異日比較は無意味。

## cleanup 設計（owner 指示: load 中以外のモデルを残さない）

- **serve していない GGUF / HF cache は削除する。** 対象判定は「現行 unit の
  ExecStart が参照している GGUF」と「過去 N 日に成功応答した log のある GGUF」
  以外。削除前に `du -sh` と sha256 を log に残す（HF から再取得できるものは
  sha256 が復元鍵になる）。
- 削除と同時に serve 設定も剥がす: 削ったモデルを参照する DEFAULT_MODEL /
  plist / unit は同時に更新する。cache だけ消すと次回起動が再取得 hang に
  変わる。
- dan 実測インベントリ: gemma-4 cache 3.3GB（prune 対象・DEFAULT_MODEL を
  変えるか murakumo-serve を退役させる）、comfyui 23GB（画像 ring・残す）、
  ollama は不在。/tmp の検証用 safetensors は検証後削除（GB 級の輸送を
  /tmp に残さない）。

## tier → model mapping（owner 方針確定済み）

| alias | 実体 id | 裏付け |
|---|---|---|
| murakumo/16gb-kame | murakumo-27b（Qwen3.8-27B GSQ IQ2_XS 8.4GB） | judah 実測 6.3-7.8 tok/s、ctx 8192、out 1024 |
| murakumo/24gb-kame | murakumo-27b-6600h（同 GGUF を 6600h で serve） | 6600h 実測 decode 2.77 tok/s（Ling 2 プロセス共存下）。24GB では IQ3_S（11.8GB）への更新候補あり — 更新時は node 実測を取り直してから limits を変える |
| murakumo/24gb-usagi・32gb-usagi | ling-3.0-tiny | 6600h 実測 ~25 tok/s |
| murakumo/32gb-kame | qwen3.8-27b（gad Q4_K_M、main pool） | 既存経路、rewrite 先が既に生きている |
| murakumo/16gb-usagi | 意図的に未定義 | queue に 2 台目 model が無い。解決先の無い alias は 503 を名前付きで返すだけ — model 登録と同時に出す |
| murakomo/usagi | ling-3.0-tiny | K16（aiueos-node-260910-usagi）ring join 済み。gateway tier_aliases.js に登録。`murakumo/16gb-usagi` とは別系統 — 前綴りなしの素 usagi は K16-class replica。gateway /v1/chat/completions で model=murakomo/usagi が解決する |

## ling-3.0-tiny（usagi）K16 replica 実測

- unit: `murakumo-edge-usagi-llama.service`（Ling-3.0-tiny-Q4_K_M.gguf、`--alias ling-3.0-tiny`、port 8096、`-ngl 99 --device Vulkan0`、**ctx 65536 + `--cache-type-k/v q8_0`** — 窓は llama-server `-c` 起動 flag であってモデル上限ではない。モデル本来の ceiling は 131072（HF config `max_position_embeddings`）。KV は 192 KiB/token（f16）で `2×n_kv_heads(16)×head_dim(128)×n_layers(24)×2 bytes` から算出、q8_0 で半分（~96 KiB/token）— 65536 ≈ 6 GB + weights 4.8 GB で K16（26GB、Ornith worker 共存）の安全床。131072 は KV 12.3 GB で共存解除後のみ再評価。**window 変更時は node 上で旧値を超える実応答 probe（needle 探し）を通してから gateway face を動かす**。65536 引き上げ時の probe 実測: 41,936-token prompt → finish=stop、needle 正答、wall 131.8s（cached_tokens 20,420）。SSH host は tailscale `aiueos-node-260910`（100.66.205.17、~/.ssh/murakumo 鍵、root）。窓の正はこの unit の `--ctx-size` のみで、gateway 2 面 + KV descriptor + Hermes cache は写し — 1 正 6 写しの伝播規則は root ADR-2609131642。）。+ `murakumo-edge-usagi-join.service`（`--model ling-3.0-tiny --name <node名>-usagi --trust-tier awai-secure`、8096 health 待ち）。enrolled 201 → /infer/nodes で ready/live/fresh を確認してから次に進む。
- 重み sha256 `246d67d45f5b...d7be9`（4,823,894,944 bytes、既存 murakumo-edge dir の copy を流用 — sha256 を先に取り一致したら DL しない）。
- decode 40.8 tok/s（server timings、wall 13.2s — queue+prefill 込み）、tool call OK（finish=tool_calls）。**reasoning-first**: `reasoning_content` を吐いてから content — 小さい max_tokens だと全部 reasoning に食われて content が空。token_limits は maxOutputTokens 1024。
- 「Ling-3.0-mini」なるモデルは murakumo registry / OpenRouter / HF のどこにも存在しない（実測）。旧世代は Ling-mini-2.0（6600h disk のみ、node は offline）。新規 model id を murakumo に足す前に `/infer/models/<id>` で在り処を確認する。
- KV PUT（operator token、box 上で Bearer 使用・平文転送しない）で descriptor を live catalog に先行表示できる — worker.js refresh 待ちの間の interim ship 経路。JVM 依存しない owner 制約下では、refresh が止まっている間これが catalog 面 (④) の唯一の ship 経路だが、/v1/models (②) と admission (③) は bundle 依存のまま — 全 caller を救うには値を env/KV 由来の JS 面に移す構造変更（refresh-free deploy）が本命。
- **Hermes profile の fallback に usagi を使う条件**: fallback を ling-3.0-tiny にすると agent.log に「Auxiliary auto-detect: using main provider murakumo (ling-3.0-tiny)」が出て実際に選ばれる。⚠ **window を超える session は「Context length exceeded」で死ぬ**（実測: 14,352 token の aux session vs 8192 window）— scout/crawl 系（小 session）は適合、compression/aux や長文 crawl は usagi に寄せない。rollout は job 単位で workload fit を見て選ぶ。失敗したら fallback を元に戻す。fallback 変更は hand-edit せず `HERMES_HOME=<profile> hermes config set fallback_providers '<edn>'`（hermes binary は venv 内 — bare `hermes` は PATH に無い）。`hermes cron run <id> --accept-hooks` は dispatch で返らず job 完了まで block する — 進行は agent.log の `model=` 行と jobs.json の last_status で読む。
- **node の窓を上げても caller は 3 段階で別々に死ぬ**（窓引き上げの完了条件を広げる）: gateway 3 面（worker.cljk model-context-windows / model_token_limits.js DEFAULTS / model_registry.cljk seed の `:context`）が node の `-c` より小さいと、① Hermes init gate（/v1/models の context_window < 64k なら落ちる）→ ② auxiliary gate（compression も主 model と別々に走る）→ ③ 実 request の 400（admission が character-class 推定で旧窓を拒む）の順に露出する。**node probe 通過だけでは終わりでない**。窓値の正は node unit の `--ctx-size` のみで、gateway 2 面 + KV descriptor + Hermes cache は写し — 1 正 6 写しの伝播規則と乖離監視（fleet-model-watch の usagi_window 検査）は root ADR-2609131642。明示宣言する Hermes profile は `model.context_length` と `auxiliary.compression.context_length` の **2 箇所**、加えて `<profile>/context_length_cache.yaml` が server 報告値を cache する（key `<model>@<base_url>`）— **server 値を上げたら cache file を消してから再検証する**。検証の完了条件は scratch profile（`.env` に MURAKUMO_API_KEY を複写、`HERMES_HOME=<profile> <venv>/bin/hermes chat -q ...`）での実走 — 裸 curl の 200 は init gate を通らない。
- **probe と 400 本文の数値の読み方**: needle probe が旧窓を超えたかの判定は**応答 `usage.prompt_tokens` で行う**（filler の bytes/3.3 推定は実測より ~9% 低く出て、旧窓超えを取りこぼす — 実測: est 20,019 → actual 20,936）。gateway admission 400 の "reserves N input tokens (character-class estimate)" は Hermes の実 token 数の **~2.8 倍**を出す（JSON 全体への文字種推定）— この N を session サイズとして読まない。どちらも「推定で打たず、実測 usage で判定する」が本体。

## main pool（murakumo-main）— 3 head

`murakumo-main` alias は b70 slot（`qwen3.8-27b-throughput-b70`）に repoint
済み（API 実読）。bot fleet は `murakumo-main` を pin。capacity-members 実測
（slots = 実 slot 数、ctx = per-slot、decode/prefill = server 側 timings）:

| head | slots | ctx | decode tok/s | prefill tok/s |
|---|---|---|---|---|
| b70 | 2 | 16384 | 16.83（predictable 内容は 28.23 まで） | 507 |
| gad | 2 | 262144 | 4.95 | 未掲載 |
| xavier | 1 | 8192 | 2.45 | 33.8 |

- **throughput 系 4 id は routing 残骸**（`qwen3.8-27b-fastmtp-aggressive` /
  `-throughput` / `-throughput-5090` / `-throughput-b70`）。実体は
  worker.cljk の hosted-model-ids + env-gated endpoint 群で、env が無いので
  /v1/models に is_ready=false で載る一方、catalog には不在。廃番手順は
  SKILL.md「gateway 側: model id の退役」。**b70 id だけは保持**:
  `body-for-origin` が b70 head 宛て body.model をこの id に書き換え、
  `probe-b70` がこの id + n_params 一致で identity 検証する — 公開 alias で
  はなく **wire contract**。このため `with-model-request-defaults` の set は
  b70 を含めておく（内部書換 → この呼び出し、の順序のため。落とすと b70
  head で enable_thinking 既定が黙って消える）。残り 3 id は
  `deadHostedModels` に足して murakumo-main へ rewrite で既存 caller を守る。
  b70 という *head* は pool 構員として残る — id の廃番と head の退場は別の
  話。同一 id 文字列の第三の用途に注意: `provider_catalog.cljk` の
  qualification-model（OpenRouter projection の棚 id、is_ready false、
  test pin 済み）— grep は `src/local_murakumo/*.cljk` 全体で行う。

## 6600h（aiueos-6600hs、24GB tier）

- Ryzen 6600H、Radeon 680M（Vulkan、`--list-devices` で RADV REMBRANDT 12.2GB free 表示）、MemTotal 23.87GB、LAN 192.168.1.8、tailscale 100.92.201.91。**SSH は root のみ許可**（他 user は tailnet policy で拒否）。
- llama.cpp: `/root/llamacpp/llama-b10919`（libggml-vulkan.so 同梱、`--device Vulkan0` 対応）。
- 重み: `/root/models/` に Qwen3.8-27B-GSQ-RCO-IQ2_XS.gguf（sha256 f0ae5006… = infer.edn の pin と一致済み）、Ling-3.0-tiny-Q4_K_M.gguf、Ling-mini-2.0-Q4_K_M.gguf、Qwen3-Coder-30B IQ4_XS。DL 後は必ず byte 数 + sha256 を infer.edn の pin と照合（truncated-but-padded を byte 数では拾えない）。
- kame 実測起動: `-ngl 99 -c 8192 -t 12 --port 8095` → 200 応答、decode 2.77 tok/s。⚠ `-ngl 99` だけでは Vulkan を使った痕跡が log に無い — `--device Vulkan0` 明示 + 起動 log 確認が次の一手。
- usagi: `llama-ling30-tiny.service`（port 8099、-ngl 0 CPU、fa on）が systemd 常駐済み。ring 用 replica は `--alias ling-3.0-tiny` を付けて別 port（例 8096）に立てる。
- 24GB tier の quant 選択肢（ISTA-DASLab/Qwen3.8-27B-GSQ-RCO-GGUF 実測ツリー）: IQ2_XS 8.4GB / IQ2_S 9.3GB / IQ3_XXS 10.1GB / IQ3_S 11.8GB（+ 各 -mtp 版）。

## join worker（poll_worker.cljk / scripts/infer-join.cljk）レシピ

```bash
# ノード上で。kame と usagi で 2 本、--name を分ける
MURAKUMO_SERVICE_TOKEN=<join.env から> kbb --backend sci scripts/infer-join.cljk \
  --model <queue-model-id> --name <node名> \
  --local-url http://127.0.0.1:<port>/v1 --trust-tier community
```

- 流れ: POST /infer/nodes 登録 → heartbeat（local /v1/models probe）→ GET /infer/queue?model=&did= → claim 201 / 既 claim 409 で次へ → local completion → POST /infer/queue/<id>/result。
- heartbeat は **local /v1/models の id 一致**で ready を判定 → llama-server 側 `--alias <queue-model-id>` 必須。
- 認証は MURAKUMO_SERVICE_TOKEN（dan の `~/.murakumo/edge/join.env` に実績あり。CACAO の場合は --did に issuer DID 必須）。
- 恒久化: Linux は systemd unit（Restart=always）、macOS は LaunchAgent（dan 先例: ~/.murakumo/edge/ に runner + join.env）。

## gateway worktree 作業手順（tier alias 追加の標準形）

repo は superproject 内 `orgs/network-awai/cloud-murakumo-api`。routing core
は `src/local_murakumo/worker.cljk`。worktree と branch 操作は **repo 自身から**
行う（superproject 側からは remote 名付き参照が解決しない）。

1. worktree: `git worktree add -b jk/<topic> /tmp/wt-<topic> network-awai/main`（remote 名付き参照。この repo に `origin` は無い）。初回は repo 内で `git fetch network-awai main`。
2. `src/murakumo_tier_aliases.js`（純 rewrite table。**未着地 — 新規作成**）に alias を追加。
3. `src/worker_entry.js`: azure rewrite の直後・**`applyModelTokenBudget` の直前**に 1 箇所だけ `request = await rewriteTierAliases(request);`（import も追加）。budget が body.model で token policy を引くため、budget より後だと alias が UNKNOWN default に落ちる。この 1 箇所の上流 rewrite で chat/completions・messages・responses 全分岐と budget・admission・gad 短絡判定が解決後の id で通る。
4. `src/model_token_limits.js` DEFAULTS に concrete id 行。
5. **concrete id は `src/local_murakumo/worker.cljk` へも**: `model-context-windows`（ALLOWLIST。無い id の応答は edge モデル名で返る）と `resolve-endpoint`。
6. `test/murakumo_tier_aliases.test.mjs` に pin 追加 + package.json `test:origin-auth` に登録。
7. `node --test test/murakumo_tier_aliases.test.mjs test/model_token_limits.test.mjs test/dead_hosted_model.test.mjs` で green（非alias が同一 object で pass-through することも assert）。
8. **worker.cljk を触ったら、まず `grep -rn "<symbol>" test/` で該当 literal pin を全て引いて同時に直す**（chat_fallback_timeout.test.mjs がソース行を regex pin している。pin 直し漏れは一見無関係な test で落ちる）。/ready の `js/Promise.all` から probe を抜いたら `(aget values N)` の index を必ず振り直す（漏れると隣の probe の結果を別の capacity 変数が読み、/ready は 200 のまま静かに嘘を列挙する）。その後 `npm run test:origin-auth && npm run test:cljs`。
9. commit → push → PR → merge → deploy（resource-guard 経由）→ live verify: `POST /v1/chat/completions` に model=<alias>、`x-murakumo-served-model` と実応答本文を確認。出す前の生存確認は 3 面（SKILL.md）。

## live verify の具体値（kame alias 着地時の実測）

- `murakumo/kame` → 200、content "OK"。**応答の model field は解決後の wire
  id（b70 head が serve したため `qwen3.8-27b-throughput-b70`）を返す** —
  alias 名で応答が返ると誤読しない。alive 判定は HTTP 200 と content で行い、
  model field は「どの head が serve したか」の観測として読む。
- retired id の request は 503 ではなく **45-180s の wall timeout** になる
  （main pool の待ち行列に入るため）。死んだ id が生きているように見えるの
  で、retire の完全性は /v1/models からの消失で確認する。
- merge 後の統合 checkout で `package-release.mjs` が落ちるのは正常（merge
  が main 側の別 commit を含むと digest 対象の src 内容が branch 時点から
  変わる）— refresh-release-artifact → release commit → deploy の順で回す。

## usagi（ling-3.0-tiny）の本番 E2E 実測（gateway 直投下）

- `POST /v1/chat/completions` `model=ling-3.0-tiny` → finish stop、content 正答
  （max_tokens 512 で「数字だけ」の指示に従う。64 だと reasoning-first で
  content が空になる — 小さい budget は効かない）。wall 11.9-17.6s。
- `model=murakumo/usagi`（tier alias）→ rewrite → 応答 model field は
  **ling-3.0-tiny**（解決後 wire id を返す — kame と同じ正直な labelling）。
- `GET /v1/models` の選択リストと `GET /infer/models/<id>` の catalog は別面 —
  選択リストの構成員は `MURAKUMO_MODELS` / `MURAKUMO_EXTERNAL_MODELS` /
  modal augment（advertise gate）の 3 供給源が合流する。除去系の変更は
  実 fetch して全構成員が消えたことを確認する（単一供給源の grep では拾い切れない）。

## dan（16GB mini）restart ループ診断記録 — 再発時の順路

1. log に traceback が無い → bare `except Exception:` の握り潰しを疑う。print + raise に変えてから再起動（これをしない限り原因は見えない）。
2. 「Missing N parameters」→ safetensors header を直読（`struct.unpack('<Q')` + json）して tensor 名一覧を取り、HF の weight_map と sym-diff。**sym-diff 0 なら重みは無傷**で loader 側の新 arch 未対応 → loader を upgrade して再試行が先。
3. fallback CDN が prune 済みなら DNS が TEST-NET-1（192.0.2.1）に落ちて connect が minutes hang。prune 済みの経路は timeout を短くするのではなく**コードから剥がす**。
4. watchdog cron が pkill→再起動を 1 分周期で回している間は原因が隠れる。まず script 先頭に `exit 0` を足して no-op 化（.bak を残す）。**/var/at/tabs は root+sudo でも書換不可**（Operation not permitted）— crontab の編集は諦めて script 側を止めるのが確実。watchdog を戻すときは **rate-limit（stamp file + 最小再起動間隔）を入れてから**戻す。1 分周期の pkill→restart は warmup が終わる前に殺し続ける。
5. 実原因: gemma-4 チェックポイントは mlx-vlm 形式（image-text-to-text）。旧 mlx-lm の loader が layers 15-34 の k_norm/k_proj/v_proj を読めず ValueError。sha256 で重み無傷を確認後、mlx-lm を最新に上げて解消（0.31.2→0.31.3 実測で復旧、health ok + completion 200）。
6. 教訓の一般形: **exception を握って fallback に落とす設計は、fallback 自体が死んでいると 3 つの障害（loader 非対応・fallback 死・watchdog ループ）を 1 つの症状に畳み込む。** まず print+raise で分離してから直す。
7. **macOS ノードの運用**: crontab は sudo でも編集不可（Operation not permitted）。`launchctl gui/501` 操作は SSH から 125 error（bootstrap/load とも）— LaunchAgent の登録・修復は Aqua セッションに任せ、SSH からは script ファイル管理で運用する。nohup 起動のプロセスは親が launchd に見えるが再起動で消える。

## 第三者 model 比較の読み方 — KV/メモリ予算の計算手順

新規モデルを fleet に載せるかどうかの比較で、「KV キャッシュが何割減るか」
と「総 RAM が何割減るか」を混同しない。HF config 定数からの計算手順:

1. **KV は token 履歴に比例、Mamba/recurrent state は固定** — この 2 つを分けて
   出す。hybrid モデル（例: Mamba-2 層 + Attention 層が混在）の Attention 側
   KV は `attention層数 × n_kv_heads × head_dim × 2(K+V) × dtype bytes` /
   token。Mamba 側 state は `mamba_num_heads × mamba_head_dim × ssm_state_size`
   要素/層で、ctx 長に比例しない（conv state も少し乗る）。
2. **「標準 MHA 比で何%」の基準は sequence-mixing 層の深さを揃える** — 総層数
   ではなく attention 層の比率と KV head 比を掛ける（例: attention 6/29 層 ×
   kv 2/32 heads = 約 1.3% になり、これを「98.7% 削減」と読む）。
3. **weights は削減に含めない** — hybrid でも 30B weights は 30B 分のままで
   ある。「KV が 98.7% 減」を「総 RAM が 98.7% 減」と読むのが典型的な宣伝
   読み替え。Q4 量子化の実ファイルサイズは理論下限（0.5 byte/param）+ 経験的
   加算で概算し、実 GGUF を DL していないなら未測定と明記する。
4. **第三者ベンチスコア（Artificial Analysis 等）はスコア世代を揃えてから比べる**
   — 同一モデルの release 時スコアと現行ページスコアは評価セット更新で別の
   尺度。異世代スコアの直接比較はしない。スコアは自前実測ではなく第三者測定値
   として明示する。

## dan（16GB mini）restart ループ診断記録 — 再発時の順路
## dan（16GB mini）restart ループ診断記録 — 再発時の順路
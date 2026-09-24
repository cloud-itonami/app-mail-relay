---
name: utsushi-jvmfree-bot
description: Use when extending the utsushi-maint JVM-free migration bot.
category: devops
---

# utsushi-maint — utsushi JVM-free 移行 bot

2026-09-14 新設 (既存の未稼働 `utsushi-maint` profile を実装 — 新規 profile は作っていない)。

## 構成

- **profile**: `~/.hermes/profiles/utsushi-maint/` (既存 dir + config を利用)。
  SOUL.md = 移行マップ + 1 tick 1 wave 手順。skills/ に
  java-kotoba-migration / kbb-test-suite-fix / kbb-config-and-classpath を複製
  (per-profile skill 独立性 — 本体 profile にしか無い skill を参照すると cron run が
  自己報告で落ちる)。
- **cron 2 本** (profile-scope):
  - `utsushi-jvmfree-measure` (id 3ed2c634d9c6, `*/20 * * * *`, no_agent,
    script `utsushi_jvmfree_evidence.py` bare filename) — JVM 依存残数を grep 測定、
    MEASURE 行を出力。手動 fire 1 回緑。
  - `utsushi-jvmfree-land` (id f091869ecc12, `15 */2 * * *` — measure job との
    干渉を避ける 15 分オフセット) — agent job, 1 tick = 1 wave 移植 + verify + 着地。
- **config**: `model.default: ao/kame` / murakumo, `max_tokens: 8192`
  (2048 だと bot の最終報告が途中で切れる — 実測), `run_budget_seconds: 1200`
  (600 は worktree 設計+verify+着地に足りない — 実測で着地 0)。

## 移行対象の実測 (2026-09-14 baseline, git 87a224d)

- JVM 依存 24 file-hits: test.clojure.java.io 8 / test.ExceptionInfo 5 /
  bench.clojure.java.io 5 / bench.clojure.java.shell 4 / bench.java.util 2。
  **src は 0** (すでに JVM-free)。
- 12 test ns の require は classpath に gitlibs deps を手載せすれば全部通る
  (clojure.java.io require でのみ死ぬ)。緑 classpath:
  `src:test:bench/ffmpeg-comparison/src:../org-iso-h264/src:../org-iso-isobmff/src:$G/codec-primitives/ffa07605.../src:$G/langchain/0f966d0.../src:$G/text/73bdb13.../src:$G/perfgate/02f5485.../src`
  (shas は utsushi/deps.edn の pin)。
- suite runner: `~/.hermes/profiles/utsushi-maint/scripts/utsushi_suite_run.py`
  — kbb -M:test を回し totals を suite-ledger.jsonl に append。
  `Could not find namespace:` 等の指紋は UNMEASURED として報告 (suite 赤ではない)。
- baseline では suite は clojure.java.io で即死 (UNMEASURED)。

## 実測の罠

- **本体 checkout に前セッションの未着地 WIP が有った** (8 test file の kbb 移植)。
  `git stash push -m 'pre-jvm-baseline: earlier session kbb port (keep)' -- test/`
  で温存。bot はこの stash から同じ移植を写すことができる (SOUL に名指し済み)。
- **nbb.edn を新規作成した** (utsushi に無かった)。deps.edn の sha を byte-for-byte
  複写 + `:paths` に test/bench 追加。kbb shim が nbb.edn のみ読む。
- **cljk-origin の origin `.clj` の test file は kbb で `target-incompatible`**
  (codec_test.cljk 実測) — reader-conditional 化したら cljk-origin.edn の origin を
  `.cljc` に付け替えるのが同じ commit。SOUL に書いた。
- **手動 fire と scheduler tick の並行 run が起きた** (13:54 手動 fire 中に 14:00 tick
  が起動, `already being fired` 拒否が効かず二重実行)。land cron を 15 分オフセットに
  ずらして干渉を構造的に避けた。手動 fire は検証目的に限定。
- **gateway の unclean shutdown が in-flight cron run を interrupted にする**
  (13:43 実測)。bot の不具合ではない — executions の unknown は再 fire せず次 tick に任せる。
- **execute_code は cron run で BLOCKED** (SOUL の規則どおり terminal script 経由に寄せる)。
- **1 tick で着地できたかの判定は suite-ledger.jsonl + state.db 最終 assistant 報告**。
  agent.log は API call 統計のみ。

---
name: nbb-to-kbb-migration
description: Use when migrating nbb tooling to kbb. kbb-first rule.
---

# nbb → kbb migration

**Policy (owner, 2026-09-07): 新規の運用 tooling を nbb で書かない。kbb-first。**
nbb は既存資産の実行環境として残るが、新規 script host としては禁止。
移行対象は `scripts/*.cljs` 等の運用 tooling。

## 前提: kbb readiness を先に測る

- kbb の readiness gate は skill `kbb-readiness-gate` と
  `orgs/kotoba-lang/kotoba-lang` の roadmap（ADR-2607181900）が正本。
- **kbb に無い op で必要なものは先に kbb 側に足す**（host capability 追加は
  skill `kotoba-capability-extension`）。guest 側で無理に代替しない。
- 測り方: 移行対象スクリプトが使う op（fs read/write、env、process spawn、
  http、log、edn parse 等）を列挙し、kbb の capability catalog と照合する。
  足りない op が 1 つでもあれば、その分は kbb 側の実装タスクになる。

## 移行手順

1. **対象を 1 script に絞る**（1 script = 1 branch = 1 slice）。west 管理なら
   origin/main から worktree。
2. **使う op を列挙 → kbb catalog 照合**。不足 op は kbb 側タスクに切り出す
   （guest を縮めない — whole-component 契約と同じ発想）。
3. **`.kotoba`（または `.cljk`）で書き直す。** nbb の cljs コードを直訳しない —
   kbb の op 形に合わせて書く。
4. **verify は kbb 自身で**: `kbb` CLI（amu checkout 内）で check/test/run。
   JVM-free を主張するなら java/clojure 拒否 trace も見せる。
5. **parity**: nbb 版の出力と kbb 版の出力を突き合わせる。nbb 版は
   comparison oracle として残す（cutover まで削らない）。
6. **着地**: branch push → `gh api .../merges` → pin（複数なら batch）。
7. **launchd/plist・cron の呼び出し行を kbb に書き換えてから**初めて
   nbb 依存が外れる。スクリプトだけ差し替えて呼び出しを残さない。

## 実測された制約（2026-09-06/07 時点）

- fs read + **write** は wire-35 (fs/app-data) が landed（write は path ++ "WRITE_SEP" ++ content、
  js backend; amu 6cca3852 同型）。**dir listing は kbb.browse（wire 34）** —
  2026-09-08 以降 (kotoba #615 / 01175b0b6, amu a681d3f2) は `NAME<TAB><0|1>` 行で
  is-directory flag も返る。kbb.browse に `subdirs` / `entry-dir?` / `entry-name` が
  あり、scope 内の tree walk は表現可能。ただし native backend は `string-index-of`
  を lower しない制約が残る（kbb_shim_test が pin）。
- `kbb.str`（5 byte-addressed helpers）landed。文字列演算はこの面を使う。
- EDN parse は capability 無しで可（parsing is computation, not authority）。
- **kbb.proc は allowlist-by-grant-index**（argv は policy literal、guest は 0 byte）。
  **動的 argv を要るスクリプト（repo ごとに違うコマンドを叩く tick 等）は
  このモデルと合わない** — 実測: kotoba-wave-verify-tick（20 repo × 5 command =
  100+ policy rows）と checkout-pin-lag（120 checkout の git -C）で `:blocked` 判定。
  **設計の tension は kotoba-lang/kotoba#597 で追跡中**（scoped dynamic form の方向性つき）。
  動的 spawn を要る既存 nbb tool は migration candidate から外し、#597 の結論を待つ。
- **ただし git stdout capture は wire 22 で可能**（kbb.git: stdout / stdout-line-count、
  js backend 1ef80b7d4 landed）— 固定 1 コマンドの git 系処理（checkout_holds_probe 形）
  は dynamic argv 不要で port 可能。
- **kbb js host は main checkout 基準でパス解決する**（worktree cwd ではない）—
  worktree に fixture を足したら main checkout にも同名 untracked で置くか commit する
  （実測: worktree-only fixture が「path outside the granted scope」で拒否された）。
- **string-substring は codepoint 級 / string-byte-length は byte** — UTF-8 マルチバイトを
  含む入力で index 計算がずれる。fixture は ASCII に保つか、codepoint 前提で書く。
- **backend 差**: string=? 等の一部 head は interpreter backend の strict grammar に無い
  （js backend は可）。port の検証は `--backend js`（配布形の native は wire-34 provider
  未達）。interpreter での拒否は backend gap として記録し、作り直さない。
- 未達 op がある場合の正しい出力は **`:blocked`**（fallback で書き直さない）。

## 実績

- 2026-09-07: `examples/kbb/edn-parse-report.kotoba` — superproject
  scripts/docs-edn-parse-report.cljs の kbb port（browse + fs read + edn 構造 scan、
  failures*10+total packing）。`--backend js` で result 12 = 1 failure / 2 files（fixture
  真実と一致）を確認して着地（kotoba-lang/kotoba 13ddc73f）。interpreter backend の
  string=? 欠落は記録済み。

## Migration candidate ledger (2026-09-16 re-measured; 09-15 entries preserved below)

- 2026-09-16 tick: kotoba#597 re-measured OPEN (dynamic argv blocker stands).
  origin/main since 09-15 landed only deps-door/launcher routing (PR #622/#623,
  70af01857) - no kbb surface op gained. None of the 6 blockers lifted.
  verify-kbb-ports.cljs confirmed NOT a candidate (stub-PATH spawnSync +
  per-case dynamic argv). Ports this tick: 0.

## Migration candidate ledger (2026-09-15)

- 2026-09-15 tick: kotoba main landed wire 3 (:hash/sha256) + wire 7 (:clock/now)
  js host providers (0e90a4d8b) and kbb -M/-X/-Spath/-Saliases engine resolution
  (c45f21994, ADR-2609112000 cutover). None of the ledger blockers
  (classpath scope-outer dirs / WASM API / full EDN values / dynamic argv) is
  lifted; 6 :blocked entries stand. Ports this tick: 0 (no unblocked candidate).

### 2026-09-09 measurement (breakdown below is as of that date)

### Landed（kbb examples/kbb/）
- docs-edn-depth-profile.cljs → examples/kbb/edn_depth_scan.kotoba
- langchain-store-adoption-scan.cljs → examples/kbb/store_adoption_scan.kotoba
- docs-edn-parse-report.cljs → examples/kbb/edn_parse_report.kotoba（13ddc73f）
- scripts/edn-datomize.cljs → examples/kbb/edn_datomize_scan.kotoba（2026-09-09 着地 e9d3c7e659、wire 35 のみ `--backend js`、nbb oracle parity 12202、fixture test/fixtures/kbb_ports/datomize_dir/。wrap 本体（schema merge）は kbb.edn が round-trip 不可のため nbb 版が oracle のまま）

### :blocked（kbb surface 未達、進化を待つ）
- gen-shadow-cljs-edn.cljs - 2026-09-09 再実測: proc stdout (wire:20) landed に加え、
  `:fs/browse` が NAME<TAB>D を返す変更 (kotoba #615, 01175b0b6) も landed。
  **scope 内**の path なら parent dir を browse して entry の D flag を見る形で
  is-directory は表現可能（旧「型判定 op が無い」blocker は scope 内については解消）。
  残る blocker: `clojure -Spath` が返す classpath には ~/.m2 / ~/.gitlibs 等
  checkout root 外の dir が含まれ、それらは wildcard 禁止の policy scope に入らない。
  original はそれらを dir filter に含むため、port は parity でなく挙動変更になる。
  blocked 維持（代替しない）
- tender-debug.cljs — WebAssembly API
- validate-adr-2608135000.cljs 系 — guest 内 full EDN reader（kbb.edn は構造のみ）
- verify-clj-everywhere.cljs / kami-provider-split-sync.cljs — spawn
- verify-d1-kotobase-migration.cljs — dir walk + full EDN values
- nbb-run-tests.cljs — 移行対象ではなく nbb ランタイム自体（テスト host 移行とセット）

## bb residue（退役 host、664 files 実測）

- q9 soak scripts（collect/check-q9-soak.bb）は 9-14 cutover の live infra —
  **cutover 完了まで残す**（evidence chain を壊さない）。
- edn-datomize.bb 複製群（20+ repos）— liveness 測定が未実施。dead なら削除、
  live なら移行。削除前の archive は git-cleanup-conflict 規則どおり。

## ルール（workspace-wide への反映）

新規 tooling を nbb で書かない規則は:
- この skill（移行者用の手順書）
- `AGENTS.md` / `CLAUDE.md` の toolchain 節（全 agent が読む正本）
- 既存の「nbb のみ」規則（ADR-2607173000 / ADR-2607181900 の kbb roadmap）を
  更新する形で反映する。bb → nbb 移行の時と同じパターン（退役 host の
  新規利用を禁じ、既存は段階移行）。

新規 nbb スクリプトを見つけたら **migration candidate として列挙する**
（即座に書き直すのではなく、kbb readiness が追いついたところから順に）。

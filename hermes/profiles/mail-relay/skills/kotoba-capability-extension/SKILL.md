---
name: kotoba-capability-extension
description: Use when adding a Kotoba host capability or kbb op.
---

# Kotoba capability extension (contract → lang → runtime)

Adding one capability touches THREE repos in a fixed order. Do them as three branches, one PR each, landed via server-side `gh api repos/kotoba-lang/<repo>/merges` (never local merge), then verify the chain.

## Chain (in order)

1. **kotoba-core-contracts** `resources/kotoba/runtime/capability_contract.edn`: add `"kind/name" <id>` to `:capability-ids` (find the next free id by grepping the number range — do not assume), a `:host-imports` entry keyed by the op symbol, and the op to `:host-import-order`. host-import-order is APPEND-ONLY — inserting mid-list shifts existing .wasm import indexes and silently breaks every pinned artifact.
   Beyond the contract file, kotoba-core-contracts also requires edits to `src/kotoba/core/capability_repository.cljc` and the contract test:
   - Classify the capability's effect (`capability-effects` map, e.g. wire-format codecs → `#{:codec}`) and add a radicle rid to `radicle-rids`, or its `full-repository-manifest` throws `capability effects are not classified` under the repository tests.
   - `test/kotoba/core/contracts_test.cljc` hardcodes the EXPECTED `host-import-order` vector inside `capability-contract-loads-and-validates` — an appended op fails that equality until you append it to the expected vector too. Grep the test file for the expected list before assuming the failure is environmental; that vector drifts stale when a prior wave lands without touching it.
   - Do NOT add a kbb ops capability to `actor-host-capability-ids` (the curated actor-host atomic catalog counted by `atomic-capability-repository-catalog-conforms`). That set holds actor:host wire-format/authority ops (`data/json` is there); a kbb-only op (`env-read`, `proc-exec`, `data/edn`) is a `:host/` op on the kbb surface and belongs only in the append-only contract region — adding it to the curated catalog flakes the 12-repo count as `:excess #{...}`.
2. **kotoba-lang**: add `:host/<kind> :host/<kind>` to `effect-for-kind` in `src/kotoba/lang/capability_values.cljc` (missing it = runtime deny `:unsupported-kind` even with a granted policy; the static compile gate does NOT catch this), and add the op symbol to `:string-head-host-ops` (or `:data-head-host-ops` if the first arg is structured data) in `lang/guest-grammar.edn` — otherwise the interpreter rejects the call with `:unknown-form` before any provider code runs.
3. **kotoba**: `op->kind` entry in `src/kotoba/runtime.clj`, a real handler in `host_providers.clj/default-handlers` (mirror `fs-check-permitted!` for per-argument resource narrowing — the guard runs on KIND before the guest's argument exists, so a scoped policy is silently ignored without this second check), and, for kbb, add the capability keyword to `admitted-capabilities` in `kbb.clj` plus a `:kbb/<cap>-resource-scope-required` policy check.

## Verification (all measured working)

- Sibling checkouts resolve via `clojure -M:dev` (`:dev` alias overrides git pins with `local/root` siblings). Plain `clojure -M` uses stale gitlib pins and will show your contract change as absent.
- Contract load check: `clojure -M:dev -e "(prn (get (:capability-ids (kotoba.core.contracts/capability-contract)) \"kind/name\"))"`.
- End-to-end: `bin/kbb src/demo_<name>.kotoba --policy src/demo_<name>_policy.edn` (sibling convention `demo_x.kotoba → demo_x_policy.edn`). Test both the granted path AND fail-closed (non-granted name → `:resource-not-permitted`; missing scope → `:kbb/...-required`).
- Full suite: `clojure -M:dev:test -m kotoba.test-runner` (it ignores args and runs everything).

## kotoba repo test-registration traps

- Every new test ns MUST be added to BOTH the `:require` vector AND the `run-tests` call in `test/kotoba/test_runner.clj` — a completeness test fails the suite otherwise.
- Any new file under `src/*.kotoba` must be in the emit manifest: run `clojure -M:dev:reproducible-emit regenerate` (never hand-edit `qualification/emit-digests.edn`), then bump the expected `:sources` count in `reproducible_emit_test.clj`.
- Before claiming a failure is yours, `git stash` your changes and rerun: this suite has standing pre-existing failures (e.g. `/tmp/kotoba-lang-*` conformance file misses). Compare counts.
- `with-redefs` on `System/getenv` fails to compile (`Unable to resolve var`). Test env handlers with real unlikely-to-be-set variable names instead of stubbing the JVM method.

## kbb guest (.kotoba) language constraints — check BEFORE writing the script

The kbb interpreter is much narrower than the wasm lowering path. Probe with a minimal script before writing a long one:

- Special forms are ONLY `do let if quote def defn`. No `loop`/`recur` (use fuel-bounded self-recursion calling the fn by name), no `defn-`, no `cond`/`when` sugar at the interpreter gate.
- Docstrings AND any string literal are capped at 127 UTF-8 bytes — an oversized docstring fails `check` with `portable string literal exceeds 127 UTF-8 bytes`, not an obvious docstring error.
- Iteration primitives: `count`, `nth` (throws on out-of-range), the string heads `string-length/string=/string-concat/string-substring` (interpreter bindings exist since the fs-browse slice; before them the interpreter had NONE even though grammar + wasm lowering admitted the heads). `fs-browse` returns entry NAMES only — join paths with nested `string-concat` before `fs-read`.
- Guard `string-substring` with a length check first: a short file body (empty file, short script) throws `indices out of range`, not a false.

### Compile-route fs-scanning: src/* interpreter-dialect vs examples/kbb/* compile-route variants

`bin/kbb` is a JVM-free shim (nbb → `amu compile --jvm-free` + kexe_loader, target `js-kotoba-v1`) and it compiles the **`examples/kbb/*.kotoba`** variants — those with `(:require [kbb.fs :as fs] [kbb.browse :as browse] [kbb.str :as str])`, `:string`/`:i64` type annotations, an `(:export [main])`, and `--source-path lib`. The **`src/*.kotoba`** interpreter-dialect variants (plain `fs-browse`/`fs-read`, `(count …)`) do NOT compile on this route — they fail e.g. `:kotoba.error/count-receiver` on `(count (fs-browse dir))` because `browse/entries` answers a `\n`-joined **string**, not a vector. Measure which variant you are on before porting a scan: `examples/kbb/no_bb_scan.kotoba` runs natively (result 3, full fs-browse/fs-read receipts); `src/no_bb_scan.kotoba` does not.

**fs-browse has no is-directory indicator → the guest cannot recurse.** `browse/entries` answers entry NAMES only (file and dir alike) and the guest has no way to ask "is this a directory" — so every kbb fs-scan today is FLAT (one directory), per the examples store_adoption_scan self-note. Org-wide find/grep over thousands of repos requires a NEW capability (e.g. `fs-browse-dir`: per-entry `(name, is-dir)` returned), added through the contract → lang → kotoba chain. A recursion-requiring scan cannot be built on `fs-browse` as-is.

### Landing a new fs-tree-walk capability — two catalog layers and the amu deferral

A capability that goes through `amu compile` (kbb `--backend js`, the JVM-free shim) needs BOTH source-surface layers, and they live in different places with different resync waves:

1. **guest-grammar head** (admission) — `:string-head-host-ops` in `kotoba-lang/lang/guest-grammar.edn` + the `:host/<kind>` effect row in `capability_values.cljc`. This is a grammar-resync wave: kotoba-lang (authority) first, then in-repo `resources/` copy, then sibling vendored copies (`kotoba-sema`, `grammar`, `kotoba` x2), EACH with its own `guest_grammar_vendor_test` digest pin moved in its own PR. `lang/vendored-copies.edn` lists every copy.
2. **capability-catalog wire id** (lowering) — `:fs/browse-dir {:compiler-wire-id 261}` in `kotoba-lang/lang/capability-catalog.edn` + the vendored copy in `kotoba-sema/resources/...`. amu resolves the capability catalog through its PINNED kotoba-sema's vendored copy, NOT through amu's own `resources/kotoba/lang/capability-catalog.edn` (that file is a disjoint T8.3-only catalog with no fs ops). So a new cap that `amu` must lower is only registered once amu's kotoba-sema pin points at a sema commit carrying the catalog entry. Until then `amu compile` fails `cap-call names an unregistered capability: :fs/browse-dir`.

   **The compiler wire id is NOT the capability id, and a later maintainer may renumber it.** Measured 2026-09-07: the chain was first filed with `compiler-wire-id 261` (arbitrarily matching the runtime capability id 261), then a maintainer normalized it to **36** (sema PR #69 `93a2ad4`, "resync :fs/browse-dir compiler wire 36") because amu KEXE import slots are small integers near fs/browse's 34. The runtime capability id stays 261; the compiler wire id became 36. Before landing a consumer that dispatches on the wire id (e.g. `kbb_js.cljs`'s `wire-ids` map), re-read the CURRENT capability-catalog wire id from main — do not trust the id you filed; grep main for the number.

   **An amu sema-pin bump for a new capability often breaks amu's `downstream-murakumo` CI job.** That job clones kotoba-lang/murakumo and runs `murakumo.kotoba-oracle-authority-test` against this amu checkout. If the sema commit you bump TO also carries KIR-changing heads (e.g. `ccd3b23` min/max), the live-compiled KIR drifts off murakumo's checked-in oracle and the gate fails with `kotoba oracle not ready`. Do NOT bump amu's sema in isolation to make a new capability lower; either pick a sema commit without the KIR-changing heads (often none on main), or make it a murakumo-coupled wave (regenerate murakumo's oracle + advance amu sema in the same PR). A kicked amu PR whose only diff is deps.edn+deps-lock.edn should omit the sema bump entirely and ship the capability on the kotoba runtime/kbb-js host instead.



### Wiring a new fs capability through the kbb js backend (kotoba repo)

When the capability must run on kbb `--backend js` (the JVM-free nbb→amu route), it touches five places in the kotoba repo. Missing any one = a distinct failure:

1. **`bin/kbb_js.cljs`** — the js host provider. Edit all four: (a) `wire-ids` map gets `:fs/browse-dir 261`; (b) `compile-names` map gets `:fs/browse-dir :fs/browse-dir`; (c) `needs-scope` gets a `:kbb/fs-browse-dir-resource-scope-required` clause (an admitted cap with no scope check is scope-silent); (d) the dispatch `(contains? caps ...) (assoc <id> (fn [...]))` handler — for a tree-walk, build each `"<name>\t<0|1>"` line by `.statSync`ing the child to decide is-dir. A missing `wire-ids`/`compile-names` entry fails `capability-not-compilable`; a missing dispatch slot denies every call.
2. **`lib/kbb/browse.kotoba`** — add `entries-dir` → `(typed-cap-call :fs/browse-dir :string :string dir)` and `(:export [… entries-dir …])`. This is what a guest funnels through; the raw cap name is not a guest symbol.
3. **probe guest** `examples/kbb/probe_*_via_env.kotoba` — the directory-under-test arrives via a granted env name (guests have no argv); `(:require [kbb.browse :as browse])`, read `KBB_PROBE_DIR`, call `browse/entries-dir`. Parse the is-dir flag from the LAST byte of each line (the tab position varies with the name, only the trailing flag byte is a safe fixed point); `string-index-of "\n"` + `string-substring` walk, self-recursion (no `loop`).
4. **provider boundary test** `test/kotoba/kbb_js_providers_test.clj` — mirror the `:fs/browse` group: a scoped dir with N files + 1 subdir asserts result = subdir count and last receipt `:entries N+1`; a FILE inside scope asserts `:reason "not a directory"`; a dir outside scope asserts `:outcome :denied`. Reuse `refused!` (its three marks: exit 1, `:kbb-js/guest-failed`, `:outcome :denied` last receipt).
5. **`src/kotoba/kbb.clj`** — add `:fs/browse-dir` to `admitted-capabilities` and a `:kbb/fs-browse-dir-resource-scope-required` policy check (mirror the `:fs/browse` one).

Plus the interpreter slice in `src/kotoba/host_providers.clj/default-handlers`: `'fs-browse-dir` returns a `\n`-joined `"<name>\t<0|1>"` string through the plain-string result convention (like `env-read`), narrowing via the same `fs-browse-check-permitted!` (granted directory TREE). Reuse `:host/fs-browse-dir` in `runtime.clj` `op->kind`. A single misplaced close-paren in the handler (off-by-one in the `(map (fn …) names)` closure) fails the whole file parse at a far line — count the 7 closes (map → join → let names → when → let f → let dir → fn) before committing.

Full recipe for a working native fs-scan guest (kbb.fs/kbb.browse/kbb.str API, policy resource-scope shape, verified run command): see `references/kbb-fs-scan.md`.

Running a JVM-shaped deps.edn project (render/build paths closed behind `#?(:clj …)`) on the kbb engine — nbb.edn coordinate mirroring, the `.nbb/.cache` layout, io/resource via cache scan, JVM→node fs interop table, the `.-method`-vs-`.method` trap, guard-removal procedure, and the build/bundle-host decision (render vs ESM worker bundle): see `references/jvm-render-port.md`.

**Host-authoritative consumers need the host's OWN surface, not a port.** A file whose runtime is a host platform (e.g. a Cloudflare Worker: `Request`/`Response`/`Headers`/`fetch`/DO/R2 bindings) is not a render path to port node-side — the host interop is its substance. Before sizing any port, census the interop surface: `grep -o 'js[-/][A-Za-z_./]*\.?\|js-invoke\|js->clj\|clj->js\|gobj/[a-z-]*'` over the entry file and every transitively-required ns (count per group: http / json / time / crypto / misc). A census over ~100 forms across ~13 files means the host surface must become capabilities (multi-wave upstream work — file ONE issue at the nearest authority repo with the census table, minimal repro of the rejection literal, wave order, and reuse check of already-landed caps), and the consumer-side branch ships only what IS portable (render/static assets) with the deploy explicitly marked blocked-on-wave.

## Missing-library blockers: diagnose the LAYER, then implement and publish

Owner standing directive: when a needed capability/library is absent, implement the missing layer yourself, publish it as a kotoba-lang org repo (or PR to the existing one), and consume it — never leave the blocker as prose or work around it host-side forever.

Before implementing, check which of the THREE layers is actually missing — a "missing library" is often only a missing wrapper/provider:

1. **capability repo** (`capability-<id>`: `capability.edn` + reference provider) — measured: `capability-hash-sha256` (wire 3) and `capability-clock-monotonic` exist with definition CID + JVM reference provider; `capability-clock-now` (wall-clock, wire 7) is absent — a genuine new-repo case.
2. **kbb guest lib** (`kotoba repo lib/kbb/<kind>.kotoba`) — measured absent for hash/time; guests must go through the lib, never spell raw wire ids.
3. **kbb js host provider** (`bin/kbb_js.cljk` `wire-ids` + `compile-names` + `make-providers` dispatch + `needs-scope` clause) — measured absent for 3/7; the usual missing layer. Host it by mirroring the existing four dispatch arms (grant check → receipt → deny with 3 marks), and mirror `test/kotoba/kbb_js_providers_test.cljk`'s one-assertion-group-per-wire shape for the test.

The runtime tells you which layer is missing, fail-closed:

- Policy naming an unhosted capability → `:kbb/capability-not-hosted` with `:hosted [...]` (the provider layer is missing). **Do not treat compile-green as runnable**: `amu compile --policy` produces the wasm regardless — the wire provider is a separate RUNTIME layer.
- A capability absent from the compiler catalog → `cap-call names an unregistered capability` (the catalog/grammar layer — the multi-repo resync wave, not the host).
- A bare `{:allow #{...}}` policy → `:kbb/forbid-wildcard-required`. The js-backend policy needs the FULL shape copied from `examples/kbb/*_policy.edn`: `{:kotoba.policy/capabilities #{...} :kotoba.policy/forbid-wildcard true :kotoba.policy/capability-resources {...}}` with explicit scopes per capability.

Invoke kbb's js backend as `kbb --backend js <file> --policy <policy>` — NEVER `node bin/kbb_js.cljk` directly: the file is a `.cljk` namespace that requires the engine to resolve its requires; raw node execution dies at parse (`SyntaxError: Invalid or unexpected token`) before any diagnostics. From a WORKTREE of the kotoba repo, set both `KBB_HOME=<worktree>` (picks up your edited bin/kbb_js.cljk) and `KBB_ENGINE=<superproject>/orgs/kotoba-lang/org-babashka-nbb/cli.js` (the worktree's `../org-babashka-nbb` sibling does not exist under /tmp) before calling `<worktree>/bin/kbb --backend js ...` — otherwise the run resolves the MAIN checkout's host and tests the wrong provider set.

### Landed: wire 3 (hash/sha256) + wire 7 (clock/now) on the kbb js host (measured green)

Both went through the host-provider path only (no contract/grammar wave — they were already in the catalog and amu admission). Landed on kotoba main (`bin/kbb_js.cljk` + `lib/kbb/hash.kotoba` + `lib/kbb/time.kotoba` + probes):

- **`make-providers` close-parens discipline — measure, don't eyeball.** Each cond→ arm needs: branch-internal closes (str→let→fn→assoc = 4) with cond→/let/defn's 3 closes ONLY on the LAST arm. The landed bug class: adding arms after the previously-last arm without re-balancing left the new arms NESTED inside the old last arm's fn (depth check: every `(contains? caps :X)` must sit at the SAME paren depth inside make-providers — compute `(count "(") - (count ")")` over the prefix before each test line and require uniformity before running; measured drift produced `:provider-keys [33]` with the hash arm silently nested, then `IAssociative.-assoc` on a number when over-closed). Probe depth per arm BEFORE running anything.
- **Policy shape for no-scope capabilities**: `:hash/sha256` and `:clock/now` have NO resource scope — a policy naming them needs only `{:kotoba.policy/capabilities #{...} :kotoba.policy/forbid-wildcard true :kotoba.policy/capability-resources {}}`. Scoped caps (env/fs) still need explicit scope entries.
- **String 65,536 boundary is a RUNTIME assert, three layers**: generated artifact `stringLimits.valueBytes` + generated `assertString` (`utf8Bytes > 65536 → 'string-too-large'`) + host caps (defense-in-depth). A >64KiB value arrives fine through a provider and dies AT the guest boundary — attribute the failure to the boundary, not the provider. Native route differs: `KEXE_STRING_POOL` per-run budget (amu 2a3d4333; baked at package time, env can NOT raise a packaged binary) lifts it up to 256 MiB; wasm32-browser and js keep the fixed 65,536. Practical rule: guests are Digest-first (hold small deterministic values; big payloads stay host-side or content-addressed), protocol chunking (RANGE_SEP) for sequential folds — never raise the constant.
- **kexe-native is the CLI shape**: `amu compile --target x86_64-macos --jvm-free` + `extract-native --symbol main`, capabilities baked at package time. kagi ADR 0002 records the decision; JVM exec and kbb/sci exec are retired for it (sci died on java.time.Instant/JCA/java.nio — measured).

## Adding a builtin OPERATOR (not a capability) — 5 backends / 6+ repos

A capability (above) is a host-import the guest calls: contract → lang → runtime, THREE repos. A new bare LANGUAGE OPERATOR (`+`, `min`, `document-vector-sort`) is a different, wider surface: it must be admitted AND LOWERED by every backend a `-M test` / `-M compile` target runs, or that target fails with a DIFFERENT per-backend refusal. You cannot ship an operator only one backend implements — `-M test` walks all configured targets and the weakest backend games the verdict.

## The chain (each a separate PR; the last is the tail)

1. **kotoba-sema** `frontend.cljc` — add to BOTH the arity/op table AND the type-inference dispatch (`i64-operations` for i64 ops; `document-fixed-operations`+its `(= op ...)` branch for document ops). Miss the type branch = the op falls to `:else (reject! "operation has no admitted type signature")`.
2. **osaho/kotoba-kir** `kir.cljc` — the backend op-list (`def` of all ops) AND the interpreter `case` for BOTH runtimes (`:clj` `reduce min`; `:cljs` BigInt-safe — `js/Math.min` over Number projections back-converted, or a ternary `a<b?a:b` since Math.* chokes on BigInt).
3. **kotoba-script** `script.cljc` — the JS emitter has TWO tables: the operand-type dispatch and the `emit-call` dispatch. Plus a document op needs a prelude JS helper embedded in every emitted module — that changes EVERY parity golden, so `clojure -M:golden` (JVM dev tool) + commit the .mjs regen, THEN `nbb --classpath "src:test" test/nbb/parity.cljs` must report `SCANNED N failed 0`.
4. **kotoba-wasm** — typed wasm emitter, THREE tables: `typed.cljc` result-type set, `core.cljc` emit dispatch AND its has-operation detection list, plus a document op's intrinsic signature row (`typed-document-*`) matched by a real HOST implementation (next repo).
5. **amu** — the runtime that RUNS the wasm: `runtime/browser-host.mjs` must implement the host side of the new intrinsic and register its `kotoba:typed/.../function` import. This is SEPARATE from kotoba-wasm's emit table — one writes the call, the other answers it; missing either yields a distinct error.
6. **tail — pin integration**: the operator is only usable end-to-end after the merged kotoba-wasm (and the advanced sema/kir/kotoba-script pins) is actually what the consumer resolves. A consumer's working-tree `deps.edn` edit does not survive sibling sessions, and a competing `UU` (unmerged-other) file in the shared worktree BLOCKS commit entirely — the pin change must live in the same clean commit as the consumer's edge.

## The per-backend failure signatures (read as a map)

Each "X is not supported/qualified" names a DIFFERENT unimplemented layer; fix the layer the message names, not the message:

- `unsupported KIR operation` — kotoba-script's emit or type dispatch lacks the op (it reached the `:else` at the end of the emitter).
- `unsupported typed Wasm expression` — kotoba-wasm's type-infer or emit dispatch lacks the op (the hand-written `-M compile` of the SAME source may still pass because that route skips the qualification gate).
- `typed Wasm operation is not qualified` — the op reached kotoba-wasm's final `:else`(neither a case nor a known function), i.e. the emit dispatch case you added is in a DIFFERENT `emit-expr` than the one the failing route enters, OR the consumer pinned an OLD kotoba-wasm without your case.
- `Kotoba target test process failed, :target :wasm` — the wasm COMPILED and got to execution; the failure is a runtime/host problem (`compatibility`, host import, an option the host rejected).
- `no exported test-* definitions` — the `-M test` file has no `test-*` export; `main` alone is not enough (`-M test` requires `test-*` defs listed in `(:export ...)`).

## JVM-free verification (owner standing preference)

Owner: "JVM/clojure に落とすのをやめてほしい". For compiler work, verify on nbb (JVM-free), never reach for `clojure -M:*` as the first route. Build the classpath explicitly from gitlib caches:

```bash
nbb --classpath "src:test:$HIR/$SEC/$SHA/$MUL/$CBOR+..." <script or runner>
```

where each `$X` is a gitlib `src` from `~/.gitlibs/libs/<org>/<repo>/<sha>/src` for every dependency the ns requires (trace `:require`s). Tests run JVM-free when they are `.cljc` and registered in the repo's `run-tests.cljs` — a `.clj` test is JVM-only by construction.

## Operator vs capability decision

Operator = a grammar/emit-level head (`+`, `min`, `document-vector-sort`), 5-backend chain above. Capability = a host-import the guest calls, the 3-repo contract→lang→runtime chain above. Pick by whether the feature is a new head in source or a new host-import the source can call; do not start down the 3-repo capability path for a head the compiler must lower.

## Scope rules for new ops

- Fail closed by default: a grant without an explicit resource scope reads nothing. Empty scope sets are a policy mistake — reject them at kbb admission.
- Exact-match scopes for names (env vars), canonicalized paths for files. Case differences must not slip a name allowlist.
- Append, never insert, in `:host-import-order`.

## amu loader.c 変更 (native capability provider) の必然 chain (2026-09-06 確定)

amu `tools/kexe_loader.c` に native 側 provider を足すと loader-source の sha が変わり、**3 箇所同時に動かさないと native 系 CI が全滅**する:
1. `kotoba-lang/artifact` `src/kotoba/artifact/runtime_identity.cljc` の `loader-source-sha256` 前進 (artifact PR)
2. amu の artifact pin advance (amu PR)
3. amu `fuzz/baselines/native-parser.edn` の `:loader-source-sha256` 同期 (同一 amu PR)

不一致シグナル: native テスト 20 件が `native loader source identity mismatch`、または CI の `fuzz-native: coverage baseline does not match loader source`。

**分解規則**: wire-id 発番 PR (純 pin) に loader.c 実装を混入しない — 発番 = 別 PR (suite green)、loader provider = 別 PR (artifact identity + fuzz baseline とセット)。混入すると approved identity が旧 loader と不一致で発番 PR 自体が red になり着地できない。

## kbb readiness gate state (ADR-2607181900)

Landed (measured green): `:fs/app-data` (real read/write; a DIRECTORY scope entry covers the files beneath it — per-FILE entries keep exact equality), `:env/read` (id 258, per-name provider), `:fs/browse` (id 253, dir listing narrowed to the granted directory TREE, prefix boundary on a path separator, canonicalized so `dir/..` cannot escape), interpreter string heads + `nth`, `data/JSON` (id 246, `json-encode`/`json-extract-field`, per-field resource narrowing), `process/exec` (native slice), and the `.kotoba` porting wave (`src/verify_no_bb.kotoba`, `src/no_bb_scan.kotoba`, `src/shebang_scan.kotoba` — verify-no-babashka equivalents that RUN on kbb; measured: dirty fixture = 3 violations, clean = 0, `dir/..` escape fail-closed). Their tests are the ADR gate-condition-② evidence and pass under `kotoba.test-runner`.

Closed (2026-09-06): the ELN‑reader surface is landable via the full `data/edn` chain (contract #24 → lang effect row #561 → kotoba provider #575, all on main). `data/edn` = capability id 260, `edn-read` host-import, `:result :value` (interpreter slice has no linear memory, so the guest passes EDN text and gets a parsed value directly; host_providers backs it with clojure.edn/read‑string, kind‑level grant only since every byte is guest‑supplied; malformed EDN fails closed as an `:error`).
    Gate item ④ (tasks.edn facade check as .kotoba) is LANDED (2026-09-06, kotoba #576): pure `keys` + `get` interpreter builtins (runtime.builtin‑fns; both grammar‑admitted heads, capability‑free — required to enumerate/index an edn‑read parsed value), plus `src/facade_edn_scan.kotoba` which fs‑reads a tasks.edn, edn‑reads it, iterates entries via `keys`, and detects the two documented facade shapes (argv head `bb`; an `exec bb` shell string). This closes the LAST verify‑no‑babashka detection arm missing on kbb (the .bb/bb.edn/shebang arms were already ported).
    Also upgraded (kotoba #577, 2026-09-06): `src/edn_pin_check.kotoba` from text‑level (counting 40-hex runs after the `io.github.kotoba-lang/` needle) to a real EDN‑parsed deps.edn audit — edn‑read the file, enumerate `:deps` via `keys`, check each `:git/sha` is a valid 40‑hex string. Structurally beats the text scan (catches non‑hex 40‑char shas, no quote‑boundary blind spot). Note: the fixture had a duplicate `:deps` key (a text‑level‑tolerance artifact) that real EDN cannot parse — renamed the second `datom` entry to `short`. Reading a value off a non‑map deps entry requires `(get entry :git/sha)` → nil, NOT `map?`/`string?` (both stay rejected by the strict grammar; guard the length instead with `(= 40 (count v))` before `string-length`).
    Pitfalls measured on the data/edn chain (add to the runbook before the next kbb capability):
- Adding a kbb capability to the kotoba repo REQUIRES advancing the git pins for kotoba-core-contracts AND kotoba-lang in kotoba's `deps.edn` to the landed commits — CI resolves via git pins (`:dev` sibling checkout is local-only), so stale pins = `effect-for-kind` missing `:host/data-edn` = contract-test FAIL on CI while passing locally. Use full 40-char SHAs (gitlib short-SHA interpolation is unreliable).
- A new `src/*.kotoba` demo requires `clojure -M:dev:reproducible-emit regenerate` + bump the `:sources` count assertion in `reproducible_emit_test.clj`.
- clj-kondo treats warnings as failures (exit 2) on the kotoba CLJ lint gate — a nested redundant `let` in a new handler fails it; keep handlers flat. The gate is chronically red from pre-existing warnings + `/tmp/kotoba-lang-*` conformance misses; recent merged kotoba/lang PRs all landed via `gh pr merge --admin` — verify each lint/test failure is pre-existing (not yours) before doing the same.
- `data-edn`/`data-json` ops are NOT in `:string-head-host-ops`/`:data-head-host-ops` in guest-grammar — the kbb interpreter resolves them purely via `effect-for-kind` + runtime `op->kind` + host_providers, so a kbb-only capability needs NO guest-grammar.edn edit (avoid the multi-repo vendored-copy resync wave entirely).

kbb accepts `.kotoba` only — `.cljs`/`.cljc` are deliberately rejected inputs.

Under the kbb **sci backend** the surface differs again — reader conditionals
(`#?(:clj … :cljs …)`) in a `.cljk` helper can fail `No matching clause: ` (empty
tail) when the engine dispatches on neither tag for that file context; in
sci-backend helpers write the JS branch plainly and do JVM-free dispatch
dynamically. `(int c)` on a cljs char is 0 (chars are 1-char strings) — byte
fixtures must use `.charCodeAt`; see skill `java-kotoba-migration` for the full
kbb-replacement table (io/json/crypto/URLDecoder/Base64/exit).

Edge case that will recur if measured again: the full suite always reports standing pre-existing failures in `codebase_compile_test` (cache-key/definition-graph binding, `FileNotFoundException` on a missing `/tmp/kotoba-lang-*` conformance path) and `origin-assertion-test` (conformance file misses) — grep the run log to attribute FAIL/ERROR lines by namespace before claiming a wave regressed something; kbb/scan/string-search/data-json namespaces will be clean even when those others fail.

When the grammar authority (`kotoba-lang/lang/guest-grammar.edn`) changes, resync BOTH in-repo copies (`resources/kotoba/lang/` and `vendor/grammar/resources/kotoba/lang/`) and update the pinned digest in `guest_grammar_vendor_test.clj`. Residual classpath digest mixing from the pinned kotoba-sema vendor is a known standing failure, not yours.

## Kotoba pilot migration (kotoba/ directory pattern)

When migrating Clojure scripts to kotoba guests, create parallel kotoba files in a `kotoba/<module>/` directory structure **alongside** the original `.cljc` files, **not replacing them**. This pattern allows gradual migration with parity testing.

## Procedure

1. **Create kotoba directory structure**: Add `kotoba/<protocol>/` directory to your repo
2. **Write kotoba guest files**: Mirror the `.cljc` source using kotoba-admitted forms
3. **Add parity tests**: Create `test/..._kotoba_parity_test.clj` files to verify byte-identical WASM output
4. **Advance compiler pins**: When kotoba files change, advance deps.edn pins for amu AND kotoba-kir together

## File structure

```
org-ietf-smtp/
├── src/
│   └── org/ietf/smtp/
│       ├── protocol.cljc        # Original Clojure code
│       └── transport.cljc
├── kotoba/
│   └── smtp/
│       ├── protocol_core.kotoba
│       ├── protocol_commands.kotoba
│       ├── protocol_response.kotoba
│       ├── message.kotoba
│       └── session.kotoba
└── deps.edn                     # Must pin amu + kotoba-kir to commits that admit your kotoba forms
```

## Dependencies

Each pilot repo's `deps.edn` must include:
```clojure
io.github.kotoba-lang/amu
{:git/url "https://github.com/kotoba-lang/amu.git"
 :git/sha "<40-char-commit-sha>"}
io.github.kotoba-lang/kotoba-kir
{:git/url "https://github.com/kotoba-lang/kotoba-kir.git"
 :git/sha "<40-char-commit-sha>"}
```

**Important**: Advance both pins together when kotoba files change - the guest now relies on frontend type inference rather than explicit annotations, so stale pins reject valid kotoba code.

## Verification

1. **WASM byte-identity**: `kotoba compile` output must be byte-identical to previous versions after removing type annotations
2. **Parity tests**: `test/..._kotoba_parity_test.clj` files verify guest/host behavior matches
3. **Native capability**: `amu check --jvm-free` confirms kotoba forms work without JVM
4. **Type inference check**: After kotoba file changes, verify compiler pins match - stale pins cause `expected string, got i64` errors

## Pitfalls

- **Type inference dependency**: Pilot kotoba files rely on frontend inferring types from use. If pin is stale, compilation rejects with `expected string, got i64` or `typed parameters require alternating name/type pairs`
- **Compiler strictness on branches**: All `if` branches must have the same value type (both branches must be i64 or both be bool). Don't mix `true/false` with `0/1`.
- **count restrictions**: `count` only accepts bounded vectors, typed sets, or canonical typed maps. Using it on untyped lists causes `count requires a bounded vector... got :i64` errors.
- **API rate limits**: Scanning large orgs (>50 repos) without authentication triggers GitHub rate limiting. Use authenticated requests or batch with sleep delays.

- **Don't replace cljc files**: Keep `.cljc` files alongside `kotoba/` directory for gradual migration and parity testing

- **Pin advancement is mandatory**: When you modify kotoba guest files, you MUST advance both amu and kotoba-kir pins in deps.edn to commits that admit your forms

- **Use 40-char SHAs**: Short git SHA interpolation is unreliable for deps.edn pins

## Current status (measured 2026-09-07)

| Repo | kotoba files | Lines | Status |
|---|---|---|---|
| org-ietf-smtp | 5 files in kotoba/smtp/ | 710 | ✅ WASM verified |
| org-ietf-pop3 | 1 file in kotoba/pop3/ | 806 | ✅ WASM verified |
| org-ietf-imap | 1 file in kotoba/imap/ | 643 | ✅ WASM verified |
| org-chainagnostic-cacao | 1 file in kotoba/siwe/ | 11KB | ✅ window.kotoba |

**Org-wide scan**: kotoba-lang org has 100 repos total. Only the above 4 have `kotoba/` directories. Other repos (mail, mailer, org-ietf-cbor, org-ietf-ed25519, org-ietf-ical, aiueos, kototama, etc.) do NOT have kotoba directories yet.

## Org-wide kotoba directory scan procedure

**Warning:** GitHub API has rate limits (60/hour unauthenticated). For orgs with >50 repos, clone locally or use authenticated requests.

```bash
# List all repos in kotoba-lang org
curl -s https://api.github.com/orgs/kotoba-lang/repos?per_page=200 > /tmp/all_repos.json

# Batch check: check 5 at a time, pause between batches
count=0
for repo in $(jq -r '.[].name' /tmp/all_repos.json); do
  count=$((count+1))
  if [ $count -gt 5 ]; then
    sleep 10  # Avoid rate limits
    count=0
  fi
  result=$(curl -s https://api.github.com/repos/kotoba-lang/$repo/contents/kotoba 2>/dev/null)
  if echo "$result" | grep -q 'type.*dir\|type.*file'; then
    echo "$repo: HAS kotoba directory"
  else
    echo "$repo: no kotoba directory"
  fi
done
```

**Result (2026-09-07):** Only 4 of 100 repos have kotoba directories (org-ietf-smtp, org-ietf-pop3, org-ietf-imap, org-chainagnostic-cacao).
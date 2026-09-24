---
name: java-kotoba-migration
description: Migrate java.*/clojure.java.* usage to kotoba.* libs and pure .kotoba; create and publish missing libs
category: software-development
---

# java-kotoba-migration

Use when migrating Clojure/ClojureScript code from java.*/clojure.java.* usage to kotoba.* libraries or pure `.kotoba`, and when creating/publishing missing kotoba.* libraries.

## Standing preferences

- **Source files for new kotoba.* libraries are `.kotoba`, not `.clj`/`.cljc`.** Placeholders that merely re-export JVM interop are not a migration — implement the pure core in `.kotoba` (amu grammar) and keep I/O as capability-injected fns. Owner correction: file NAMES must be kotoba (`src/kotoba/<name>.kotoba`).
- **Self-checks return a failure COUNT, not a boolean** (0 = all green) — a boolean cannot distinguish "all cases pass" from "one case out of many failed".
- **Do not leave `java.*` interop in place when a pure replacement exists.** JVM-only I/O is not accepted as "JVM-only OK"; either inject the effect boundary or implement the capability in a kotoba lib.
- **Owner direction (applies to every migration): kotoba-ONLY, not "extract a pure bit".** When told to migrate a repo/component, port the whole public surface to `.kotoba` guests with a stated host boundary — a lone extracted predicate is not a migration. Oracle `.cljc` stays for parity tests; the component itself must not depend on running the oracle.
- **List before acting.** When the scope is "all repos", produce the inventory (which repos have kotoba dirs, which have `.clj/.cljc` remaining) and get the owner's pick before writing anything — enumerating 100 repos silently produces wrong scope.

## Verify by running the artifact (build/test green before landing)

`.kotoba`: `amu check` → `amu compile --target wasm32-browser` (multi-module
graphs: `amu module-lock ... --source-path <dir> --blocks <dir>` then
`amu compile --module-lock <lock> --blocks <dir>`; wasm32-browser target only —
the js-browser route refuses some graphs) → instantiate in Node with
`amu/runtime/browser-host.mjs` and assert exported values. `.cljc`/`.cljk` test
suites: `kbb -M:test` — the invocation is always kbb, never a Clojure CLI/bb/nbb
spelling; run the TEST path through kbb before landing a runtime cutover —
requiring all src namespaces proves nothing about the suite. A compile-only pass
does not prove the migrated code answers correctly.

**Run the suite BEFORE starting migration work, not after.** A runtime cutover
landed by a parallel session (e.g. a kbb cutover merge) can leave the test tree
broken on main while src resolves fine — require every src ns and see zero
failures, then run `kbb -M:test` and watch all 39 test namespaces die on
`clojure.java.io`. Grep the test tree for the JVM-only require before assuming
src-side green means suite-side green, and fix the pre-existing break in the
same branch rather than stacking new work on it. That repair rule applies to
cutover work; for ADDING new decision logic to a repo whose suite is JVM-bound
(src carries `#?(:clj [clojure.java.io :as io])` and `kbb -M:test` dies with
`Could not find namespace: clojure.java.io`), do NOT repair the suite as a
prerequisite and do not boot a JVM "just to check the suite" — the owner's
direction is JVM-free verification only. Scope the new logic as a pure
`.kotoba` guest (`repo/kotoba/<ns>/<name>.kotoba`, capability-free), verify
with `amu check --jvm-free` + `amu test --jvm-free` + a Node-side test script
over the host artifacts, state the suite's JVM-boundness in the report, and
leave the suite port as its own authorized wave (skill `kbb-test-suite-fix`).

Landing flow for org repos (measured working path, all main-branch):
clone to /tmp → write the guest → check + compile locally → commit to main →
`git push origin main` → confirm remote HEAD with
`gh api repos/<org>/<repo>/commits/main --jq .sha`. A repo checked out at a
detached HEAD reports `src refspec main does not match any` on push —
`git checkout -B main <verified-sha>` first. West checkouts name the remote
per-org (`kotoba-lang`, `net-kotobase`), NOT `origin` — `git fetch origin`
fails with "'origin' does not appear to be a git repository"; read
`git remote -v` first and use the org-named remote for fetch/push.

## Migration patterns

### Pure-core + injected-capability split
Application code that used `java.io`/`java.net` migrates by injecting the effect boundary as a fn parameter, not by swapping to a wrapper that still calls JVM classes:

```clojure
;; before
(:require [clojure.java.io :as io])
(defn read-people [path]
  (let [f (io/file path)]
    (if (and (.exists f) ...) (slurp f) [])))

;; after — default adapter, 2-arity for hosts without a filesystem
(def default-read-string!
  "clojure.core/slurp — no clojure.java.io require."
  (fn [path] (try (slurp path) (catch Exception _ nil))))
(defn read-people
  ([path] (read-people path default-read-string!))
  ([path read-string!]
   (let [content (read-string! path)]
     (when content ...))))
```
Keep the 1-arity so existing call sites and tests keep working.

### SHA-256 / crypto digests
Replace `java.security.MessageDigest` + `java.math.BigInteger` + `(format "%064x" ...)` with `sha2.core` (org-nist-sha2, pure `.cljc`): `(sha2/sha256-hex utf8-bytes)`. Wrap it in a shared `<ns>/digest.cljc` (string → UTF-8 unsigned bytes → hex) and have call sites require that.

### Decision core in `.kotoba`
For logic that must run natively, write `*_core.kotoba` under the amu guest grammar; host adapters stay `.cljc`. When a host script ALSO computes a value the guest computes (host measures axes, guest computes the composite), the host's copy must name the guest function it mirrors in a comment and keep the arithmetic identical down to operation order (clamp-then-integer-divide included) — the host sits outside the amu gate, so a silent divergence makes the host's published number a different quantity than the guest's, and no guest test can catch it.

## Creating a kotoba.* library (class procedure)

1. `mkdir -p <checkout>/src/kotoba`; `project.edn` at repo ROOT (not under `src/`) with `:name :version :description :license`.
2. Write the pure core as `src/kotoba/<name>.kotoba` (amu grammar).
3. Add an in-module self-check: `(defn self-check [] :i64 (+ (if (= <expected> (<fn> <args>)) 0 1) ...))` and `main` returning it. Probe in Node asserts both `main` and individual exports.
4. Verify: `amu check` → `amu compile --jvm-free --target wasm32-browser` → Node probe → commit → push.

### amu `.kotoba` subset-reject pitfalls (rewrite these shapes)

- `cond->` → chained `if`/`+` accumulation; `^:private`/metadata on defn → drop; `(mod x n)` → `(defn divisible? [y n] (if (= y (* n (quot y n))) 1 0))`.
- Type annotations are inline and per-parameter: `(defn f [x :i64] :i64 ...)`; mixing metadata-style `(param :i64)` fails with "parameter name expected".
- `subs` with computed indices has no admitted lowering — compute chars arithmetically via char codes instead.
- The compiled wasm module MUST export `main` (browser-host rejects otherwise): `(defn main [] :i64 (self-check))` plus `(:export [... main])`.
- `mkdir -p target` before `amu compile --output target/x.wasm`.
- browser-host needs `new Uint8Array(readFileSync(...))` — a Node Buffer is rejected as "must be an ArrayBuffer or typed-array view".

### Guest-grammar admission rules measured on the wave-1 tranche (20 repos, agent-dispatched)

Check these BEFORE writing a guest — every one was a real dispatch failure at least once:

- **Every defn a gate discovers must be IN the `(:export [...])` vector — including `test-*` defs and `main`.** `kotoba -M test`'s tests-in filters over `:exports`, not all defs; `main` missing → "entryless library" rejection on wasm targets. Adding a test fn without adding it to the export vector fails with `no exported test-* definitions`.
- **`if` branches must have the same value type** — mixing `true`/`false` with `1/0` in nested if branches fails `if branches must have the same value type`. Return booleans via `(and ...)` directly.
- **`count` on a string is rejected** (`count-receiver`): pass lengths as separate i64 parameters instead of calling `(count s)`.
- **`string-substring` uses codepoint indices; `string-byte-length` counts bytes** — UTF-8 multibyte characters shift byte indexes off codepoint boundaries and fail `string-substring-code-point-boundary`. Keep scanned fixtures ASCII or index in codepoints.
- **`kotoba -M` requires ABSOLUTE paths** — relative paths fail `:decode`/"input could not be read".
- `kbb` js host resolves file paths against the MAIN checkout, not a linked worktree cwd — a fixture that exists only in the worktree fails "path outside the granted scope"; copy it into the main checkout or commit it first.
- `kotoba.lang.text/split` takes a REGEX only (`#"/"`) — a string separator (`"/"`) crashes the host matcher with `Cannot read properties of undefined (reading 'includes')`, a message that names nothing about the argument type. Probe helper crash sites by asking "what TYPE did I pass?" first, not "what is broken?".

### Verification verdicts must be string-matched, not truthy-checked

`kotoba -M test` prints `kotoba test: N/N passed ...`. A gate script that collects raw output and treats any non-empty string as truthy records rc70 failures as passes — check for the exact `N/N passed` shape (or exit 0) before claiming green. This pattern also applies to `amu check` (`:ok true` in stdout) and to wasm-compile verification (`:ok true` + `:target :wasm32-kotoba-v1`).

### LLM agent dispatch for guest migrations (measured: 20/20 landed)

- Model selection measured across 5 models × 2 compilers: **mercury-2.5-preview 6/6 in ~2.5s** (fastest and most instruction-faithful); glm-5.3 fails instruction-following (reasoning exhausts token budget before content); deepseek-v4-flash 5/6. Use mercury-2.5 for agent-dispatched guest migrations.
- **Verify with the native CLI, not the JVM route**: `kotoba -M check/test/compile` (`kotoba -M test` prints `kotoba test: N/N passed targets=[:jvm-kir :js :wasm]`), plus `amu check --jvm-free`. `clojure -M:test` is auxiliary. Gate scripts must string-match `N/N passed` — truthy-checking raw output records rc70 failures as passes.
- Quality checkpoints: every ~10 slices, re-run all gates + verify pin freshness + append to an append-only ledger. When a recorded claim turns out overstated (gate script bug), append a correction amendment — never edit past entries.
- One agent = one repo = one slice, 5 parallel batches. Worktrees pre-created by the operator from synced origin/main.
- Dispatch prompts must embed ALL measured constraints (export-vector listing, main entry, same-type if branches, absolute paths, int-only) — agents repeat each failure class unless the constraint is in the prompt.
- **Agent self-report is not evidence**: after each merge, re-run `kotoba -M check/test/compile` + `amu check` on the landed files before advancing pins. Agents report success honestly but can miss a gate; re-running catches it.
- Advance many pins with `PINS=pins.tsv nbb scripts/west-pin-put-batch.cljs` (tsv: `name<TAB>sha<TAB>slug`) — 19 pins landed in one commit. Take SHAs from the GitHub API (`gh api repos/<org>/<repo>/commits/main --jq .sha`), NOT from shared-checkout `rev-parse` — checkout origin/main refs go stale during parallel work and the batch drops every entry as "already at that pin".
- Run a quality checkpoint every ~10 slices: re-run all 4 gates on every landed file, verify pin freshness (manifest revision == GitHub main SHA) for every repo, and append to an append-only ledger.

The measured-bounds table (max-parameters, `:container-items 32`, literal-only
i64 shifts, `rem`/`mod` spellings, throw-to-value, regex-to-positional) and the
full porting workflow live in `references/kotoba-guest-porting.md` — read it
before writing any `.kotoba` guest, and record any NEW backend refusal there
after re-measuring.

### Capability/wire replacement is now a 4-layer check (owner directive landed)

Before hand-porting a java.* capability, check FOUR layers in order — the
"missing library" is usually only one layer:

1. **capability repo** (`kotoba-lang/capability-<id>`) — the definition-CID
   contract + JVM reference provider (e.g. capability-hash-sha256 already
   ships provider.cljk).
2. **capability catalog** (`kotoba-lang lang/capability-catalog.edn`) — the
   compiler-wire-id declaration (`:hash/sha256`=3, `:clock/now`=7).
3. **kbb guest lib** (`kotoba lib/kbb/<name>.kotoba`) — the wrapper so scripts
   never spell a wire id.
4. **kbb js host provider** (`kotoba bin/kbb_js.cljk wire-ids` map) — the
   actual hosted set; catalog-declared does NOT mean host-hosted.

Landed as of kotoba main `0e90a4d8b`: wire 3 (:hash/sha256, node:crypto,
64-char lowercase hex, 65,536-byte request cap with :denied receipt) and wire
7 (:clock/now, ISO-8601 UTC) answer on the js host, plus `lib/kbb/hash.kotoba`
/ `lib/kbb/time.kotoba` wrappers. Crypto substitution: `kagi.crypto.noble`
(pure JS @noble/*: hybrid KEM, ML-DSA, AES-GCM, HKDF, REAL Argon2id — kbb
green) replaces jvm-provider/BouncyCastle; pure-cljc `ed25519.sign` (RFC 8032,
org-ietf-ed25519 + org-ietf-x25519 + org-nist-sha2 classpath) replaces JCA
Ed25519 sign/verify. Probe classpath recipe for `ed25519.sign` under kbb:
`"<consumer>/src:<ed25519>/src:<x25519>/src:<org-nist-sha2>/src"` — the
`edwards` module requires `x25519.field`, so x25519 is NOT optional.

**Canonical-encoder porting — the measured rules that byte comparison caught:**

- **base58btc is LSB-first long division**: process digits from index 0
  (least significant) upward — `digit*256 + byte`, store `mod 58`, carry
  `quot 58` toward the MORE significant end, append overflow digits at the
  END, render `reversed`. Iterating the digit vector in the opposite
  direction propagates carry the wrong way and leaves digits ≥58 in the
  vector — the symptom is `Index out of bounds` at the alphabet lookup, not
  a wrong answer. Zero-seed vector to pin the implementation:
  pubkey `3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29`
  + multicodec `[0xed 0x01]` → `did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVV
  QR3djdX3mDooWp` (the did-key spec's canonical zero-key DID). Do NOT use
  the popular `z6Mkhaux…` example as an expectation — it is a different
  key, and asserting against it fails a correct encoder.
- **did-key must be built host-side when the consumer is kbb**: `ed25519.core`'s
  did-key/b58 paths carry `#?(:clj BigInteger …)` bodies nbb drops, and the
  `:cljs` branch of `did-key-from-pubkey` produced a SHORT bogus did
  (`did:key:zK36`, 4 chars, measured) via its node:crypto-dependent branch.
  Build it in the host adapter: `(str "did:key:z" (b58btc (concat [0xed 0x01]
  pubkey)))` — pubkey from `ed25519.sign`'s pure `(:public sk)`.
- **CBOR encoder round-trip needs LENGTH parity, not just parse success**: after
  porting a CBOR writer, re-encode a decoded wire and assert the byte COUNT
  equals the original. A writer that emits trailing bytes the reader silently
  ignores (measured: 441 emitted vs 439 parsed) leaves unreachable bytes at the
  envelope tail, and a byte-tamper test that flips them still verifies green —
  tamper detection is off with no failing test pointing at the encoder.

### Remote already has history (placeholder from an earlier push)

```bash
git fetch origin
git merge origin/main --allow-unrelated-histories -X ours -m 'Merge remote placeholder'
# -X ours only wins CONFLICTS; files that exist only on remote still land — explicitly remove superseded placeholders:
git rm <remote-only placeholder files> && git commit && git push origin main
```

Re-check `git ls-tree -r --name-only HEAD` — placeholder `.clj` files reappear after this merge if you only relied on `-X ours`.

### Host-adapter consolidation (repo-wide java.* sweep)
When a repo has java.* spread across many files, do NOT patch each call site with a different workaround — create ONE adapter namespace (e.g. `<ns>.qualification-host`, `kotoba.net.jvm-host`) that owns every host primitive, and convert call sites to consume plain data from it:

- Capability-shaped: every fn takes/returns plain data — paths are STRINGS (`.getPath` inside the adapter, never a `java.io.File` leak), processes return `{:exit :out :err}`, and effect fns are injected so app code uses `kotoba.net/http-get`-style entry points and never sees java.* types.
- Fail-closed per fn: unknown HTTP method / missing resource / missing file throws or returns nil — never a silent default.
- `:replace-deps` test aliases drop transitive deps — when the adapter needs a library (e.g. sha2), add it explicitly to the alias pinned to the default-branch tip, verified reachable via `gh api repos/<org>/<repo>/compare/<sha>...main --jq '{status, behind_by}'` (`behind_by: 0`). Same for `:replace-paths`: an adapter ns living in `src/` must be added to the alias's `:replace-paths` or the require fails "on classpath".
- Verify the adapter LIVE before landing: real GET/PUT/PATCH/DELETE against httpbin.org (`:put` body echo must contain the sent body; unsupported method must throw). A compile-only pass on a transport is not evidence.
- Inline transports delegating to the adapter must PRESERVE their documented serialization semantics (e.g. JSON-body + edn-response wrappers) in the wrapper — do not let the delegation silently change the wire format.

### Batch-migrating a large app repo (dozens of inline transports)
When one repo has ~30+ files each carrying its own inline `java.net.http` builder+send block (the imperative `.send client req handler` style, NOT the functional factory style):

1. **Create a shim facade ns first** (e.g. `cloud.itonami.app.http-client`) exposing `(request {:url :method :headers :body}) -> {:status :body}` that delegates to the workspace adapter (`kotoba.net.jvm-host`). Do NOT try to preserve the imperative builder flow — every site rewrites to a single functional call.
2. **Dispatch 2 parallel subagents**, each with half the file list, an explicit per-site recipe (preserve url/header/body expressions verbatim; `.GET`→`:get`, `.method "X"`→keyword; keep surrounding auth/retry/JSON-parse logic; add require; drop unused imports), and the load-file parse-check command. Rule: agents repeat failure classes unless the constraint is in the prompt.
3. **Binary bodies**: the adapter is string-only. For sites that sent raw bytes (wasm, multipart, protobuf), base64-encode across the shim and decode in the caller — `BodyPublishers/ofString` would corrupt bytes >0x7f.
4. **Subagent summary failure ≠ failed edits**: a subagent can exhaust its token budget producing the final summary AFTER completing all file edits (the log shows tool calls succeeded). Before re-dispatching, diff the worktree — the first agent may have covered the second's file list; re-running the same files produces conflicts. Verify with the parse-check instead of trusting the dispatch report.
5. Verify ALL edited files with `load-file` parse-check (distinguish "reading source" = syntax broken vs "macroexpanding at 1:1" = deps missing, which is pre-existing), then land via one commit.

### Worktree must not resolve to the superproject root
`git worktree add <path-inside-superproject> <branch>` from a nested west checkout can land the worktree's toplevel AT the superproject root — a following `git add -A && git commit` then sweeps OTHER sessions' untracked WIP (including `.env`) into a commit on root main. Rules:

- Verify with `git -C <worktree> rev-parse --show-toplevel` immediately after `worktree add`; if it prints the superproject root, remove the worktree and re-add outside the superproject or at `orgs/<org>/wt-<name>` depth (required anyway when `deps.edn` uses `../../` sibling paths).
- If a bad commit already exists: `git reset --mixed <parent>` (uncommitted, unpushed) restores other sessions' WIP to untracked — nothing of theirs is lost. Nothing was pushed; do not force-push to fix it.

### Concurrency tests: CountDownLatch → promise/deliver with an atomic slot claim
Replace `java.util.concurrent.CountDownLatch` with clojure.core `promise`/`deliver`. Two measured traps:

- **Claim the slot atomically**: `(deliver (first (remove realized? promises)) ...)` races — both concurrent futures can resolve the same first promise and the second is never delivered, hanging the main thread on `deref`. Use `(nth promises (dec (swap! idx inc)))`.
- **Off-by-one on the claim counter**: `swap!` returns the INCREMENTED value; `(nth v (swap! idx inc))` indexes past the vector. Use `(dec (swap! idx inc))`.
- Guard the claim with a `claiming?` atom so reads issued AFTER the release (the "third request" assertions) do not index or block; clear it before `(deliver release true)`.
- Run the concurrency test 3× before landing — a deadlock shows as the test runner hanging forever, not as a failure.

### ns forms: never close inside a `;;` comment
A `)` at the end of a `;;` comment line is COMMENT TEXT — the ns form stays open and the reader consumes the whole rest of the file as "item N of list starting at line 1", reporting EOF far away. Put the closing `)` on its own line. Adding more `)` characters after the comment does not help; moving the closer out of the comment does.

### .clj files reject reader conditionals
Adding `#?(:clj ...)` to a `.clj` file fails `load-file` with "Conditional read not allowed" — reader conditionals are only legal in `.cljc`/`.cljs`. In a `.clj` file write the require plainly (it is always :clj). Note `(read-string ...)` in a `-e` context reports the same error even with `*read-eval*` bound — parse-check reader-conditionals via `load-file` of the real file, not `read-string`.

West checkouts sit on detached HEAD — never commit there. Worktree + server-side merge:

1. `git worktree add ../wt-<name> -b <branch>` — sibling depth (next to the repo, inside the superproject is fine for plain git without west; `/tmp` breaks repos whose `deps.edn` uses relative `:local/root "../../..."`).
2. Commit and push the branch.
3. Server-side merge (no local merge/rebase):
   ```bash
   gh api -X POST repos/<org>/<repo>/merges -f base=main -f head=<branch> -f commit_message=...
   ```
4. Delete the worktree, local branch, and remote branch.

## Verifying migration progress

```bash
gh api -X GET search/code -f 'q=extension:clj org:<org>' --jq '.items[] | .repository.full_name + " " + .path'
# also extension:cljs, q=java.io org:<org> language:clojure
```

The search index can be stale after a just-merged fix — confirm on disk with `git show origin/main:<path> | grep -c 'java\.'` before treating a hit as real.

### org-wide inventory survey (workspace-local, not the search API)
For a full workspace sweep, grep the local west checkouts instead of `gh api search/code` (the search API rate-limits quickly and its index lags). Recipe:

```bash
grep -rln 'HttpClient/newHttpClient\|HttpClient/newBuilder\|java\.net\.http\.HttpRequest/newBuilder\|new java\.net\.Socket\|ServerSocket\.' \
  <orgs-dir>/{cloud-itonami,kotoba-lang,network-awai,gftdcojp,etzhayyim,jk-luxury,com-junkawasaki,net-kotobase} \
  --include='*.clj' --include='*.cljc' 2>/dev/null \
  | grep -v nbb_compat | grep -v '/vendor/' | grep -v '.gitlibs' | grep -v '/target/' \
  | grep -v '/.nbb/' | grep -v '.claude/worktrees/' | grep -v '_wt-' | grep -v 'wt-' \
  | grep -v 'verify-kotoba' | grep -v 'cloud-itonami-app-worktrees' | grep -v '/test/' \
  | grep -v '/examples/' | grep -v '/verify/' | grep -v '/scripts/' | grep -v '/tools/'
```

Reading the result:
- **A file matching `java\.net\.http` may be docstring-only.** Count REAL call sites by grepping the constructor shapes (`HttpRequest/newBuilder`, `HttpClient/newHttpClient`, `HttpClient/newBuilder`) and excluding `;;` lines — raw `java\.net\.http` counts inflate by all prose mentions.
- Split the inventory into **HTTP transports** (mechanical: replace the inline `jvm-http-fn` body with `jvm-host/http-transport`, add the require) and **raw TCP** (`Socket`/`ServerSocket` — needs its own capability design, NOT the HTTP adapter).
- The biggest single cluster is usually one app repo with the same copy-paste fn in ~dozens of files — migrate the shared definition first if one exists, then the stragglers.
- Exclude worktrees/`verify-*`/`target/` dirs or you count the same code 2–4× and the numbers lie upward.

## Migrating JVM test harnesses (tests that touch the filesystem / randomness)

A JVM test that scans files or reads fixtures does not need `clojure.java.io` spread through its logic. Isolate the one filesystem touchpoint in a single adapter namespace and make everything downstream consume plain strings/vectors.

### kbb-era: ONE `test_host` adapter namespace (measured pattern)

A suite that must run under kbb gets a `test_host.cljk` in the test dir: require
`kotoba.lang.fs` + `kotoba.lang.fs-host`, build `(fs-host/host-filesystem {:root
<abs repo root>})`, and expose plain-data fns (`read-string!`, `read-edn!`,
`exists?`, `resource-edn`). Then point every `clojure.java.io` require at it.

Measured pitfalls:

- Derive the repo root from the adapter's own `*file*` — it is ABSOLUTE inside a
  required namespace under kbb (nil in `-e`; `js/__dirname` also nil). CWD differs
  between `npm test` and `kbb -M:test`, so a guessed relative root breaks one of the two.
- fs-host refuses `..` escape (`path refused: escape`) — reach a sibling checkout by
  computing an ABSOLUTE path from repo root, never a `"../../x"` literal.
- Absent-file semantics per fn: repo-local resources THROW when missing (a missing
  rule pack is a broken suite, not an empty one); sibling-checkout probes return nil
  so `when`-guarded tests keep their skip-when-absent behaviour.
- `nbb.edn :paths` does not inherit deps.edn's `:extra-paths` — add `"test"` to
  `:paths` or no test namespace resolves (see skill `kbb-config-and-classpath`).
- After a sibling rename, grep tests for hardcoded relative sibling paths — renames
  move source, not cross-repo test paths, and the old path keeps pointing at a
  checkout name that no longer exists (`ai-gftd-kabuto` → bare `kabuto`).
- Verify the current west-registered sibling path from the SUPERPROJECT manifest
  (`manifest/west.yml`), not from `ls` of the org dir, before hard-coding the new
  path — a worktree sits beside OTHER WORKTREES at `orgs/<org>/wt-<name>`, so
  `../<name>` resolves inside the org's worktree namespace, not to the real
  checkout tree. The adapter resolves siblings from `realpath(repo-root)/../..`
  (the orgs/ dir) + the full `<org>/<repo>` path, with the sibling root
  realpath'd BEFORE the fs-host `:root` handover (`..` inside a root is refused
  by fs-host's escape policy). `sibling-exists?` must also check the real path
  (`(.existsSync (js/require "fs") ...)`), not `(fs/exists? root "../<name>")` —
  the relative form hits the same escape refusal.
- When sed/python-rewriting regex literals containing `\"`, python's `.replace`
  mangles the escaping (`\\"` after round-trip) — verify edited regex lines by
  re-reading the file, not by trusting the replacement to be literal-safe. A
  one-off escaped-regex edit is safer done with an explicit line-index rewrite
  than a substring replace. Also re-run the require check AFTER the last edit:
  a python round-trip can silently change the nesting depth of trailing parens,
  and the suite only dies at parse time ("EOF while reading") on the next run.
- Batch-rewriting a `(let [...] ...)` block with python line surgery shifts the
  whole body's indentation (bindings at 12+ spaces) — re-indent the restored
  block by a constant, and check the `when`/`let` pair count changed by exactly
  the number of blocks you touched. `grep -c '(let'` before and after is the
  cheap invariant. Re-run the require check AFTER the last edit: a python
  round-trip can silently change the nesting depth of trailing parens, and the
  suite only dies at parse time ("EOF while reading") on the next run.
- A raw `(`/`)` count per file is the fastest way to LOCATE which file a batch
  python edit unbalanced (the parse error often names a line far from the
  surgery); treat it as a locator, not a verdict — it false-alarms on reader
  conditionals and escaped quotes, so confirm with a require of the flagged
  file before editing further.
- Before pointing call sites at a 1-arg adapter, convert multi-arg `(io/file
  "dir" name*)` joins into ONE path (`(str "dir/" name*)`) — a blind
  `(slurp (io/file dir name))` → `(th/read-string! dir name*)` replacement
  compiles against the 1-arg contract and then breaks at runtime with the
  fixture name treated as an argument the adapter never reads.

JVM-only suite (no kbb requirement) keeps the host-walk adapter fn
(fully-qualified call, no `:require` of `clojure.java.io`):

```clojure
;; Host adapter boundary: the only place this test touches filesystem types.
(defn- host-walk [root]
  (->> (file-seq (clojure.java.io/file root))
       (filter #(.isFile %))
       (mapv (fn [f] [(.getPath f) (slurp f)]))))
```

### kbb replacement targets — the measured mapping

- `clojure.java.io` (`io/resource slurp`, `io/file`, `.exists`) → `kotoba.lang.fs` +
  `kotoba.lang.fs-host` via the `test_host` adapter (`fs/read`, `fs/exists?`).
- `java.nio.file` (`Files`/`Paths`/`writeString`/`move` ATOMIC_MOVE) → the SAME
  `kotoba.lang.fs-host`: `host-filesystem` implements the IFilesystem protocol on
  BOTH runtimes in one namespace (`:clj` java.nio, `:cljs` node:fs), root-confined
  with `:fs/escape` refusal — so the migration target is fs ops, not a JVM interop
  shim. `rename`/atomic-move is NOT on the `IFilesystem` protocol: keep the
  temp-file+rename pattern as `fs-host` write + a host-side
  `(.renameSync (js/require "fs") tmp target)`. Owner direction (repo-wide):
  replace ALL `java.nio.file` with the kotoba fs libs — do not keep it behind
  nbb-compat shims.
- Cross-repo requires under kbb need the classpath/`NBB_CLJK_ROOTS` pattern (see
  skill `kbb-config-and-classpath`): every required repo's `src` on the classpath,
  and its repo root (plus dep-repo roots — never the superproject root together
  with a child repo) in `NBB_CLJK_ROOTS`. The `kotoba.lang.fs` dependency closure
  measured green: fs + text + fs-filesystem + fs-async-filesystem.
- `clojure.data.json` (`read-str`/`write-str`) → a `json_host` adapter:
  `(js->clj (js/JSON.parse s))` with STRING keys (matches production
  `(get doc "k")` reads) and `(js/JSON.stringify (clj->js x))`. Check src/ for
  `clojure.data.json` requires first — if production uses it, the mapping must
  keep its exact key/number semantics, not the test-side convenience shape.
- `Long/parseLong s 16` / `Integer/parseInt` → `(js/parseInt s 16)`.
- `java.net.URLDecoder/decode s "UTF-8"` → `(js/decodeURIComponent s)`.
- `java.util.Base64` (`getEncoder`/`getDecoder`) → `js/btoa` / `js/atob` —
  wrap non-ASCII input with `js/encodeURIComponent` first (btoa throws on
  multi-byte UTF-8).
- `java.security.MessageDigest` / `javax.crypto.Mac` (sigv4-style REAL digests
  in tests) → a `crypto_host` adapter over Node `node:crypto`: createHash for
  sha256-hex, createHmac for hmac. Verify the adapter against a published
  vector (e.g. AWS sigv4 get-vanilla signature) before trusting it — a stub
  digest passes every assertion that doesn't pin an expected value.
- **missing-library blockers are implemented AND published, not worked around**
  (owner directive): when the needed layer is absent, implement it and land it
  as a kotoba-lang org repo / PR, then consume it from the migration target.
  Check the THREE capability layers first (capability repo / kbb guest lib /
  kbb js host provider — see skill `kotoba-capability-extension`) because the
  "missing library" is often only the host provider. measured: pure-JS crypto
  already exists as `kagi.crypto.noble` (@noble/* covering hybrid KEM, ML-DSA,
  AES-GCM, HKDF, real Argon2id) and pure-cljc RFC-8032 Ed25519 lives in
  `ed25519.sign` (org-ietf-ed25519, needs org-ietf-x25519 + org-nist-sha2 on
  the classpath) — probe these BEFORE re-implementing a primitive that a
  first-party library already owns. Also check the JVM-only branch of the same
  library: `ed25519.core`'s did-key/seed paths carry `#?(:clj BigInteger ...)`
  bodies that nbb drops, so a name that requires fine under clojure can be
  unresolved under kbb — prefer the pure-cljc sibling (`ed25519.sign`) and
  build the did-key encoding in the host adapter (`"did:key:z" + base58btc of
  [0xed 0x01] ++ pubkey`).
- **Byte-exactness claims need a byte-level comparison, not an eyeball** —
  when porting a canonical encoder (base32/base58/CBOR/did-key), decode BOTH
  implementations' outputs in python (e.g. `base64.b32decode` after stripping
  the multibase prefix) and compare byte-for-byte, then fix the port until
  equal. Two real bugs this caught: a base32 fold that carried the PRE-drain
  bit count back to the outer loop (bits grow 8,11,14… and the arithmetic
  shift reads past the 32-bit accumulator), and a missing 32-bit mask on the
  `bit-shift-left` accumulator (cljs `<<` wraps to int32 — the reference
  implementation's semantics, so the port must mask 0xFFFFFFFF too).
- `System/exit` (test runner) → `(js/process.exit 1)` under kbb, or
  throw-to-value: report fail/error counts as a value; the non-zero-exit contract
  rides on the runner's own process exit, not a JVM static.
- `java.time` (`Instant/now`, `.plusSeconds`, `(str (Instant/now))` timestamps) →
  `kotoba.lang.time` (west repo: epoch-millis pure data + HOST-INJECTED clock +
  `->iso8601`; UTC ISO-8601 only, no tz DB). Replace `(str (Instant/now))` with
  `(time/->iso8601 (time/now clock))` and inject the clock once at the CLI/host
  layer — smallest diff. `java.util.UUID` → `(js/crypto.randomUUID)` (returns the
  string directly, no `.uuid` property). `System/getenv`/`getProperty "user.home"`/
  `System/exit` → `scripts.nbb-compat` (getenv / js process env / `(.homedir os)` /
  exit) — the workspace bridge already provides all of these; do not reimplement.
  `java.awt.Desktop.browse` → keep the existing `println "open in a browser:" url`
  fallback (desktop-open is an optional convenience, not a migration blocker).
  A bare `(:import [java.time ...] ...)` at the top of a `.cljk` file FAILS LOAD
  under kbb sci (`Unable to resolve classname`) even when the import is never
  used at runtime — strip the import list itself, not only the call sites.
  Crypto note (measured): `kagi.crypto.noble` (@noble/*, synchronous) passes ALL
  round-trips under kbb — rand/kem-keypair/aead-seal→open/argon2id m=262144 —
  and `npm install` in the repo is enough for the engine to resolve @noble/*
  from the repo's node_modules (no engine-side install). JVM-only Maven deps
  (e.g. bouncycastle Argon2) are therefore NOT needed on the kbb path when a
  noble/reference provider exists. Probe a library's pure-cljc/cljs sibling
  before reimplementing: the JVM branch of the same ns can require fine while
  every runtime call fails under kbb.
- `clojure.lang.ExceptionInfo` in `catch`/`instance?` → `:default` catch or
  `ex-info`-construction checks via `(ex-message e)` / `(ex-data e)` — the class
  symbol itself is unresolved under kbb's sci. In `(thrown? clojure.lang.ExceptionInfo
  ...)` test assertions the direct replacement is `(thrown? js/Error ...)`:
  under the sci engine `ex-info` throws a JS Error object (`(instance? js/Error
  (ex-info ...))` measured true), and `:default` catch still catches it.
- `#?(:clj A :cljs B)` inside a `.cljk` helper under the sci backend fails with
  `No matching clause: ` (empty tail) when the engine dispatches on neither
  `:clj` nor `:cljs` for that file context — reader conditionals are not a
  portable escape hatch in `.cljk` the way they are in `.cljc`. In test-only
  helpers write the JS branch plainly (the suite's runtime is JS under kbb);
  if a JVM leg must survive, feature-check dynamically or wire `clojure.data.json`
  onto the alias instead of guessing reader-conditional support.

### `(int c)` on cljs chars — the zeros-vector trap

**Never build byte vectors with `(mapv int text)` / `(map int text)` when the
suite must run on cljs/kbb — a cljs character is a one-character string and
`int` coerces it to 0, producing an all-zeros vector that silently corrupts
every byte-facing assertion downstream.** Use
`(mapv #(.charCodeAt (str c) 0) text)` (or the repo's existing `ascii-bytes`).
Two recorded sites this bit in ONE repo: a test `bytes-of` helper (all scan
fixtures became NUL bytes) and a src `wide-pattern` fn (every wide text atom
became unmatchable on this runtime). Both read as engine failures; grep src AND
test for `(map int` / `(mapv int` before theorizing. The same trap applies to
HMAC helper chaining: `(str byte-vector)` renders a Clojure vector string, and
`String.fromCharCode` + UTF-8 corrupts bytes ≥0x80 — pass raw byte vectors
through `(js/Buffer.from (into-array (map #(bit-and 0xff %) x)))` for BOTH key
and message sides of `createHmac`.

Also: a reader conditional INSIDE a vector literal element
(`[(:#?(:clj .. :cljs ..)) 0]`) compiles to a JS call form that fails
`t4.call is not a function` — bind the conditional in a `let` first, then put
the binding in the vector.

### Binary fixtures read from tests: check in a hex file, not a stream read
`.readAllBytes`/`io/input-stream` on a binary fixture (wasm, protobuf) cannot be replaced by `slurp` (text corrupts bytes) and `java.nio` is still java.*. Check the artifact in as hex text (`<name>.wasm.hex`) and decode with pure clojure:

```clojure
(->> (slurp "wasm/fixture.wasm.hex")
     (re-seq #"..")
     (mapv #(Integer/parseInt % 16))
     byte-array)
```
Generate the hex file from the artifact at migration time (`binascii.hexlify`). Regenerate it whenever the artifact changes — a stale hex fixture passes tests against a phantom binary.

### Deterministic shuffle: pure clojure replaces java.util.Random + Collections/shuffle
Use an overflow-safe LCG + Fisher-Yates (`swap!` an atom for the state):

```clojure
(swap! rng-state (fn [s]
                   (mod (+' (*' s 6364136223846793005) 1442695040888963407)
                        9223372036854775807)))
```

Use `*'`/`+'` (overflow-promoting), NOT `*`/`+` — plain `*` throws ArithmeticException long overflow on the first multiply with a large seed. Tests assert on convergence over shuffled orderings, not on a specific ordering, so an exact-sequence match with the JVM RNG is not required.

### Parse-check without building the classpath
When the repo's `deps.edn` cannot build a classpath (missing `:local/root` sibling checkouts, pre-existing gaps), verify syntax with `load-file` in a MINIMAL context: run from a directory with a trivial `deps.edn` (e.g. kotoba-net) and `load-file` the target by ABSOLUTE path — a parse error reports "Syntax error reading source at (<file>:line:col)", while missing deps surface later as macroexpansion/FileNotFound errors. Distinguish: "reading source" = syntax broken; "macroexpanding at 1:1" = syntax fine, deps missing. A crude python paren-balance counter miscounts reader conditionals and escaped quotes — the compiler is the only authority.

### Adding a git dep to a `:replace-deps` test alias
When a migrated test needs a library (e.g. sha2) that arrives transitively in the main deps but the test alias uses `:replace-deps`, the alias loses the transitive dep — add the library explicitly to that alias with a git sha pinned to the default-branch tip, verified reachable via `gh api repos/<org>/<repo>/compare/<sha>...main --jq '{status, behind_by}'` (`behind_by: 0`).

### Making an adapter lib consumable downstream
An adapter repo (e.g. kotoba-net) needs a `deps.edn` at its ROOT (`{:paths ["src"] :deps {}}`) before other repos can consume it via `:local/root` or a git dep with `:git/url` + `:git/sha` pinned to the default-branch tip. Without it, `:local/root` consumers fail with a lib-not-found classpath error. Pin SHAs from `gh api repos/<org>/<repo>/commits/main --jq .sha` and verify reachability (`compare/<sha>...main` → `behind_by: 0`).

### Runtime-repo (kotoba本体) host capability layer
`kotoba.host-providers` (the CLJ runtime's capability dispatch) is a legitimate migration target — its `http-fetch` provider constructs `java.net.http` directly. Replace the builder+send with `((jvm-host/http-transport ...) {:url ... :method :get})`, add kotoba-net as a git dep pinned to the tip, and keep the -1-on-failure sentinel semantics (`catch Exception _ -1`) — guests distinguish success via `string?`, not `if`. The remaining runtime files (launcher, codebase_publish/ipns/routing, wasm_exec) are behavior-critical and need their own careful pass, not a batch edit.

## org-level migration workflow

### Distinguish source repos from auto-generated worktrees

- **Source repos live in `~/github/com-junkasaki/orgs/`, `~/.gftd/kotoba-lang/`, `~/.codex-worktrees/`, `~/.gitlibs/libs/`**. Migrate `.clj`/`.cljc` files in these locations.
- **Auto-generated worktrees live in `~/.gftd/worktrees/`**. These are temporary/build artifacts (e.g., `kotoba-cli-build-bot/`, `kotoba-cli-build-verifier/`). **DO NOT migrate** `.clj`/`.cljc` files here — they will be regenerated.
- **Identify target directories first**: `find ~/github/com-junkasaki/orgs -type f -name '*.clj'` vs `find ~/.gftd/worktrees -type f -name '*.clj'`.

### File selection rules

- **Migrate**: `.clj`, `.cljc` → `.kotoba`
- **`.cljs` → `.cljk`**: the Clojure-shaped surface of this workspace is `.cljk`
  (the rename decision covers .cljs too). When a repo still has `.cljs` files,
  rename them AND sweep every remaining `.cljs` STRING reference in the same
  commit — an enumeration/filter over `.claude/hooks/*.cljs` silently returns 0
  after a rename (the artifact looks fine, the registry undercounts), and
  stale prose mentions mislead readers. Run the rewritten enumeration tool once
  and compare the count against the hook/skill directory listing before landing.
- **Keep as-is**: auto-generated `.cljc` (e.g., `catalog.cljc` created by scripts)
- **Check before migrating**: `find ~/.gftd/kotoba-lang ~/.codex-worktrees/kotoba-lang ~/.gitlibs/libs/io.github.cloud-itonami -type f \( -name '*.clj' -o -name '*.cljc' \)` before running migration.

### Keep `.cljk`-era cutover on MAIN green before/after every slice

A cutover merge from a parallel session can leave a repo's suite red while src
resolves (39/39 test namespaces dead on `clojure.java.io`, measured). Fixing
that break is the prerequisite for everything else in this class of work:
land the suite-repair first (see skill `kbb-test-suite-fix`), then build on the
repaired base. The same rule in reverse: re-run the repo's suite on MERGED main
before reporting done — the server-side merge can differ from the worktree you
verified.

### Migration pattern

1. Identify target orgs (exclude `~/.gftd/worktrees/`)
2. Convert files: `find . -type f \( -name '*.clj' -o -name '*.cljc' \) -exec sh -c 'cp "$1" "${1%.clj}.kotoba"' _ {} \;`
3. Keep same `ns` declaration in converted files
4. Verify with `find . -type f -name '*.kotoba' | wc -l`

## Prioritization

Count remaining files first (`find src -name '*.cl[jcs]' | wc -l`), migrate by java.* usage (interop in src/ first), then by repo. Decision cores move to `.kotoba` first; host wrappers remain. Test-infra JVM usage (io/resource, Files/createTempDirectory, shell) migrates last.
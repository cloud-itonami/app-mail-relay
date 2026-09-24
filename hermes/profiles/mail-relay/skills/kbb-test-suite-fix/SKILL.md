---
name: kbb-test-suite-fix
description: Use when repo tests fail under kbb after the JVM cutover.
---

# kbb test-suite fix (measured: cybersecurity, opencloud, zap-proxy, app-wvme — 2026-09-13)

A kbb cutover that fixes only src/ leaves the tests JVM-only. Four repos
landed green with this pattern (cybersecurity 358t / opencloud 75t /
zap-proxy 11t / app-wvme run_tests — all 0 failures, server-side merged).
The cutover ALSO renamed paths the tests probe by name (`.cljs`/`.clj`
fixtures, sibling repos, emulator servers) — a rename-following pass is
part of the workflow, see step 4.

## One adapter namespace per external surface, in test/

- `test_host.cljk` — all filesystem reads via `kotoba.lang.fs` +
  `kotoba.lang.fs-host` (root-confined IFilesystem). `io/resource`+`slurp`+
  `read-string` → `resource-edn`; `slurp (io/file p)` → `read-string!`;
  `.exists (io/file p)` → `exists?`; sibling checkouts → `read-sibling!`.
  For GENERATORS (not tests) the same pattern with the root set to the orgs/
  dir covers cross-repo reads/writes: see kbb-config-and-classpath
  references/kbb-jvm-free-generator.md for the measured fs-io template.
- `json_host.cljk` — `read-str` = `(js->clj (js/JSON.parse s))` with STRING
  keys (production reads `(get doc "k")`); `write-str` =
  `(js/JSON.stringify (clj->js x))`. NO reader conditionals — sci answers
  `No matching clause:` (empty) for `#?(:clj … :cljs nil)` in a .cljk.
- `crypto_host.cljk` — Node `node:crypto` SHA-256/HMAC-SHA256 with a real
  Buffer byte face. Verify against the published AWS sigv4 test vector.

## Pitfalls (all measured)

- **Skip-reasons that name a missing sibling mean the rename left the probe
  behind, not that the suite is fine.** A skipped conformance/emulator test
  prints the exact path it probed — fix that path and re-run; `skipped 0`
  is the only accepted green for a suite with skip machinery.
- **nbb.edn must declare deps**: kbb reads nbb.edn, NOT deps.edn; coordinates
  living only in deps.edn are invisible. Copy git shas byte-for-byte; the fs
  seam as `:local/root` (never a guessed sha). Mirror the test alias's
  `:extra-paths` into `:paths`. A repo with NO nbb.edn resolves nothing
  (zap-proxy shipped without one).
- **`(set! js/fetch …)` は nbb で "Invalid assignment target" で落ちる** — js/* グローバルへの
  set! は compile 済み参照に効かない。`(aset js/globalThis "fetch" …)` でも、
  **別ファイル（nbb にロード済みの src namespace）が持つ `js/fetch` 参照は置き換わらない**
  （identical? が true のまま実測 — テスト ns から src ns の内部 fetch を stub できない）。
  fetch を stub したいテストは fetch を受け取る pure fn にするか、fake transport を注入する
  設計にしてから書く。テスト対象が直接 `js/fetch` を呼ぶ場合は、テストを nbb で書かず
  shadow-cljs suite 側に置くか、関数レベルの境界から stub する。**つまずく前に確認する順**:
  `set!` が即死 → `aset` は黙って無効 → どちらも解決にならない。Stub 対象の呼び出しが
  実ネットワークに飛ぶ（403 が返る）ので「stub が効いた」と誤読する前に
  `identical? js/fetch js/globalThis.fetch` を対象 ns の呼び出し側で印字して判定する。
- **`(int "S")` / `(int \S)` = 0 on cljs** — chars are 1-char strings.
  `(mapv int text)` builds an all-zeros byte vector; use
  `(.charCodeAt (str c) 0)`. This hid in src `wide-pattern` too (every wide
  text atom unmatchable) and test `bytes-of`. A reader conditional INSIDE a
  vector literal fails `t4.call is not a function` — bind in a `let` first.
- **`clojure.lang.PersistentQueue` does not exist on cljs** — BFS queue as
  the amortized functional pair (pending front-list + in tail-list, reversed
  on drain); FIFO preserved; the spider test pins all pages.
- **HMAC with byte-seq keys/messages**: `String.fromCharCode` + utf8
  corrupts 0x80+; `(str byte-vector)` renders a vector literal. Use
  `(js/Buffer.from (into-array (map #(bit-and 0xff %) x)))` for BOTH sides
  of createHmac.
- String/JVM API swaps: `java.net.URLDecoder/decode e "UTF-8"` →
  `(js/decodeURIComponent e)`; `java.util.Base64` →
  `(js/btoa (js/encodeURIComponent s))` / `(js/atob t)` +
  `js/decodeURIComponent`; `Long/parseLong s 16` → `(js/parseInt s 16)`;
  `System/exit` → `(js/process.exit 1)`; `clojure.lang.ExceptionInfo` →
  `js/Error` (ex-info throws a js/Error under nbb).
- **`(js/writeFileSync fs …)` is wrong** — module functions use
  `(.writeFileSync fs …)`; `js/*` in call position looks up a GLOBAL.
- **Temp scripts for child kbb runs must live INSIDE the checkout** — a
  /tmp script loses the nbb.edn deps (`Could not find namespace`, measured).
  Write to `target/`.
- repo-root derivation depth varies per repo layout (cybersecurity:
  test/ai_gftd/cybersecurity = 4 segments up; opencloud: test/opencloud =
  2; zap-proxy lives at 2 too) — print the derived root once before
  trusting it.
- A docstring containing a quoted path (`("resources/x.edn")`) can fail
  the sci reader — drop the inner quotes.
- **resource同名は repo をまたいで別物になり得る** — 同一パス
  (`i18n/<name>/en.edn`, taxonomy edn など) が 2 repo の resources/ に別内容で
  存在する実測がある。fs-io adapter で repo 名を明示して読む (`<org>/<repo>/resources/...`)
  パスにし、「classpath に載ってたから」で 1 repo に寄せない。byte 比較で 2 repo 版の
  差異を確認してからどちらが本物かを決める。

## Namespace resolution + async HTTP services

- **Namespace file placement is `<paths-root>/<ns-dashes→underscores>.cljk`** —
  a `dispatcher/scan.cljk` directory does NOT resolve
  `wvme-dispatcher.scan` even when named in nbb.edn `:paths`; move it to
  `src/wvme_dispatcher/scan.cljk`.
- **JSON bodies travel with STRING keys** when the server does plain
  `(js->clj parsed)` — read both faces (`(or (:k body) (get body "k"))`)
  before deciding a required field is missing.
- **Sync loops cannot await fetch** — for a pure core that takes an
  injected sync fetch-fn, PRE-FETCH the crawl with awaited fetches one at
  a time, then hand the pages map as the fetch-fn's data; never try to
  make the sync loop await.
- `(js/Promise.resolve x)` works; `(.resolve (js/Promise.) x)` fails
  `Promise resolver undefined`. `(js/writeFileSync fs …)` is wrong —
  module functions use `(.writeFileSync fs …)`; `js/*` in call position
  looks up a GLOBAL.
- **Nested fn literals are rejected** — `#(… #(…))` fails; bind inner
  steps with explicit `(fn …)`.
- Mock req/res objects need defensive checks (`(if (.-on req) (bind) (cb ""))`)
  — js-obj test doubles lack instance methods real sockets have.
- argv layout under `kbb --backend sci script.cljk args…`: [node, cli.js,
  script, arg1…] — user args start at index 3. Guard any auto-start on
  "argv contains this file's own name" so a bare require doesn't bind a
  port.
- A JSONC config a strict `JSON.parse` reader consumes must stay
  comment-free — wrangler accepts JSONC comments, the self-description
  suite does not.

## Workflow

1. Worktree outside the superproject checkout, branch from origin/main.
2. Probe kbb capability set first (`clojure.edn`/`clojure.string`/
   `kotoba.lang.text` work; `slurp`, `Long/*`, `System/*`, `data.json`,
   `PersistentQueue` do not).
3. Write adapters, batch-replace with python re, then require EVERY test ns
   as SEQUENTIAL top-level `(require 'ns)` forms and COUNT fails before
   running the suite — failures surface one at a time otherwise. Do NOT
   collect the requires into a `(doseq [ns '[…] (try (require ns))])` loop:
   after a doseq-require the runner sees `Ran 0 tests containing 0
   assertions` even though nothing failed — the loop's requires do not
   register the namespaces the way top-level forms do (measured on kenchi
   under kbb/sci). And trust only the PRINTED face of the summary —
   `(:test r)` / `(:pass r)` on the `run-all-tests` return value read back
   nil under sci; grep the `Ran N tests containing M assertions` line
   instead.
4. **Follow the cutover's renames in the test tree** — grep the test files
   for `.cljs` / `.clj"` / stale sibling names after a rename wave: tests
   probe files by literal path (self-description suites, emulator-availa-
   bility probes, fixture loaders) and a rename empties those probes into
   `skipped`/ENOENT. Two real classes hit this session: a conformance
   suite's emulator probe (`server.cljs` → `server.cljk`) silently skipped
   23/23 tests, and an agent-registry hook filter counted 0 hooks. Fix the
   PATH, not the assertion — the test's skip-reason text names the file.
5. **Register every NEW `.cljk` in the repo's `cljk-origin.edn` in the same
   commit** — an unregistered file dies `cljk: invalid-origin` on the first
   require, and a file merged by a parallel session without registration
   makes an unrelated task's first require fix the manifest first. Cheap
   pre-landing gate: `grep <filename> cljk-origin.edn`.
6. Run the suite, classify failures (engine bug vs test fixture bug), fix
   each class once, land: push branch → `gh api …/merges` → re-run suite on
   main → cleanup worktree/branch.
7. **When a migration cannot finish in-session, hand the next session a
   resume table, not a narrative**: which ns is the ceiling, the measured
   replacement for each resource (repo + byte count verified), and the exact
   NBB_CLJK_ROOTS/--classpath line that went green. A cross-repo run needs
   the orgs/-rooted fs-io adapter pattern (see kbb-config-and-classpath
   references/kbb-jvm-free-generator.md).

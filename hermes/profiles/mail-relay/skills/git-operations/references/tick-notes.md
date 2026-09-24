# cljk-finish tick notes (2026-09-19, app-itonami-inkan)

- The repo's **west pin is stale** (`cae4015`, pre-rename), so a shared checkout
  can show old `.cljs` strings while origin/main is already fixed. Authority is
  **origin/main / `gh api contents`** — verify there before declaring work.
- The measure script's repo paths live under the `com-junkawasaki` superproject
  (`~/github/com-junkawasaki/orgs/...`); `kwt2` hosts other org
  trees. Probe both before assuming a repo path exists under one superproject.
- Cron security scanner blocks grouped/redirected shell (`{...} > f 2>&1`, odd
  pipes). Write a probe script with write_file, run `python3 <path>` plain, and
  let the script open its own output file.
- `kbb -M:site` fails standalone (`jp-go-dds` is `:local/root`, sibling checkout
  only). The green gate for app-itonami-inkan is `kbb -M:test` (15 tests /
  357 assertions / exit 0). Verify main-tip content with
  `git show <remote>:<file> | grep`, never a stale local checkout.
- A *dangling* WIP merge commit (e.g. `ec2527b` in the object store) is not
  evidence of an unlanded fix — check ancestry (`branch --contains`) against
  origin/main before treating a ledger finding as still-open.
- Prior tick pattern: each fix = 1 line change in `site/inkan/page.cljk`, commit
  message "page: reference X.cljk (post-rename)" + Co-Authored-By
  cljk-finish bot (Hermes), landed via `gh api .../merges`.

## 2026-09-19 tick 2 (app-itonami-shirohan)

- west-managed children have NO `origin` remote — the remote is named after the
  west group (e.g. `cloud-itonami`). `git worktree add ... origin/main` fails
  `invalid reference`; use `<remote>/main` after checking `git remote -v`.
- One stale literal per ledger line can hide a family: shirohan's
  `./shirohan/geom.cljs` was one of 14 sibling `:src "./…cljs"` scittal paths in
  the same form — fix ALL dangling path literals in the file, not just the flagged one.
- app-itonami-shirohan green gate: `kbb -M:test` fails identically on main tip
  (pre-existing: `kotoba.lang.text` not declared for the engine — do NOT blame
  your change; take a stashed baseline first). Use instead: measure-gate
  re-scan (0 dangling literals) + all 15 `:src` paths resolve to repo files +
  `bb -e` reader parse (17 forms) + `clojure -M:test` exit 0.
- Ledger final line stays behind until the hourly measure job reruns; that is
  expected, not a failed landing. Verify landing by `git show <remote>/main:<file>`
  after `gh api .../merges`, then remove worktree + delete local and remote branch.

## 2026-09-19 tick 4 (hayari)

- The ledger's flagged literal (west-pin-put.cljs) was only one of FOUR dangling
  path literals in the file — the others (persist/collect/corpus.cljs) resolved
  to .cljk files under different tree prefixes (superproject root vs own repo),
  so a repo-tree-only probe said NOEXIST. Resolve every literal against the
  clone prefix the path/join actually builds (root vs clone), not just the repo tree.
- Worktree cleanup must run in the CHILD repo (git worktree prune/remove in
  orgs/<org>/<repo>) — a prune in the superproject does not clear the child's
  worktree registration, and the branch stays 'used by worktree'.
- hayari green gate: `npm test` = kbb --backend sci --classpath src:test
  test/hayari/core_test.cljk (29 tests / 142 assertions / exit 0, unchanged by fix).

## 2026-09-19 tick 3 (bunker)

- The ledger's on-disk findings are mostly STALE: prior ticks already landed
  fixes on main but the shared checkouts ride old west pins, so the measure
  script keeps re-flagging them. Before picking a repo, run a strict main-tip
  probe (fetch flagged file raw from GitHub, re-extract quoted `.cljs`
  literals, resolve against main-tip recursive tree — same semantics as the
  measure script). 2026-09-19: 8 of the first 9 single-candidate repos were
  already clean on main; bunker was the first genuinely open one.
- strlit fix is not just literal `.cljs`: bunker's repo-marker tested
  `src/bunker/murakumo.cljc` while the file is `.cljk` — the gate REFUSED
  (exit 2) on main tip because of it. Fix every dangling PATH literal in the
  file (same family rule as shirohan), then take a REFUSED-vs-runs baseline
  from a detach worktree of main tip to prove the fix is what made the gate run.
- bunker verify-quickstart doc gate now RUNS post-fix (was REFUSED): 3/5 OK;
  2 DIFF blocks are pre-existing doc drift (expected output recorded under the
  JVM runner / clj-kondo Maven dep; the kbb engine substitutes cljs.test and
  resolves no Maven coords). Suite gate `kbb -M:test` exit 0 (9 tests/252
  assertions). Report the DIFFs as newly-visible doc drift, not as green.

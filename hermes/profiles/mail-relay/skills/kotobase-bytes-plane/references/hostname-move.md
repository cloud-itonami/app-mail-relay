# Hostname move checklist (bytes/wiki/search → yataverse zone)

Landed twice 2026-09-14 (wiki ADR-2609141707, search ADR-2609141815). Shape: **new host canonical on the same Worker + old host = legacy 301 helper, not retired** (ADR-2609131630, net-kotobase/ipfs#68).

## Leaf repo order
1. Edit wrangler.jsonc: add the new hostname to custom domains; keep the old one (Worker serves it as the 301 helper — compare `:hostname` against the legacy literal, lowercase).
2. Worker: 301 helper preserving path+query; non-GET/HEAD on legacy host → 405. `str/lower` is in kotoba.lang.text; `str/lower-case` is NOT (undeclared-var build break).
3. Update ingest source / docstrings / README to the new host BEFORE the first ingest.
4. Build route (if .cljk sources): copy ipfs' gen-shadow-cljs-edn.cljk + template; `:source-paths` needs the real mirrored src dirs (cljk-mirror alone misses `resource/inline`); keep template `:dependencies` (browser jars — reagent/re-frame — are NOT in the gitlib dirs gen mirrors); use `clojure -Spath` for dirs; generated shadow-cljs.edn stays untracked (gitignore).
5. npm run build + npm test + node smoke (canonical 200 with real query hits, legacy 301 Location, non-GET → 405) BEFORE deploy.
6. Move any untracked stale artifact (index_bundled.cljs → /tmp) so shadow doesn't resolve the stale ns first.

## Deploy + measure
- npm run deploy attaches custom domains. Measure live per-hop: legacy header shows 301 + correct Location; with -L final URL = canonical, nredirect=1.
- HEAD on GET-only routes returning 404 is pre-existing worker behavior, not a regression — compare against pre-move.
- Update the maintainer bot's smoke/measurement script in the same pass: canonical probes + one legacy line asserting the 301 Location preserves path+query (per-hop, no -L). Back up every touched profile file as `<file>.bak-<stamp>` first — SOUL/script edits have no undo.

## Superproject landing
1. west-pin-put.cljk <name> HEAD --message (single-entry commit) → verify.
2. Worktree branch: ADR .kotoba (adr-<stamp>-<slug>, write_file NOT heredoc; docs-edn-check gate must show no NEW fails — baseline has ~6 pre-existing FAILs) + manifest/repository-rules.edn :policy/capability-origins + gate fixture literal in the SAME commit.
3. Landing order: FF main → branch from origin/main → push → gh api …/merges (returns merged:null with a sha — verify by fetching and cat-file the path on main).
4. kagami reconcile --db <worktree>/manifest/fleet-db.edn --west <worktree>/manifest/west.yml (west.yml must exist in the worktree: git show origin/main:manifest/west.yml > …). Worktree sparse-checkout: git sparse-checkout add 90-docs/adr BEFORE git add or the ADR file silently won't be indexed.
5. Stale worktrees/branches: retire after merge; worktree-retire.cljk scans ALL worktrees (slow — background or filter).
6. index.lock older than ~30min with no git process (ps verified) = orphaned, safe to remove.

## Policy file gotcha
manifest/repository-rules.edn nests under :workspace-policies → :live-service-durable-data → :policy/capability-origins; .kotoba files need the adl-decode path ((map …) call-form → map) to read — see manifest/edn-query.cljk loader.

## Known broken (do not "fix" blindly)
scripts/verify-kotobase-persistence-policy.cljk crashes under kbb (indexOf on null) — same on pristine origin/main. Policy+ADR correctness was measured with a direct reader script instead.
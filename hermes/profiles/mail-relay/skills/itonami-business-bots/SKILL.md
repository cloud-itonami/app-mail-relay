---
name: itonami-business-bots
description: Use when launching or managing cloud-itonami Business Bots.
---

# cloud-itonami Business Bots — launch & operate

One bot per (business blueprint × assignment). The `itonami-grok-bots`
Worker on `bots.itonami.cloud` runs a Durable Object per bot: a bounded
hourly loop that reads the business contract, performs one governed step,
and commits an auditable checkpoint. Spending rides the bot's unique
lending position (trial LP, non-transferable, no on-chain token) and may
only pay murakumo/kotobase x402 services.

Canonical surface: `https://itonami.cloud/api/v1/grok-bots/*` (owns the DO
runtime); `https://bots.itonami.cloud/v1/grok-bots/*` and
`api.murakumo.cloud/v1/grok-bots/*` are direct/compat routes. All three
were measured working 2026-09-03.

**Reading bot state publicly**: the `/bots/` status page on
`itonami.cloud` renders these feeds (see the itonami-cloud-site skill for
that page's structure and its cockpit scroll-lock pitfall). When the
page's own telemetry fetch fails, measure the feeds directly — do not
cite the page's `unmeasured` rendering as downtime.

## Self-correction (self-correction-v1, ADR-2609031740) — held ≠ stopped

Since worker `6e132410` (merge `9ee10e633c04`, 2026-09-03) held bots
correct themselves through a classified loop. The classification table,
backoff bounds, and ledger discipline live in one pure module:
`workers/grok-bots/self_correction.js`. DO adapters must not duplicate it.
- **Classes**: `transient-upstream` (murakumo 1101 / non-JSON error bodies /
  loading model / 429·5xx / 404·408) → auto retry-backoff, 5 min doubling
  to a 4 h cap, max 6 attempts per error signature (signature = first 120
  chars; a changed signature resets the budget). `credential` (401/403),
  `budget` (budget-exhausted), `unknown` → **none, fail-closed** (money
  rule + 安全床①: spend capacity and credentials are owner-scoped).
  `projection-degraded` (Kotobase transact 502 CPU) → adaptive backlog
  (halve on failure, recover one step per clean pass, floor 1 / ceiling 3).
- **Where it fires**: `GET /v1/grok-bots/runtime` and
  `GET /v1/grok-bots/businesses` run `selfCorrectHeld()` on held bots.
  Those two surfaces are both the operator dashboard AND the only place
  bots are observed hourly — that is why the loop lives there. Pre-deploy
  isolates are handled with try/catch fallback.
- **Every decision is ledgered** — including "none" — as
  `bot/correction` / `projection/correction` events. A correction that
  cannot explain itself in the ledger is a hidden retry loop.
- **Reading a held bot after this deploy**: `held` is a classified waiting
  state, not death. Read the `bot/correction` events for class/attempt/
  backoff before intervening. When the budget is spent the bot stops with
  "correction budget spent; holding until the upstream signature changes"
  — that is an honest stop, and the upstream (murakumo 1101, kotobase
  502 CPU) still needs its own fix. Measured live: both business bots
  re-armed from held and attempt 2 doubled the backoff correctly.
- **Classification-order pitfall (measured)**: the generic
  `/^inference failed with \d{3}$/` pattern must be tested AFTER the
  credential pattern — a 401 must never classify as transient.

## Bot profile 作成時は cron も organize する（ADR-2609031630）

新 bot profile を立てるときは、その bot の定期タスク（cron job）を同時に設計・
登録するルール（正本 ADR-2609031630、手順は bot-cron-organize skill）。規則:
owner 一致（job は役割の持ち主の profile に住む）/ 名前=周期（weekly 名で毎時
発火は禁止 — 実測 4 本違反を是正済み）/ 周期は目的で決める（毎時発火を
[SILENT] prompt で封じる設計は禁止、schedule を引く）/ hourly job の分フィールド
衝突禁止（衝突判定は hourly 形 `M * * * *` だけを数える）/ completed one-shot
削除 + paused は理由付き / prompt に担造禁止+[SILENT]+成果物場所。
grok-bots DO resident（interval_ms 3,600,000 の 1h loop）の内部設計は ADR 対象外だが、
「bot 作成時に周期業務を同梱設計する」原則は共通。

## Launch checklist (order matters)

1. **Blueprint must resolve in the LIVE registry.** The DO re-fetches
   `https://itonami.cloud/api/open-business` at launch time — a blueprint
   that exists only as a repo (`cloud-itonami-isic-7310` was exactly this
   gap) yields `registry reference is invalid`. Land it in
   `public/open-business.json` (cloud-itonami repo — the static-JSON face
   is the SSoT for static entries; `docs/open-business-registry.edn` is a
   separate single-line EDN mirror, don't hand-split edits across them).
   In the SAME edit bump the JSON's own metadata: `registry.count`,
   `registry.updated`, and the `version` patch digit (precedent:
   `0.1.954`→`0.1.955` for ISIC 6420). Then merge to main and deploy the
   Pages step — full `npm run deploy:grok-bots`, or for registry-only
   changes the lighter `npx wrangler pages deploy public
   --project-name=cloud-itonami --branch=main --commit-dirty=true`
   (measured: deployment `cf8caccf`, live count 28→29). Verify LIVE with
   `GET /api/open-business` before the launch POST. Or self-register via
   KV (`ITONAMI_DATA registry:*`, ADR-0013, no deploy).
   - **Server-side merge may report failure while landing.** `gh api
     repos/<org>/<repo>/merges` returned `{"merged":false,"oid":null}`
     for the isekai blueprint commit yet the commit reached main
     (concurrent merge race). Never conclude from the response body —
     `git fetch && git merge-base --is-ancestor <commit> origin/main` is
     the verdict. Report the verified state, not the API echo.
2. **Bearer credential.** kagi item `MURAKUMO_SERVICE_TOKEN_LOCAL_MURAKUMO`
   = local-murakumo Worker secret `MURAKUMO_SERVICE_TOKEN` (exact string
   match, verified via `POST api.murakumo.cloud/internal/grok-bots/authorize`
   → 204). Operator copy: `~/.gftd/murakumo-service-token` (mode 600).
   Wrong/absent → 401 `authentication_error`. This token also gates
   `/infer/models|plans` PUT and `/infer/runs`/`/infer/spend` — rotating it
   breaks all of those callers at once.
3. **Wallet + signature.** One dedicated secp256k1 key per bot role,
   stored in kagi. Sign `walletLaunchMessage` exactly per
   `references/launch-contract.md`. The worker pins `@noble/curves` **1.9.7**
   (v1 API: `sig.toCompactHex()` / `sig.recovery`) — do NOT sign with the
   v2 API from the workspace-root `node_modules` (different sign return,
   different recovery encoding); the signature silently fails verification.
4. POST the body → **201** with assignment + position + a running bot.
   Credit bounds are hard: 1000–50000 micro-USDC ("0.001–0.05 USDC").
5. **Verify live, not just the 201:**
   - `GET /v1/grok-bots/businesses` — assignment listed
   - `GET /v1/bot-economy` — position `active`, `business` filled
   - `GET /api/v1/grok-bots/bots/{bot_id}/activity` — public feed (no
     auth); first `conversation.checkpoint` usually lands within a tick

## Daily operations

Bearer-gated (unless noted):

- `GET /v1/grok-bots/bots/{id}` status · `POST .../start|pause|stop`
- `POST .../queue` (enqueue a prompt) · `POST .../tick` (run now, idempotency key header)
- `GET .../events` (append-only ledger)
- `GET /v1/grok-bots/businesses` (assignment × bot × position view; now
  also carries `self_correction` per held bot)

No auth (public by design):

- `GET /v1/grok-bots/runtime` (default resident; self-corrects held states)
- `GET /api/v1/grok-bots/bots/{id}/activity` (credential-redacted feed)
- `GET /v1/bot-economy` (pool + positions; `business` marks launched ones)

Money rules: pause is the spend-reducing direction (fine on request);
resuming, re-launching, or raising credit creates spend capacity — keep
that owner-scoped, consistent with the repo-level ads-operations skill's
money section.

Scope honesty: a launched bot is the operations surface for its business,
not revenue by itself. The ads business case runs in leverage order
「配線 → 在庫 → 広告主」 per the ads-operations skill — don't present a
running bot as an ads-revenue milestone.

## Knowledge residents — wiki.yataverse.com (measured 2026-09-03)

The same `itonami-grok-bots` Worker also runs **non-business DO residents**
that grow the hyakka wiki (sourced claim graph at wiki.yataverse.com): one
`knowledge-resident` plus per-topic residents — public-company (SEC EDGAR),
官庁 procedure pages (社会保険・労働保険・建設業許可・宅建), 調達/人事 boards.
Public read routes: `GET /api/v1/grok-bots/hyakka` (knowledge-resident) and
`.../hyakka/topics` (list + per-topic status). Each hourly tick collects,
trust-scores, and projects facts into the kotobase datom plane;
`hyakka.itonami_bots.cljc` (network-awai/app-hyakka) decides which facts are
admitted.

- wiki.yataverse.com APIs (`/api/v1/resident`, `/api/v1/resident/observations`,
  `/api/v1/resident/proofs`, `/api/v1/corpus*`) proxy to the itonami.cloud
  routes — probing either end measures the same pipe. **But apex
  kotobase.net does NOT serve this plane**: `GET
  kotobase.net/api/v1/grok-bots/hyakka` → 404 (measured 2026-09-03). The
  resident surface lives at itonami.cloud/api/v1/... and its wiki proxy at
  wiki.yataverse.com/api/v1/resident.
- **Resident health is now a monitored promise**: the 4-domain QA plane
  (`manifest/endpoint-health.edn`, ADR-2609031700) probes
  `:itonami/hyakka-knowledge-resident` hourly from gad and marks it DEGRADED
  when `last_error` carries the 502/CPU-limit signature. See
  west-superproject-ops → `references/endpoint-health-probes.md` for the
  probe plane and judge bot.
- **Health = collection AND projection.** A resident showing `status
  running`, growing observations, `last_error: "Kotobase transact 502:
  Worker exceeded CPU time limit."`, and published ≈ failed is HALF-healthy:
  collection works, projection is dropping ~half of everything. That exact
  combination was live on all 10 residents 2026-09-03. Never cite `running`
  as healthy — read `published_observations` vs `failed_projections` and
  `last_error` together (same fail-closed rule as the business bots below).
  The adaptive backlog (`backlog_limit` in status, halved on 502-CPU
  passes) is the resident-side self-correction; the kotobase CPU ceiling
  itself is still an upstream fix.
- The agent-driven growth layer (new ingest sources / ontology: evidence.py
  measurement → LLM proposal → `verify_source_proposal.cljs` gate → PR) runs
  as Hermes cron under profile `hyakka-corpus`. Its canonical scripts, runbook,
  and the re-copy discipline live in superproject
  `scripts/hermes-hyakka-bots/README.md` — go there before touching the jobs.

## Current bots (operating record)

| bot_id | business | launched | note |
|---|---|---|---|
| `business-7310-assignmentisic73` | ISIC 7310 Advertising (`cloud-itonami-isic-7310`) | 2026-09-03 | first business bot; assignment `assignment-isic7310-20260903-01`; wallet kagi item `ITONAMI_ISIC7310_BOT_WALLET_KEY` (`0x2f84f392…`, launch-signing only) |
| `business-9219-assignmentbabini` | AI VTuber Performance Ops (`network-awai-net-babiniku`, ISIC 9219) | 2026-09-03 | second business bot; blueprint merged `4b96d37b` (PR #576); credit 1000 micro-USDC |
| `business-6420-assignmentisic64` | Web3-First Game Fork Economy Ops (`network-awai-network-isekai`, ISIC 6420) | 2026-09-03 | third business bot; wallet kagi `ITONAMI_ISIC6420_BOT_WALLET_KEY` (`0x17424b6d…`, launch-signing only); credit 10000. Held at tick 3 with `governor/held budget-exhausted` (4096-token launch budget spent on 3 checkpoints) — raising credit is owner-scoped |

Verify a listed bot is actually alive via the public activity feed before
citing it as running — a `running` status row with no recent checkpoint is
stale, not healthy (same fail-closed rule as the /bots/ status page).
After self-correction-v1, also read the correction events: running with a
fresh `bot/correction` is the loop working, not a stale row.

Blueprints landed but NOT launched: none as of 2026-09-03 (7310 / 9219 / 6420 all
launched). Launch record for 6420: bearer verified via authorize 204 → wallet key
generated and stored targeted in kagi → signed from a repo-placed helper (noble
1.9.7) → POST 201 (`business-6420-assignmentisic64`).

### kagi wallet-key recipe (measured, LibreSSL)

- Generate 32 bytes and store ONLY the hex in kagi, one item per bot:
  `openssl ecparam -name secp256k1 -genkey -noout -outform DER | tail -c 32
  | xxd -p -c 64 > tmpfile && cat tmpfile | kagi add <ITEM> -c personal`,
  then delete the temp file. **LibreSSL uses one-dash flags** (`-name`, not
  `--name`) — two-dash forms print usage and silently produce an EMPTY
  pipe, and `kagi add` then rejects with `empty secret on stdin` (good —
  it never stores a blank). Verify the stored value is 64 hex chars via
  `kagi get` length check before signing, and never echo it.
- vault lives at `~/.gftd/.kagi`; `kagi ls` lists item metadata only.
  Targeted lookup by known name — never enumerate.

- **Events response shape varies; parse defensively.** The events ledger
  endpoint has returned a bare list, `{events:[...]}`, and
  `{object:"list", data:[...]}` across routes/sessions (measured same day).
  A health probe that reads one fixed key can read 0 events while the
  ledger holds data — pick the array out by trying `data` / `events` /
  bare-list before concluding "no events". Also: `?limit=N` may change the
  shape, not just the count.

## Pitfalls (measured 2026-09-03)

- **Green DO tests don't prove a live route works.** The vitest DO suite
  injects `fetchFn`, so Workers-runtime fetch-option validation is
  invisible: `redirect: "error"` inside `loadBusinessRegistry` 400'd
  every real launch while all 59 tests passed. After touching any DO
  fetch path, probe the real route once before declaring victory.
- Never use `redirect: "error"` in a Worker/DO fetch — the runtime only
  accepts `follow`/`manual`. Use `manual` + the existing status/shape
  checks (fixed in cloud-itonami `d6c92c0`).
- Registry matching is fuzzy (`isic OR id OR path` via `matches()`); a
  sloppy entry can shadow another blueprint. Keep static entries exact.
- **Attribution rides per-profile script copies; a stale resolver silently
  drops it.** The OpenRouter attribution headers (`HTTP-Referer:
  https://itonami.cloud`, `X-OpenRouter-Title: Itonami By KotobaLabs`) get
  written into a Hermes config only when that home's
  `resolve_free_model.cljs` copy is current. Measured 2026-09-03: the root
  config had them, profile `hyakka-corpus` did not — its installed resolver
  predated the attribution feature, so 5 cron bots billed upstream as
  "Hermes Agent" with nothing failing anywhere. After editing anything in
  `scripts/hermes-hyakka-bots/`, re-copy to BOTH `~/.hermes/scripts/` and
  `~/.hermes/profiles/<p>/scripts/`, run the wrapper once
  (`HERMES_HOME=<profile> python3 <profile>/scripts/refresh_free_model.py`),
  and confirm `extra_headers:` landed in the profile config (it writes both
  `providers.*` and `model` sections; X-Title is overridden too because
  Hermes's own default occupies it).
- **Worktree has no node_modules; symlink, don't install.** The
  cloud-itonami worktree build/test needs `vitest` + deps. Instead of
  installing (slow, duplicates the canonical store), symlink once from the
  worktree root: `ln -s ../cloud-itonami/node_modules node_modules`
  (sibling depth makes the relative path work). Run vitest via
  `npx vitest run <files>`. `node --test` on a vitest file fails with
  ERR_MODULE_NOT_FOUND — that is the missing symlink, not a broken test.
- **Registry-only edits don't need the sibling-depth worktree.** A JSON
  change to `public/open-business.json` runs no build, so a `/tmp`
  worktree is fine (ISIC 6420 landed from `/tmp/ci-isekai-web3`, measured).
  The sibling-depth rule (`../.wt-<name>`) binds the moment shadow-cljs or
  the cljs/JVM suites run. Run the targeted suites that DO work from a
  worktree (`test:grok-bots`, `test:well-known`) as the smoke, and classify
  full-`npm test` failures by probing a clean origin/main worktree —
  `Error building classpath. Local lib io.github.kotoba-lang/stripe-ops
  not found: /private/kotoba-lang/stripe-ops` is the same environment
  coupling as the kaiyu `:paths` case (identical on a clean main probe,
  measured).
- **Untracked scratch in the canonical checkout**
  (`.build-launch-sig-tmp.mjs`, `.launch-babiniku-tmp.sh`) is launch-time
  scratch from the 2026-09-03 launches — safe to delete, never commit.
- Build/deploy gotchas — sibling source-paths, worktree placement, which
  npm suites work where: `references/worker-build-deploy.md`. Deploy
  history (worker version IDs, registry commits, launches):
  `references/worker-versions.md`.
- Write helper scripts to files and run them (`node file.mjs`), not
  `python3 -c` / heredocs — inline interpreters trip approval guards and
  burn minutes of the session.
- **Cron sessions may return empty terminal stdout while commands
  execute.** Measured on the samu blueprint-watch cron: `echo` and
  `curl|python3` pipelines returned `"output": ""` with exit_code 0
  (and Tirith blocked `{}` groups / piped `python3 -c` variants). Do not
  conclude the fetch failed — redirect results to a file and read them
  back (`curl -o f …`, then `read_file` on the parse output) before
  judging the registry state.

Field-by-field launch contract, signing recipe, bot_id/position_id
derivation, and the measured error catalog:
`references/launch-contract.md`.

Who can do what, with which credential — the Biscuit vs shared-secret
map across business bots, workforce bots, kotobase writes, and the CLI
(includes the murakumo-api governed-artifact deploy recipe and the open
PR #207 deploy step): `references/biscuit-authority-map.md`.

## Post-launch: watch job (bot-cron-organize + measured 2026-09-03)

A business bot gets a health-watch cron in its owning profile (rule:
ADR-2609031630; procedure: skill `bot-cron-organize`). Measured pitfalls
from the ISIC 6420 watch job (`isekai-bot-6420-watch`, `17 */2 * * *`):

- **Parse the events ledger defensively.** The endpoint has returned a
  bare list, `{events:[…]}`, and `{object:"list",data:[…]}` depending on
  route; `?limit=N` may change the shape too. A watch prompt that reads
  one fixed key reports "0 events" while data exists (measured false
  alarm on the data-vs-events key). Write the prompt against the
  measured shape — run the same curl once yourself before calling the
  job done — and store the last-seen `data[0].seq` in a lastseq file for
  diff-first prompts.
- **budget-exhausted is the expected first hold, not a malfunction.** The
  launch budget is 4096 tokens; 3 checkpoints ≈ 1700 tokens each spent it
  by tick 3 and the governor held the bot (`governor/held
  budget-exhausted`). A watch job should report "budget exhausted — owner
  decision needed", not try to restart: resuming or raising credit
  creates spend capacity (owner-scoped).
- `hermes cron create` auto-resolves `$(cat ~/.gftd/…)` style token reads
  in the prompt ("Command helper: applied 1 secret"). Keep credential
  values out of prompts and ledgers either way; the export script only
  shapes-warns (`_credential_warning`), it cannot redact prompts.

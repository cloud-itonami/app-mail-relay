---
name: hermes-fleet-cron-health
description: Use when auditing Hermes bot profiles or cron fleet health.
---

# Hermes bot fleet / cron health audit

## Inventory (workspace root = ~/.hermes/profiles/)

- Profiles: count dirs under `~/.hermes/profiles/`. Cron jobs: parse `~/.hermes/profiles/<p>/cron/*.json` — each file is `{"jobs": [...]}` with per-job fields `enabled`, `state`, `paused_at`, `last_status`, `last_error`, `failure_streak`, `last_run_at`, `next_run_at`, `schedule_display`, `repeat.completed`, `last_dispatch` (has `scheduled_at`/`dispatched_at`), `deliver`.
- Exclude `*.bak-*` profile dirs (user keeps backup clones; they inflate counts).
- `cronjob_manage` only sees the CURRENT profile's jobs — cross-profile audit must read the JSON files directly.
- Gateway profiles are the ones with a live process: `ps aux | grep 'hermes_cli.main.*gateway run'` and extract `--profile <name>`. A profile with cron but no gateway means its jobs fire only if some supervisor loop starts it.

## Scoring (measured recipe)

Job-level: last_status ok/error/delivery_failed; failure_streak>0 multiplies risk; never-run jobs are NORMAL when created <7 days ago with a future next_run_at (weekly/new) — only never-run AND old AND next_run past = stalled. Profile score: ok-rate over ran jobs, minus streak*0.03 (cap 0.3), minus stalled fraction*0.4, minus delivery_failed fraction*0.2, minus 0.1 if no live gateway.

Error classification by last_error substring: "Gateway shutdown (post-interrupt)" = collateral of a gateway restart wave (batch, self-heals next fire); "HTTP 429" = capacity (reschedule off the busy slot) — but "429 daily free quota exhausted" is QUOTA exhaustion (recurs every UTC day until the tier/key changes, reschedule won't fix); "HTTP 402 requires more credits / would exceed available credits" = billing exhausted on the paid route (a tier/top-up decision, not scheduling); "truncated" = response too big (lower frequency); "Connection error" on a NEW profile's first runs = missing API keys in the profile's `.env` (config.yaml alone does not carry keys — copy the key lines from the template profile); "TERMINAL_CWD read lock" = two cron jobs sharing a workdir (separate workdirs); "REFUSED" = script-level precondition, not scheduling. **"drift_skip" / "Skipped to prevent unintended spend: global" is a SPEND-GUARD, not a fault** — the job intentionally skipped to avoid token cost and fires again next tick. On a price-aware fleet 1/4 to 1/3 of "error" jobs are this class; separate it out BEFORE computing error rate or you over-report a healthy fleet as ~20-30 errors when the true break-count is single digits. See references/error-classification.md for the full bucket table.

Host discriminator: loadavg/core-ratio. `sysctl -n vm.loadavg` (first value) against `sysctl -n hw.ncpu`. Ratio >3 = host overload; a wave of identical interrupt/shutdown errors at that point is one host event, not N bot failures. Do not reschedule for it.

Per-job `provider`/`model`/`base_url` (+`provider_snapshot`/`model_snapshot`) fields in jobs.json OVERRIDE the profile config default — a fleet model switch that only changes profile configs leaves the pinned majority untouched. Before any switch, tabulate jobs by their own model/provider fields (inherit vs pinned) and count fires/day from each schedule expr (minute/hour multipliers); that table is the real switch scope.

last_error fields are live snapshots that mutate between scans (jobs re-fire while you audit) — re-run the scan right before finalizing counts, and don't quote a scan taken more than a few tool calls ago.

Caveat on script-not-found errors: validate the script currently exists (`ls <profile>/scripts/<name>.py`) before trusting a "Script not found" line — it may be a stale snapshot from before the script was placed, so the fault is already healed and needs only a re-fire to confirm.

## Tool-backend topology check (FIRST, before any desktop/profile diagnosis)

When the user reports a Hermes desktop or profile error, do NOT assume your file tools run on the same machine as the desktop app. Determine the topology first, in one call: `hostname; id -un; echo $HOME` via terminal AND `os.environ['HOME']` via execute_code — they can disagree. If the paths in the user's error stack (e.g. `/Users/<otheruser>/.hermes/...`) do not exist from your shell, your terminal backend SSHes into a different host: say so explicitly, report which paths are unreachable, and hand the user the exact local commands (grep over `~/.hermes/profiles/*` for the failing bot's URL/name, `hermes cron list`) instead of concluding from empty greps.
- A grep returning 0 hits on an unreachable path is NOT evidence of absence — it is unmeasured. Run one `ls -d <path>` control first; only a pass on an existing dir counts.
- vision_analyze and similar file-path tools reject non-ASCII paths (Japanese screenshot filenames fail with an ASCII-only error). Ask the user for an ASCII-safe copy or the image content instead of retrying the same call.
- Browser/PTY-based backends may be routed elsewhere again — one failed browser_exec with empty stdout is not proof the tool class is broken; verify via the workspace path it echoes before generalizing.

## Report format (owner expects)

対象 corpus / 追加 datoms / 台帳 seq / 異常の有無. Lead with totals (profiles, jobs, ok rate), then score tiers, then error classes with counts, then concrete per-job fixes.

## Provider switch on a 401 wave (executed 2026-09-16, 29 profiles)

When a fleet-wide `HTTP 401: Invalid credential` wave hits profiles whose `.env` carries the key (value stale, not missing — existence check passes), switch those profiles to a working provider end-to-end in one scripted pass: `providers.kotoba` (base_url https://api.kotoba.cloud/v1, key_env KOTOBA_API_TOKEN) + `model.default` + `fallback_providers` + every `auxiliary.*` endpoint, then copy the working token into each profile's `.env` (backup `<file>.bak-<stamp>` first). Verify per-profile by re-parsing (provider+model+token present), then re-fire enabled jobs with `HERMES_HOME=<profile> hermes cron run <id> --accept-hooks`.

## Fleet switch to self-owned kotoba endpoint

kotoba.cloud is in-house: no cost gate, max token utilization. Measured unit cost (2-point probe, paid microUSD): in ≈$0.90/M, out ≈$4.50/M — verify fresh before quoting.

Three job classes need different treatment on a model switch:
1. **Pinned jobs** (job-level provider/model fields override the profile default): edit the profile's `cron/jobs.json` directly (backup `<file>.bak-<stamp>` first), setting provider+model+base_url together. `hermes cron edit --provider kotoba` REFUSES when the job's stored `base_url` doesn't match the provider's configured endpoint (`...may only be sent to its own configured endpoint`).
2. **Pin-less jobs** (provider/model `None`) run on their CREATION-time snapshot (`provider_snapshot`/`model_snapshot`), NOT the current profile config — a config-only switch leaves them on the old model, and the provider rejects it at fire time. Fix with `hermes cron resnap <id>` (adopts the current global default, stays unpinned and tracks future changes). Detect them by `provider is None and model_snapshot not in (None, '<new model>')`.
3. **Never switch**: multimodal/vision jobs (kotoba endpoint rejects image content arrays) — leave on the vision-capable fallback.

**The fallback chain masks a broken primary.** When the primary provider 400s (`'<old-model>' is not a valid model`), the run silently falls to `fallback_providers` (openrouter-free glm), so agent.log shows the fallback model while the switch looks applied. The same masking happens with TRANSIENT primary failures: after a fleet switch, glm calls in agent.log can be the endpoint's one-off timeout/5xx followed by `API call failed ... Retrying` then a glm success in the same conversation — this is the fallback working as designed, NOT stale cron state. Before declaring success, grep agent.log for the failure line BEFORE the first API call; the log line `running on creation-snapshot provider 'custom' (global default is now 'X'); hermes cron resnap ... adopts the new default` is the tell for class 2. Distinguish the two by checking jobs.json: if every job's provider/model/base_url is already the new route, remaining glm calls are transient fallback; only stale snapshot lines mean class 2 work remains.

Verify a switch with ONE manual `hermes cron run <id> --accept-hooks` and read the profile's `logs/agent.log` for `model=... provider=...` on the first API call; execution rows land in `cron/executions.db` as `running`. Re-audit post-switch executions from executions.db since the switch timestamp (completed/failed/running) rather than trusting jobs.json last_status, which still shows pre-switch errors.

When spawning `hermes` from the Python kernel, use the full venv path (`~/.hermes/hermes-agent/venv/bin/hermes`) — `subprocess` does not inherit the interactive shell's PATH; and close each sqlite connection (`finally: con.close()`) — opening hundreds of executions.db handles trips the open-file limit.

## Profile model-section auth and budget pitfalls

- A profile `config.yaml` whose `model:` section carries `provider` + `base_url` but no `key_env` sends requests WITHOUT an Authorization header — HTTP 401 "Missing Authentication header" even when the token sits in `.env` and `providers.<name>` is complete. Fix: put `key_env: <VAR>` beside `base_url` in the `model:` section itself, then re-fire. Also seed every fallback provider's key into the same `.env` (fallback 401 on the quota day is what turns one 429 into a total tick failure).
- An agent-type cron job with no `run_budget_seconds` will, on a day when primary quota exhaustion forces the weak fallback model, thrash for 75+ minutes / 240+ API calls across cross-repo searches and end `failed` with zero landings. Set an explicit run budget on every migration/land-style agent job (the utsushi bot's 1200s is the proven floor for worktree+verify+land); when triaging a long-running tick, check agent.log API-call count and /tmp artifact count — that is the drift signature.

## Quota budget before a mass re-fire

api.kotoba.cloud free tier is 1000 requests/UTC-day per key, and EVERY agent turn + auxiliary call counts. Firing ~30 profiles' jobs at once drains it mid-wave; after drain every job returns `429 free-quota-exhausted` until 00:00 UTC even though auth is fine. Rule: before mass-firing, compute jobs × runs/day + aux multiplier against the quota; if over, widen intervals (spread the minute per job) BEFORE firing. A 429-free-quota error after a successful provider switch is the quota, not a failed switch — the 401→429 transition on the same jobs is itself the evidence the switch worked.

## Pitfalls

- **Parse ONLY the profile's `cron/jobs.json`, never glob `cron/*.json`.** A cron dir is full of non-job JSON — `*.state.json`, `usage_audit.jsonl`, `ticker_*`, `models-latest.json` — whose shapes vary (some have `{"job": ...}`). Reading them as job definitions inflates the fleet count and produces phantom "unnamed" jobs. Restrict to exactly `<p>/cron/jobs.json` per profile. Also handle both shapes in one path: the file is `{"jobs":[...],"updated_at":...}` on most profiles, so unwrap `data.get('jobs', data)` and accept a bare list.
- Schedule edits: write the profile's cron JSON directly with a `.bak-<stamp>` copy first; clear `next_run_at` so the scheduler recomputes. But a running gateway may hold cron state in memory — changes are guaranteed only after the gateway restarts.
- Restarting gateways after cron/config edits: per-profile gateways are launchd labels `ai.hermes.gateway-<profile>` (the main multiplexer is `ai.hermes.gateway` and serves every profile when `gateway.multiplex_profiles` is on — restart THAT one, not per-profile ones, for config-only changes). `launchctl kickstart -k gui/$(id -u)/<label>` is the restart; a label can sit in `spawn scheduled` for several minutes afterwards (transient EX_CONFIG churn) while KeepAlive brings it back — verify with `ps` for a fresh `gateway run` process rather than re-kickstarting in a loop, which prolongs the churn. A per-profile plist whose profile the multiplexer already serves loops on exit 78 (EX_CONFIG) forever with the log line `already serves profile '<p>'` — the jobs still fire via the multiplexer, so the churn is cosmetic; the durable fix is `launchctl bootout gui/$(id -u)/<label>` (or deleting the plist), not restart attempts. Gateway restarts also kill any in-flight cron execution (executions.db row stays `unknown` with a 'Scheduler restarted' error) — prefer restarting between tick waves, and treat those rows as next-tick self-healing.
- Clustered daily schedules at the same minute across profiles cause capacity 429s — spread them.
- A run stuck in `running` far past its model/provider timeouts is a hang, not progress — check `logs/agent.log` for the last completed tool call, then close the execution row manually (status='unknown') so the next tick is not blocked. If the gateway also shows a post-update restart warning, restart it first.
- These are LOCAL files; this session cannot push them anywhere. Reports are local-only in CLI mode.
- Load the surrounding system too when explaining errors: `uptime` loadavg vs core count, top CPU processes. A wave of identical errors at the same minute across many profiles = host-level event (restart/OOM), not N separate bot failures.
- External supervisors SIGTERM ALL gateways together (one shared parent). After an audit, re-check `ps` before claiming a gateway is down — a wave of "Gateway shutdown killed the job's tool subprocess" errors usually means every gateway was mid-restart and the fleet self-heals; do not reschedule jobs for it.
- Per-run detail lives in the profile's `cron/executions.db` (sqlite: job_id, started_at, status) and per-run markdown in `cron/output/<job_id>/` — the JSON only carries last-run fields. Bot report files (実測 datoms / blockers) are the ground truth for what a bot actually did; read the newest one before claiming a job is healthy.
- A shared bot workdir left checked out on a stale `bot/*` branch silently blocks sibling cron jobs that share it (they stop with 0 work instead of erroring — check job output for 'not FF-able against origin/main' as the tell). Remediation: verify the branch's PR is CLOSED/superseded (`gh pr list --state all --headRefName <branch>`), archive untracked WIP into `.wip-archive-<date>/` inside the worktree (never delete), `git checkout --detach origin/main` (a local `main` may be owned by another worktree — plain `checkout main` fails there), then `git push origin --delete` the stale branch and re-fire the blocked job with cronjob_manage action='run'.
- **A stale 0-byte `.git/index.lock` in the SHARED superproject checkout blocks every git tool there** — and a `git fetch` process from a DIFFERENT repo's mirror (e.g. a fleet-ci mirror under `~/.gftd/fleet-ci-cache/mirrors/`) shows up in `ps` and looks like the lock's owner but is not. A 0-byte lock older than ~15 min with no git process whose command references THIS repo's path is a crashed process's lock; remove it and retry. First thing to check when several unrelated repos' scripts suddenly report "Unable to create index.lock".
- When scoring, distinguish never-run: created <7d + future next_run = normal pending; never-run + old + next_run past = stalled. Counting pending as failed inflates the failure count ~30% on a young fleet.
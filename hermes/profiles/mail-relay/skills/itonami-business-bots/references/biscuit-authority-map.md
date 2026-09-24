# Biscuit authority map — who checks what, measured 2026-09-03

Class-level answer to 「それぞれの bot の権限は Biscuit で制御される?」.
Principles: **認証 (passkey / SIWE) と権限 (Biscuit) は分離** (ADR-2608291700);
Biscuit is the DELEGATION WIRE, `kotoba-lang/authority` (`covers?`/`meet`)
is the ONE DECIDER (ADR-2608180200). kotobase auth maturity measured
100/100 (`npx --yes nbb --classpath 90-docs
90-docs/kotobase_auth_maturity/run.cljs` — re-run it, never quote the
number as standing).

## The credential classes

| class | prefix | minted by | verified by | carries |
|---|---|---|---|---|
| inference | `mrb_` | authn `/v1/murakumo/token` (passkey session) | `/rpc/verify-inference` (authn) | model + maxOutputTokens ceiling |
| operator | `mro_` | authn `/v1/murakumo/operator-token` (passkey session **+ `MURAKUMO_OPERATOR_DIDS` allowlist, checked at mint AND verify — removal = revocation**) | `/rpc/verify-operator` (authn; local-murakumo holds NO root key by design) | actions from closed vocab `models` / `restore` / `grok-bots` |
| kotobase graph-scoped | (opaque) | authn `/v1/biscuit/token` (service-account secret exchange) | kotobase datom plane (Biscuit-required; CACAO → 401) | exact graph + `data:read`/`data:write` + 15-min TTL |
| workforce bot | (opaque) | cloud-itonami-app `bot_authority.clj` (fleet root seed `workforce-authority.seed`, 0600) | same process `bot-authority/admit` + edge verifiers | `kotoba://cap/<workforce>/<capability>` scopes, `:autonomous` decisions only |

Attenuation is the only thing a Biscuit fold can produce (meet never
widens). Workforce bots can attenuate their OWN token since 2026-09-01
(owner decision; `bot-identity/bot-signing-seed`).

## Consumers (measured)

- `operator-admitted!` in local-murakumo consumed ONLY database-restore
  routes until 2026-09-03. **PR #207 (merged `e23d338`) wired `models`**:
  `PUT /infer/models/:id` now admits `mro_` OR the shared service bearer
  (additive — rotation stays a fleet-wide event) OR CACAO fallback.
- **`grok-bots` action is still mintable-but-unacceptable** — the
  vocabulary has it, no route consumes it. Wiring it = the grok-bots DO's
  `authorized()` (currently a shared-secret round-trip to
  `api.murakumo.cloud/internal/grok-bots/authorize`, which is itself just
  `service-authorized?` exact-string compare) gaining a second, additive
  `mro_` path.
- **The `itonami` CLI has NO Biscuit surface** (`itonami commands biscuit`
  → matched 0; `credentials verify` is SD-JWT VC, trusted-issuers empty).
  `auth login` is enrollment-key (0600 in data dir) → agent-session token →
  Keychain — deliberate, documented in `agent_session.clj` (a process that
  can read the store can mint any session anyway). Do not "fix" this into
  Biscuit without an owner decision.
- workforce `admit` actually decides only tools in `tool->capability`
  (workspace_write_file / git_commit → `:patch.create`); read tools are
  deliberately unmapped. No token is held between ticks — issue → verify →
  decide per check.

## Deploying local-murakumo changes (governed artifact — measured 2026-09-03)

`npm run build` fails with `CLJS input changed: refresh the governed
release artifact` when `src/**` changes: `release/cljs/manifest.json`
pins a source digest. The refresh is the JVM path (npm `shadow-cljs` is
NOT an alias): `npm install --no-save shadow-cljs@2.28.20` then
`java -cp "node_modules/shadow-cljs-jar/bin/shadow-cljs.jar:<deps.edn :paths>
" shadow.cljs.devtools.cli release worker` (plain `npx shadow-cljs release`
fails — the npm CLI wrapper cannot locate shadow on the Clojure
classpath), through the resource-guard `build` scope. Commit refreshed
`manifest.json` + `worker.js.gz` together. **This session ended with the
refresh incomplete — PR #207 is merged but NOT deployed; a future session
must finish it and live-probe `mro_` → 200 on the models PUT.**

## Related fixes this session

- **gad VPC short-circuit threw raw 1101 for `murakumo-main`** (PR #206,
  deploy `20e80d69`): the short-circuit decision reads the RAW model string
  BEFORE KV alias resolution, so the alias id rode the gad path and threw
  instead of failing over — while b70 answered 200. Fix:
  `DEDICATED_HOSTED_MODELS` includes `murakumo-main`; KV alias repointed
  `qwen3.8-27b` → `qwen3.8-27b-throughput-b70`. Every bot pins
  `FLEET_MODEL_ALIAS = murakumo-main`, so alias health = all bots' health.
- `CHECKPOINT_ID` migration in grok_bot_runtime.js rewrites any `qwen*`
  config id back to `murakumo-main` ON READ — you cannot pin a bot to a
  concrete checkpoint id via configure; the alias is the only lever.

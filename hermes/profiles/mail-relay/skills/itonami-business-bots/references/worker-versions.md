# Landed worker versions (itonami-grok-bots)

Append-only operating record. After each deploy, add one line: date,
worker version ID, what it carried, and the live probe that verified it.

| date | worker version | carried | verified by |
|---|---|---|---|
| 2026-09-03 | `df3f63f1` | redirect:"manual" fix in DO fetches | `GET /v1/grok-bots/runtime` + one real launch |
| 2026-09-03 | `6e132410` | self-correction-v1: `self_correction.js` classifier + `selfCorrectHeld()` on runtime/businesses GETs + adaptive hyakka backlog; merge `9ee10e633c04` | live probe: held 7310/9219/default re-armed to running, `bot/correction` events attempt 1 → attempt 2 backoff 5m→10m; superproject ADR-2609031740 (`f454bff8d2a`) |

## Registry commits (public/open-business.json)

| date | commit | blueprint |
|---|---|---|
| 2026-09-03 | `915ef6e` | ISIC 7310 Advertising |
| 2026-09-03 | `4b96d37b` / PR #576 | network-awai-net-babiniku (ISIC 9219) |
| 2026-09-03 | `779ff141` → server-side merge `d74612f7` | network-awai-network-isekai (ISIC 6420, web3-first game fork economy); Pages-only deploy `cf8caccf`, live registry count 28→29 measured. NOT yet launched — needs kagi secp256k1 wallet + bearer first |

## Launches (operating record)

| date | bot_id | business | note |
|---|---|---|---|
| 2026-09-03 | `business-7310-assignmentisic73` | cloud-itonami-isic-7310 | first business bot; wallet kagi `ITONAMI_ISIC7310_BOT_WALLET_KEY` |
| 2026-09-03 | `business-9219-assignmentbabini` | network-awai-net-babiniku | second; credit 1000 micro-USDC |

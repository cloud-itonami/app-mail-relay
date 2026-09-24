# Workstream status (overwrite on each landed milestone)

## As of last session

- Branch `codex/ldbc-complete-path-20260913`, clean at `07f5a75` (IC10/IC11 milestone).
- Served: 25/29 — U1–U8; IS1–IS7; IC02,04,05,06,07,08,09,10,11,13. Transport registers
  all 29; unsupported operations must fail explicitly.
- Regression suites all green: people 4 tests/40 assertions, complex 7/98, tag 4/36
  (overlapping loader tests across suites).

## Next work (from HANDOFF, in order)

1. IC01 + IC03 — implement, add oracle comparisons incl. boundary/tie/empty/update cases.
2. IC12 + IC14 — same evidence standard; IC14 path weights/enumeration need care.
3. All 29 operations; update integrity/concurrency/durability qualification.
4. Profile full correct ops (candidate: repeated snapshot hydration + CID verify per
   engine/q call; evaluate one-time verified materialization — measure, do not claim).
5. Persist/recover full SF1; then qualify SF10+ with exact manifests (limits unspecified).
6. Production authorization path + kotobase.net deployment — separate verification.

## Reference files already fetched (pinned commit 11db98cc)

- complex 2,4,5,6,7,8,9,10,11,13 + short 1..7 were present; **complex 1 and 3 fetched
  in the latest session**: sha256 511e523567d5cfb0b33a3914aa40f4630473478f90b255f581883d4f5a8ba9a7
  (IC01) and 48f03b4d978d9805fedb3af234edfb30c33ed5f4da4f49a31f205a8d211fbdb0 (IC03).
  Still missing: interactive-complex-12.cypher, interactive-complex-14.cypher.

## IC01/IC03 implementation notes (from reading the pinned Cypher)

- IC01: candidates = 1..3-hop KNOWS from start person, exclude self. distance = min
  path length per candidate (BFS over `neighbors`). Order: distance asc, lastName asc,
  numeric id asc, limit 20. Result rows carry uni study `[name, classYear, city]` and
  work `[name, workFrom, country]` collections (empty when absent) plus birthday,
  creationDate, gender, browser, IP, emails (list), languages (list), city name.
- IC03: friends ∪ friends-of-friends (exclude self); exclude people located in countryX
  or countryY; for each candidate count messages (posts+comments) created in
  [startDate, endDate) located in X and in Y; keep only xCount>0 AND yCount>0; order
  xyCount desc, numeric id asc, limit 20. Params: personId, countryXName, countryYName,
  startDate, endDate (epoch ms).
- Fixture gaps to cover: both-countries-only-X, only-Y, neither, date boundary endpoints,
  self/disconnected, >20 candidates, university/company absent rows.

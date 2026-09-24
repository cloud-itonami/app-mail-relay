---
name: kotobase-ldbc-qualification
description: Use when resuming kotobase-ldbc LDBC qualification work.
---

# kotobase-ldbc — LDBC SNB qualification

Owner: Hermes profile `kotobase-ldbc`. The profile's `HANDOFF.md` + `profile.yaml` +
skills are the workstream state — read HANDOFF.md FIRST on every resumption, then verify
the working repo (branch, clean, last commit) against it. Profile mechanics (gateway,
cron, fallback) are in `hermes-profile-fleet-ops`; this skill is the qualification work.
In-repo canonical progress doc: `bench/LDBC-QUALIFICATION.md` (each landed milestone has
a section); session reports go to the profile's outputs dir. Current status and the next
implementation notes live in `references/status.md` — refresh it after each landed
milestone.

## Invariants (every IC/IS addition)

- **Never derive an operation's semantics from its name.** Fetch the UNCHANGED official
  Cypher for that operation from the pinned upstream commit into the external
  reference-cypher dir, implement the SUT side against it, and let the suite compare
  the two. A semantic guess that passes hand-written fixtures is still unverified.
- One pinned commit for ALL reference queries:
  `11db98cc2ba14c33492f6c0c34e68c8be7e22e5f` (ldbc/ldbc_snb_interactive_v1_impls).
  Fetch missing files; never edit an existing reference file; keep each fetched file's
  sha256 in the session report.
- The oracle is an embedded Neo4j graph seeded INDEPENDENTLY from the same CSV fixtures —
  no kotobase triples and no kotobase query code in the oracle path. Oracle
  normalization touches ONLY official result column aliases and Date params → epoch ms.
- The SUT serves reads over one immutable typed snapshot per call
  (`complex/execute #(engine/q snapshot %) op params`); updates cross the HTTP
  `/ldbc/v1/operation` boundary and must return
  `{"result":...,"status":"committed","commitCid":...}`.
- After every update step: take the old snapshot reference BEFORE the updates, then
  assert old snapshot unchanged AND new snapshot shows the new result. Both assertions,
  every suite.
- Every milestone adds boundary cases beyond the happy path: self/disconnected
  endpoints, tie ordering beyond the limit, exact date boundaries, empty results,
  >limit rows, and official update objects over HTTP before a second comparison.
- Report counts, not banners: operations served (X/29), per-suite tests/assertions and
  the FINAL exit code. A green progress line is not a green run.

## Suite map and pattern

Suites live in `bench/`: short = `ldbc_short_loader_test.cljk` +
`ldbc_short_reference_test.cljk`; complex pairs get one reference test each —
`ldbc_complex_reference_test.cljk` (IC2/9, IC5/13, IC7/8), `ldbc_tag_reference_test.cljk`
(IC4/6), `ldbc_people_reference_test.cljk` (IC10/11). Per pair:

1. Extend the fixture rows by chaining from the previous suite's rows (update-into),
   so earlier coverage is retained in the new run.
2. Seed the mirrored structures into Neo4j (shared `seed-reference!` + pair-specific
   extra seeder for tags/orgs/likes/memberships).
3. Compare oracle vs HTTP adapter for base params plus boundary variants.
4. Updates over HTTP (official operation objects), mirror them in Neo4j, re-compare.
5. Run with the JVM classpath recorded in HANDOFF (java -Xmx768m … clojure.main
   bench/<suite>.cljk ../reference-cypher). Runs take minutes (snapshot hydration) —
   one green run per landed pair; do not casually re-run full suites.
6. Land the milestone: update `bench/LDBC-QUALIFICATION.md` + `bench/kotobase_peer/`
   + sut operation table in the same commit; report the commit SHA.

## Fetching a missing official query (verified command)

Run inside the external reference-cypher dir (outside the repo):

```
base=https://raw.githubusercontent.com/ldbc/ldbc_snb_interactive_v1_impls/11db98cc2ba14c33492f6c0c34e68c8be7e22e5f/cypher/queries
curl -fsSL --max-time 60 "$base/interactive-complex-1.cypher" -o interactive-complex-1.cypher
shasum -a 256 interactive-complex-1.cypher
```

Files stay byte-unchanged; record the hash.

## Evidence boundaries (carry into every report)

- Small SQLite fixtures + Neo4j fixture oracle ≠ official SF10 validation. The SF1
  scan receipt is `:persisted false` — not a durable database and not a benchmark score.
- The loopback host is a qualification host: no production auth, never a public
  interface, never claim deployment evidence from it.
- kbb SCI does not substitute for the official driver's JVM dependency resolution;
  JVM is an explicit compatibility host for this work.
- Hardware/spend limits for SF10+ scale runs are unspecified — do not start paid runs
  without an owner decision.

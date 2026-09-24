# .kotoba guest porting — measured amu backend bounds

Values below were measured against a pinned amu; re-measure per backend before
relying on a number (pin advances change them). Re-measure with one-line probes:

```bash
printf '(ns p (:export [f]))\n(defn f [a :i64] :i64 (%s a))\n' "<op>" > /tmp/p.kotoba
node bin/amu check /tmp/p.kotoba
```

## Bounds that shape the design (state them in the file header when they do)

- **max-parameters 5** — loops take (state, index, one argument) at most;
  booleans travel as an i64 flags vector; `of`-style constructors take one
  vector, not N scalars.
- **`:container-items 32`** — no guest vector may exceed 32 entries. A 64-byte
  accumulator cannot be one vector: use a Horner pass instead of
  accumulate-then-reduce, or split lo/hi. When the value is genuinely
  unbounded (message bodies, byte strings), the guest owns WHEN/WHERE/HOW-MANY
  and the host owns the buffer — the bytes never enter the value plane.
- **i64 shift counts must be integer literals in [0,63]** — a variable shift
  becomes a case tree over fixed shifts (or arithmetic: `(+ (* acc 256) byte)`
  for big-endian reassembly, exact to 2^53).
- **`rem` / `mod` have no admitted lowering** — spell `(- x (* n (quot x n)))`.
  `quot` IS admitted; `/` has no i64 reading.
- **`document-is-null` has no lowering** — absence reads as
  `(document-count d) == 0`.
- **`defn-` is the private form**; `neg?` is a reserved name (use `is-neg`).
  `document-vector-remove` does not exist — a disj-shaped operation reports
  the gap in the file header instead of silently keeping the element.

## check passes but compile refuses

`amu check` is grammar-level; the wasm32 backend can still reject a lowering
(`unsupported typed Wasm expression` — e.g. a string-index-of lookup-table
form). Always run `amu compile --target wasm32-browser` before landing, and
state a backend refusal in the file header rather than silently reworking.

## The throw-to-value conversion

Every `ex-info` site in the oracle becomes a returned value: an `:error`
status keyword plus a reason in the result document. The caller branches on
status. A refusal is a value, not a crash — this follows from the no-untracked-
control-effects rule, and it applies to validation errors, admission refusals,
and malformed inputs alike.

## Regex-to-positional

The oracle's regexes become positional scans when the grammar is fixed-shape
(reply codes, RFC 3339 fields) and stay in the host when the pattern is a
content validator (email, CID, sha256) — the host admission owns those.
A pattern that can match the wire shape can match a great deal else; positions
are the specification where positions exist.

## Strings-as-keys

Per-topic / per-key state in a guest is a paired `[id value]` vector scanned by
position, not a keyword-keyed map (ids are i64 and cannot become keywords).
Topics are few; the 32-item bound bounds the scan.

## Reaching a sibling checkout (test adapters): realpath everything, never literal `..`

A west worktree sits beside OTHER WORKTREES at `orgs/<org>/wt-<name>`, so a
`"../<sibling>"` literal in a test resolves inside the org's worktree
namespace, not to the real checkout tree. Measured adapter shape (kotoba
`.cljk` test suites): derive repo root from the adapter ns's own `*file*`
(absolute under kbb), realpath it, compute the orgs/ dir by dropping the last
two path segments, join `<org>/<repo>/<path>`, and realpath THAT before
handing it to `fs-host/host-filesystem` as `:root` — fs-host refuses `..`
inside a root (`path refused: escape`). Check sibling presence with
`(.existsSync (js/require "fs") <real-path>)`, not `(fs/exists? root
"../<name>")` — the relative form hits the same refusal. Before hard-coding
the sibling's org path, read `manifest/west.yml` for the current registered
name — renames move the checkout and a test path written for the old name
points at a repo that no longer exists (404 on GitHub too).

`str/split` (kotoba.lang.text) requires a REGEX pattern: `#"/"` works, the
string `"/"` crashes with `Cannot read properties of undefined (reading
'includes')` from inside the host matcher — the message names nothing about
the argument type, so suspect the TYPE first at any crash inside a text
helper. Python line surgery on these adapters shifts whole `(let ...)`
blocks' indentation and can leave an unbalanced closing paren that only
surfaces as "EOF while reading" on the NEXT require — after every scripted
edit, re-run the one-line `kbb --backend sci -e "(require '<adapter>)"`
probe before moving on.

## Porting canonical encoders: prove byte-exactness against the reference, not by eyeball

When porting a canonical encoder (base32, base58, CBOR, did-key, hex), compare
against the REFERENCE implementation byte-for-byte before landing — decode both
outputs in python (e.g. `base64.b32decode` after stripping the multibase
prefix) and iterate the port until the byte strings are identical. Two measured
bug classes that only this comparison catches:

- **Drain-count semantics**: a base32 fold that emits one 5-bit group per byte
  (instead of a `while bits>=5` drain) leaves the accumulator's bit count
growing (8,11,14…) and the arithmetic shift eventually reads past the
32-bit accumulator — output diverges mid-string. The reference's per-byte
`while` drain must be the port's inner loop, and the post-drain state must
be what the outer loop carries forward (a loop that returns the pre-drain
count silently re-adds bits).
- **32-bit accumulator wrap**: the JS reference relies on `<<` truncating to
int32. A port using arbitrary-precision ints must mask the accumulator to
0xFFFFFFFF at each shift — an unmasked accumulator carries stale high bits
and emits garbage groups.

The comparison harness is cheap: one scratch script that calls both the port
and the reference on the same input and prints both strings. Byte-diff in
python beats reading two base32 strings by eye — the divergence point localizes
which iteration went wrong.

Additional measured port rules from the base58/did-key wave:

- **base58 digit representation: LSB-first with carry propagation toward the
  MS end.** Each input byte: `carry = byte`, then walk digits from index 0
  (least significant) doing `v = digit*256 + carry; digit = v mod 58; carry =
  v div 58`, then append remaining carry digits at the END. A port that walks
  digits MS-first pushes carry in the wrong direction and eventually emits a
  digit >= 58 (Index out of bounds on the alphabet lookup). Verify with the
  did-key spec's canonical zero-key vector: seed 32×0x00 → pubkey
  `3b6a27bc…da29` → `did:key:z6MkiTBz1ymuepAQ4HEHYSF1H8quG5GLVVQR3djdX3mDooWp`
  — pin THIS vector, not a remembered 'popular example' DID (the z6Mkhaux…
  string is a DIFFERENT key's DID; guessing the expected value wastes a probe
  round).
- **Prefer the pure-cljc sibling over the platform-backed ns for crypto
  primitives under kbb.** `ed25519.sign` (RFC 8032, pure cljc; classpath needs
  org-ietf-x25519 + org-nist-sha2) works everywhere; `ed25519.core`'s
  did-key/seed paths carry JVM-only bodies (BigInteger / node:crypto
  mismatches) that require fine but fail at runtime — measured a corrupted
  `did:key:zK36` (4 chars) from a broken encode and 'seed must be exactly 32
  bytes' from a blen that miscounts a cljs vector. Build did-key encoding in
  the host adapter when the pure path does not expose it.
- **Host fn normalization: convert ALL byte inputs to plain int vectors at the
  wrapper.** `(mod (if (number? %) % (aget % 0)) 256)` over every byte
  argument (pub/msg/sig) makes the wrapper indifferent to Uint8Array vs vector
  vs byte-array; without it each call site re-picks a shape and the failure
  surfaces as an unrelated type error inside the primitive.
- **Inline `(require …)` inside a fn body does not bind in sci** —
  `Unable to resolve symbol: sha2/sha256`. Put every dependency in the ns
  `:require` form; a lazily-requiring helper crashes at its first call.
- **Test vectors quoted from memory are suspect — derive the expected value
  with an independent implementation (python) FIRST, then compare.** A wrong
  'known vector' burns probe rounds on a correct port.
- **`(volatile b)` does not exist in sci/cljs — `volatile!` is the form.** A
  typo'd `(volatile x)` dies with `Unable to resolve symbol: volatile` at the
  call, not at read time.
- **When editing a large multi-branch form through multiple patch rounds,
  STOP and re-derive the structure from the pristine HEAD version once the
  round count exceeds ~3.** Each round-trip re-introduces one wrong close
  count and the error surface moves (syntax → runtime symbol → provider-key
  count) — the third measurement is not converging, it is a NEW bug per
  round. The reliable pattern: `git show HEAD:<file>` for the original, insert
  the new branches as ONE atomic edit, then machine-verify before running:
  every `cond->` arm's `(contains? caps :X)` line must sit at the SAME paren
  depth (compute depth over the prefix text before each line), and per-arm
  closes = branch-internal (str→let→fn→assoc = 4) with cond→/let/defn's 3
  closes ONLY on the last arm. Also: a shell one-liner containing BOTH a
  heredoc/inline script AND a `&&` chain can trip the backgrounding detector
  ("command uses '&'") — write the probe to a file with write_file and run
  it, instead of composing ever-larger one-liners.
- **CBOR writer round-trip needs LENGTH parity**: after porting a CBOR
  writer, re-encode a decoded wire and assert the byte count equals the
  original. A writer that emits trailing bytes the reader silently ignores
  leaves unreachable bytes at the envelope tail and defeats tamper detection
  at exactly those bytes (measured 441 emitted vs 439 parsed).

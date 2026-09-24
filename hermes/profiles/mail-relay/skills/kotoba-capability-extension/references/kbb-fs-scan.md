# kbb native fs-scan guest — verified recipe

Write a directory-scanning `.kotoba` that RUNS on `bin/kbb` (JVM-free: nbb shim → `amu compile --jvm-free` + kexe_loader, target `js-kotoba-v1`, host node-js).

## Shape that compiles (examples/kbb/*.kotoba) vs src/* that does not

Compile-route variants live under `examples/kbb/` and carry:

```kotoba
(ns examples.kbb.no-bb-scan
  (:require [kbb.fs :as fs] [kbb.browse :as browse] [kbb.str :as str])
  (:export [main]))

(defn main [] :i64 ...)   ; every export typed, one ns form, export list INSIDE ns
```

Run:

```bash
cd orgs/kotoba-lang/kotoba
bin/kbb examples/kbb/no_bb_scan.kotoba \
  --policy examples/kbb/no_bb_scan_policy.edn --source-path lib
```

Verified output shape: `:kotoba.cli/ok? true`, `:data {:kotoba.kbb/result 3, :backend :js, :host :node-js, :granted-wire-ids [34 35], :receipts [ ... per-call fs/browse + fs/app-data receipts ... ]}`. Runtime ~2.2s is nbb boot, not scan cost.

## API (compile-route), not the interpreter heads

- `browse/entries dir` → a `\n`-joined **string** of entry NAMES (file and dir alike).
- `fs/read-file path` → file content string.
- `str/line-count s` / `str/nth-line s i` — iterate the listing by index; `str/ends-with?`, `str/starts-with?` for suffix/prefix tests.
- `string-index-of hay needle` ≥ 0 for content substring grep (`contains-sub?`).
- `string-byte-length`, `string-substring`, `string-concat` for path join and length-guarded slicing.

## Policy (fail-closed, scope required)

```edn
{:kotoba.policy/capabilities #{:fs/browse :fs/app-data}
 :kotoba.policy/forbid-wildcard true
 :kotoba.policy/capability-resources
 {:fs/browse #{"test/fixtures/kbb_gate_scripts/clean_dir"}
  :fs/app-data #{"test/fixtures/kbb_gate_scripts/clean_dir"}}}
```

- Missing `:capability-resources` scope → `:kbb-shim/no-scope` ("granted but no resource scope resolves").
- A DIRECTORY scope entry covers the files beneath it; per-FILE entries keep exact equality.
- Wire ids: `:fs/browse` 253, `:fs/app-data` 202 (contract `capability_contract.edn`).

## Capability gaps that block org-wide scans

- **No recursion**: `browse/entries` gives no is-directory flag, so a guest cannot descend. All existing kbb scans are flat/single-directory. To walk a whole repo tree, add a new op (e.g. `fs-browse-dir` returning `(name, is-dir)` pairs) through the contract → lang → kotoba chain.
- **`count` on the listing is rejected**: `(count (fs-browse dir))` → `:kotoba.error/count-receiver` on the compile route; iterate with `str/line-count`/`str/nth-line` instead.

## Migration target pattern

The superproject nbb scripts this replaces (recursive walk + ext filter + content scan): `scripts/repo-search.cljs`, `scripts/langchain-store-adoption-scan.cljs`, `scripts/jvm-dependency-scan.cljs`. The nbb originals recurse with `fs/readdirSync`+`statSync.isDirectory`; the kotoba port needs the fs-browse-dir capability first.

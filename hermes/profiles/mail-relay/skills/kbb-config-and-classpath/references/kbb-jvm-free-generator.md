# Migrating a JVM-only static-site generator to kbb (measured on murakumo.cloud's `:site`)

A `kbb -M:site`-style alias whose `:main-opts` ns requires `clojure.java.io` directly
cannot run on kbb. The measured migration path, in the order done:

## Step 0 — inventory the closure before writing anything

1. Grep the generator ns for DIRECT `clojure.java.io` requires (reader-conditional
   ones are already fine — kbb reads the `:cljs` branch).
2. Resolve EVERY ns the generator requires to its actual file (site repos often
   split ns across 2–3 repos). Grep `ns <name>` across the org dir — a ns name is
   NOT a location.
3. Resolve every `io/resource` call to the repo that actually holds the file.
   Same logical resource path can exist in BOTH repos (e.g. a taxonomy edn in two
   resources/ dirs with different content) — byte-check (`ls -la` both) instead of
   trusting one. Resource paths that LOOK like they belong to the main repo
   (`i18n/onprem-3d/`) can live in a sibling app repo entirely.

## Step 1 — one fs-io adapter ns in the generator's own repo

```clojure
(ns cloud-murakumo.site.fs-io
  (:require [kotoba.lang.fs :as fs] [kotoba.lang.fs-host :as fs-host] [kotoba.lang.text :as str]))

(def repo-root  ; from *file* — absolute only when required as a ns
  (let [f (str *file*) marker "/src/<path>/fs_io.cljk" i (str/index-of f marker)]
    (when (or (= f "") (nil? i))
      (throw (ex-info "fs-io: cannot locate repo root from *file*" {:file f})))
    (subs f 0 i)))

(def orgs-root  ; realpath BEFORE handing to fs-host (unresolved .. = escape refusal)
  (str (.realpathSync (js/require "fs")
                      (.resolve (js/require "path") repo-root ".." ".."))))

(def fsys (fs-host/host-filesystem {:root orgs-root :max-bytes 67108864}))
(defn io-read [p] (fs/read fsys p))
(defn io-write [p c] (fs/write fsys p c))  ; write mkdirs parents (recursive)
```

Design points measured green:
- **One root = the orgs/ dir** (not the repo dir): repos the generator reads span
  several sibling checkouts, and root-confinement forbids `..`. Path all I/O as
  `<org>/<repo>/resources/...`. Realpath the root first or fs-host refuses it.
- `:max-bytes` explicitly large (64 MiB): the default 1 MiB is near vendored-CSS
  sizes, and the write face is bounded by the same value.
- Derive roots from the adapter's own `*file*` (nil under `-e` — throw, don't guess);
  `js/__dirname` is also nil.
- Provide per-repo shorthand fns (`dds-css`, `murakumo-edn`, `app-resource`) so the
  generator keeps its old `io/resource` call shapes.
- Verify the adapter FIRST with real byte counts for every resource it will read,
  plus a write→read round-trip into `target/`, before touching the generator.

## Step 2 — JVM-only seams in library code have cljs answers already; find them

Before patching libraries, check for the designed escape hatch:
- `css-for`-style resource readers may be `#?(:clj ...)` ONLY (no cljs branch).
  Replace the call by reading the component files through the adapter and
  concatenating — do not add a cljs branch to the library (that changes the
  library's authority model).
- `spec/load-spec`-style loaders often throw in cljs with "pass parsed spec" —
  that is the CONTRACT: read the edn via the adapter and pass the parsed value.
- Page/hiccup layers (`jp-go-dds` core/skin/tokens, chrome, blog posts) are usually
  kbb-clean — verify with a single require probe before assuming.

## Step 3 — run through NBB_CLJK_ROOTS + --classpath, not nbb.edn

Cross-repo launch measured green (8 repos; NEVER the superproject root together
with child repos — ambiguous-source):

```bash
R=$ORG/kotoba-lang; M=$ORG/network-awai
NBB_CLJK_ROOTS='["'$R'/fs","'$R'/text","'$R'/fs-filesystem","'$R'/fs-async-filesystem","'$M'/site-repo","'$M'/app-repo","'$M'/main-repo","'$R'/jp-go-digital-design-system","'$R'/html","'$R'/css"]' \
  kbb --backend sci --classpath "$R/fs/src:$R/text/src:..." -e "(require '...)"
```

Every repo whose `src` is on the classpath must appear in ROOTS (invalid-origin
otherwise), and every `.cljk` the run touches must be registered in that repo's
`cljk-origin.edn` — a NEW file (adapter, new post) must be registered in the same
commit or the first require dies `cljk: invalid-origin`.

## Step 4 — honest resume-point reporting when unfinished

If the migration cannot finish in-session, the report must name: which ns is the
ceiling, the measured replacement for each resource (table), and the exact classpath
config that went green. Never report "can't run under kbb" without this — the
next session starts from the table, not from the grep.

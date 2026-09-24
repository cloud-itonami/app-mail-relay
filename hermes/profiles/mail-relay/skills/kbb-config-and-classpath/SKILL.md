---
name: kbb-config-and-classpath
description: Use when kbb fails to resolve a namespace or deps.
---

# kbb config & classpath

kbb (script host) は deps.edn の tools.deps サブセットを解決する。この skill は
config 名・classpath 解決で実測された不変条件を集める。移行手順そのものは
skill `nbb-to-kbb-migration`。

## config 名は origin-owned — `nbb.edn` を `kbb.edn` 等へ改名しない

- engine (kotoba-lang/org-babashka-nbb) は **"nbb.edn"** という literal を 1 か所
  ハードコードし、script path から上へ辿って config を探す。`kbb.edn` は見ない。
- kbb shim (orgs/kotoba-lang/kotoba `bin/kbb_deps.cljk`) も nbb.edn を 3 か所で
  直接読む: ①`exec-engine!` が `--config nbb.edn` を engine に渡す
  ②missing-deps 警告の `(:deps (read-edn-file … "nbb.edn"))` ③classpath 解決。
- **リネームすると project の `:deps` が engine/shim の両面で消え、namespace 解決が
  全部落ちる。** bin/kotoba_adl.cljk は deps.edn/nbb.edn/bb.edn/shadow-cljs.edn を
  "origin-owned names, not ours to rename" として列挙する。
- 衝突もある: `kbb.edn` という名前は既に kotoba repo の **純 EDN パース module**
  (lib/kbb/edn.kotoba、"kbb.edn parses the bytes kbb.fs reads") が持つ。config 名に
  転用すると既存の語と衝突する。
- 改名が本当に要るなら upstream 変更 (engine 探索 + shim の 3 か所) を 1 タスクで
  切り出す。guest repo 側の単独改名は壊れるだけ。

## kbb は Maven/git 座標を解決しない — git 依存は nbb.edn `:deps` に複写する

- 症状: `kbb -M:<alias>` が `Could not find namespace: X` で落ち、console に
  `N dep(s) are not :local/root and are NOT on the classpath … declare them in
  nbb.edn for the engine` が出る。
- fix: deps.edn の該当座標 (`:git/url` + `:git/sha`) を nbb.edn の `:deps` に 1 行
  足す。**sha は隣の project file から複写する（推測しない）** — AGENTS.md の規則どおり。
- `-Sdeps` に git 座標を渡しても classpath には乗らない（`-Spath` は `:paths` +
  `:extra-paths` + `:local/root` のみを返し、Maven/git coordinate は解決しない）。
  `:local/root` のみ recursive 解決対象。
- **`:local/root` で指した checkout の deps.edn が宣言する git 座標も取得される**
  ("Downloading dependencies..." が走る。例えば `kotoba-lang/fs` を指すと
  `kotoba.fs.filesystem` (sibling repo fs-filesystem 由来) が解決される)。sha を
  推測せず sibling checkout に向けたい依存は bare git 座標ではなく `:local/root`
  で足すのが正道。
- ⚠ **ただし worktree では `:local/root` の相対パスが worktree 起点で解決される。**
  `/tmp/<wt>` に切った worktree の nbb.edn に `"../../langgraph"` と書くと実 checkout
  ではなく `/tmp` 配下を見に行き、engine が謎の失敗をする (実測: 起動が
  `/tmp` の全一覧を吐いて落ちる)。worktree で nbb.edn を書くときは sibling を
  `:local/root` でなく bare git 座標 (`:git/url` + `:git/sha` を deps.edn /
  superproject の manifest west.yml から byte-for-byte 複写) で宣言する。
- 警告が出たまま build が進むことがある（warn は die ではない）— 警告を無視して
  先へ進まず、namespace 解決が落ちるなら まず nbb.edn `:deps` を整える。
- **classpath entry は「その dir を classpath root として munged ns path を接続する」
  (engine core.cljs `find-file-on-classpath` の読み解き + 実測)**。ns
  `scripts.nbb-compat` は munged path `scripts/nbb_compat.cljk` として**各 classpath
  dir 直下**に探される。だから bridge のある dir 自体 (…/scripts) を classpath に足す
  と …/scripts/scripts/nbb_compat.cljk を探して落ち、**共通の祖先 (superproject root)
  を classpath に足せば解決する**。scripts/ cwd で通るのは cwd が既定 classpath で
  join(cwd, scripts/nbb_compat.cljk) が実在するから。repo の nbb.edn `:paths` に絶対
  パスを足す場合も同型 — **bridge の dir でなく bridge を含む親 dir** を足す
  (bridge dir 自体を :paths に入れても解決しないことを実測で確認済み)。
  Host-adaptation の必要な移植 (slurp/spit/file/sh/getenv) は
  `scripts/nbb_compat.cljk` + `scripts/nbb_compat/{babashka,clojure}/` に既にある
  (`babashka.fs` shim は create-dirs/real-path も持つ) — 新規に書かず require する。

## NBB_CLJK_ROOTS — .cljk の canonical 解決 (opt-in)

repo cwd から他 repo の ns を require したいときは `--classpath` だけでも足りる
(上の祖先 dir 規則) が、cljk の rename 体系列を canonical に解くには
`NBB_CLJK_ROOTS` を使う。実測の不変条件:

- env `NBB_CLJK_ROOTS`（または nbb.edn `:cljk-roots`）は**絶対パス文字列の vector**:
  `NBB_CLJK_ROOTS='["/Users/<u>/github/com-junkawasaki"]'` 形。map 形
  (`{"ns":"path"}`) は `cljk: invalid-manifest {:detail :roots-must-be-vector}` で
  fail-closed になる（最初に踏む形）。
- **各 root には `cljk-origin.edn` manifest が必須**。kbb の .cljk 探索は manifest
  登録 file の元拡張子から runtime target を決める（`.cljs` origin → JS、`.cljc`
  origin も kbb 上では .cljk を JS として走らせる — `kotoba.lang.fs.cljk`
  (origin .cljc) が require 緑を実測）。
- **新規に書いた `.cljk` も同じ commit で manifest に登録する** — 登録 file 以外は
  `cljk: invalid-origin {:path …}` で require 落ちする（新規 adapter / 新規記事など、
  どんな小さい file でも）。portable file は origin `.cljc` を 1 行足す。**他
  session が merge した新 file にも同じ要求が出る**: manifest 未登録のまま main に
  着地した file が在ると、その repo の ns を最初に require するタスク（作業と無関係
  なタスク）が manifest 修正から始まる — PR 着地前に `grep <file名> cljk-origin.edn`
  を 1 回入れる。
- ROOTS を設定すると、classpath で見つかった**全 .cljk が「所有 root の manifest
  登録済み」であること**を要求する: fs repo の `src/kotoba/lang/fs.cljk` を classpath
  に乗せつつ ROOTS に fs repo を入れないと `cljk: invalid-origin {:path …}` で落ちる。
  → ROOTS リストには classpath に乗せる全 repo の root を揃える。
- **`.cljk` の manifest origin が `.clj` のファイルは JS target に載らない** —
  `cljk: target-incompatible` で落ちる。`.cljk` rename は blob 内容を変えないので、
  JVM 専用だった ns は rename 後も origin が `.clj` のまま残る。kbb で動かしたい
  ns は **cljk-origin.edn の origin を `.cljs` に書き換えてから** require する
  (byte 内容は変えない — origin は「元の拡張子」記録ではなく「kbb がどの target
  として走らせるか」の宣言と読む)。repo-wide に動かすなら manifest の全 `.clj`
  origin を一括で寄せる手術が 1 回で済む。
- **cljk 所有は 1 root に限定 (ambiguous-source)**: classpath dir が ROOTS の
  2 root (例: superproject root と child repo root) の両方に包含されると
  `cljk: ambiguous-source` で落ちる。superproject root は orgs/ 配下の全 checkout
  を包含するので、**superproject と child repo を同じ ROOTS リストに入れて child の
  src を classpath に乗せる構成は必ず ambiguous になる** — child repo (とその依存
  repo) のみを ROOTS にする。classpath 側は「ns prefix を持つ最短 dir」を足すのが
  正: ns `scripts.nbb-compat` には `<superproject root>`、ns `kagi.cli` には
  `<kagi>/src`。
- **`.cljk` probe は classpath dir に munged path を接続する** — nbb.edn `:paths`
  や `--classpath` に「bridge の dir」でなく「bridge を含む親 dir」を足す。
  Host-adaptation の必要な移植 (slurp/spit/file/sh/getenv) は
  `scripts/nbb_compat.cljk` + `scripts/nbb_compat/{babashka,clojure}/` に既にある
  (`babashka.fs` shim は create-dirs/real-path も持つ) — 新規に書かず require する。
- 実測緑形（`kotoba.lang.fs` + `fs-host` の依存閉包: text + fs-filesystem +
  fs-async-filesystem）:
  ```bash
  R=$HOME/github/com-junkawasaki/orgs/kotoba-lang
  NBB_CLJK_ROOTS='["'$R'/fs","'$R'/text","'$R'/fs-filesystem","'$R'/fs-async-filesystem"]' \
    kbb --backend sci --classpath "$R/fs/src:$R/text/src:$R/fs-filesystem/src:$R/fs-async-filesystem/src" \
    -e "(require '[kotoba.lang.fs :as fs] '[kotoba.lang.fs-host :as fs-host])"
  ```

## 確認用コマンド

```bash
# 宣言されている alias と classpath を見る（cwd は project root）
kbb -Saliases
kbb -Spath
# 実行（engine は project の nbb.edn を探して :deps を読む）
kbb -M:render
```

`-M:<alias>` の alias は deps.edn の `:aliases` から解決され、未宣言 alias は
exit 64。deps.edn が無い cwd では alias は解決されず exit 66。

## kbb の alias 実行は JVM main を持てない — `:main-opts` の ns が動くかを先に測る

`kbb -M:<alias>` が通る = alias が解決されたことだけ。`:main-opts ["-m" <ns>]`
の ns 自体が JVM 前提 (`clojure.java.io` 直 require、`slurp`/`spit`/`io/resource`)
なら engine で `Could not find namespace: clojure.java.io` で落ちる。alias を
kbb で動かす前の判定手順:

1. `grep -n ':require' <nsファイル>` で JVM-only require (`clojure.java.io`,
   `clojure.data.json`, `java.*`) を列挙する。reader conditional
   (`#?(:clj [clojure.java.io ...])`) の内側なら kbb では読まれない — 直 require
   のみが天井。
2. 依存閉包も同じ grep で洗う: alias の `:replace-deps` が指す repo の src から
   JVM-only require を持つ ns を列挙し、「どの ns が天井か」を先に名指す。
3. 移行先は `kotoba.lang.fs` + `kotoba.lang.fs-host` (skill `java-kotoba-migration`:
   node fs 実装、`:clj`/`:cljs` 両面を 1 ns で持つ)。`io/resource` は classpath
   上のファイル読みに置換する — ライブラリ側 (jp-go-dds.css.cljk の docstring)
   が "resource が使えない実行系からはパスだけ取り、呼び出し側が読む" 方針を
   明示しているので、生成器側の置換はこれに従う。
4. 動かない ns を含む alias 全体ではなく、通る面だけを切り出す最小 paths/alias
   (`:replace-paths`) を作るのが現実的 — 1 ns 直せば全 alias が通るとは限らない。

設定が済んでいれば `kbb -M:site` のような alias 実行は 1 発で確認できるが、
落ちたときは (1) の grep に戻る — engine 再試行で直ることは無い。

JVM-only 生成器（`kbb -M:site` 型）の kbb 移植手順（fs-io adapter / resource の
repo またぎ解決 / 実測 classpath 構成）は `references/kbb-jvm-free-generator.md`。

⚠ **JVM-only 生成器を「kbb で動かせない」＝「生成を諦める」ではない。** 生成物
（`public/blog/*.html` 等の静的 HTML）と生成器（`generate.cljk`）は別の問題で、
生成器の JVM-free 化は独立タスクとして切り出し、未完なら owner に resume point
（どの ns が天井か、移行先は何か）を明記して報告する。既存生成物の手編集は
「生成物は再生成されるもの」という契約を壊すのでしない。

## kbb sci で解決できる/できない surface（実測）

- 動く: `clojure.edn`, `clojure.string`, `clojure.set`, `kotoba.lang.text`
  (`str/last-index-of` 等の全 export), `js/*` 全般
  (`js/parseInt s 16`, `js/JSON.parse`, `js/decodeURIComponent`)。
- 動かない: `clojure.java.io`, `clojure.data.json`, `clojure.core/slurp`/`spit`,
  `Long/parseLong`, `Integer/parseInt`, `System/exit`, `java.net.URLDecoder`,
  `java.io.File`, `clojure.lang.PersistentQueue` (cljs に実在しない — BFS queue
  は amortized functional pair に書き換える), `clojure.lang.ExceptionInfo`
  (`thrown?` は `js/Error` に対して行う — ex-info は nbb で js/Error を投げる),
  `load-file` (sci に無い — ns require で確認する)。
  置換先は `js/parseInt` / `js/JSON.*` / `js/decodeURIComponent` /
  throw-to-value（対応表は skill `java-kotoba-migration`）。
- `js/JSON.parse` は JS object を返す — EDN map と比較する前に cljs 化ヘルパを噛ます。
- `*file*` は require された namespace 内では絶対パス、`-e` 直呼びでは nil。
  repo root 導出は adapter ns 自身の `*file*` から行う（`js/__dirname` も nil）。
- `kotoba.lang.text/split` は regex を要求する (`#"/"`)。文字列 separator `"/"` を
  渡すと host matcher 呼び出しで `Cannot read properties of undefined (reading 'includes')`
  で落ちる。JS interop を含むヘルパ呼び出しが落ちたら、まず 引数の型を疑う（メッセージが
  直接の原因を名指さない）。
- repo-root / sibling-path 導出は realpath を先に噛ませる: `.realpathSync
  (js/require "fs") <path>`。realpath せずに `<root>/../<sibling>` を :root に渡すと
  fs-host の escape 拒否（`path refused: escape`）か `:root must be an existing
  directory`（`..` が lstat で未解決のため）になる。sibling の存在確認も
  `(.existsSync (js/require "fs") <real-path>)` で行う（fs/exists? + `"../"` は
  escape 拒否に当たる）。
- **node module fn は method-call 形で呼ぶ** — `(js/writeFileSync fs …)` は
  GLOBAL `writeFileSync` を探して undefined になり
  `Function.prototype.apply was called on undefined` で落ちる。正しくは
  `(.writeFileSync fs …)`。`js/*` は call position で global lookup になることを
  忘れない（`js/Buffer.from` や `js/process.exit` のような `js/<global>.<method>`
  形は global に実在するものだけ）。
- **`(ex-info ...)` の arg 順を JVM 流で書かない** — `(throw (ex-info "msg" {}))`
  が cljs で `Too many arguments to throw` になるのは arg 順の間違い（message と
  map の順・余分な arg）を指す。2-arg 形 `(ex-info msg data)` で書く。
- **top-level def の `-e` からは `(fn? x)` が false を返す** — def 済みの値を確認
  するときは `(deref (var ns/name))` を使う（require 済み ns の top-level def は
  値としては動く。謎の `ka.call is not a function` が出たら「def の読み方」を先に
  疑う）。
## 実測（murakumo site 生成器の kbb 化、2026-09-13）— .cljk 移行の 5 罠

- **ns フォーム無し .cljk は「require が成功するのに def が登録されない」**: cljk loader は ns フォームの無いファイルを登録済み namespace なしで読むため、alias 経由の参照が全部 `Unable to resolve symbol` になる（`(all-ns)` にも現れない）。`def` のみのファイルにも必ず `(ns ...)` を 1 行足す。
- **hiccup form の間に raw 日本語テキストを書かない**: `[:a {...} "ラベル"] は、単なる…` のような引用符忘れがあると、リーダは本文をシンボル列として読み、`3B active` のような断片で `Invalid number: 3B` で落ちる。エラー列は本文の途中を指すので原因が分かりにくい。全文検索は `"\]\s+[^\s"\[]`（閉じ quote 直後に raw テキスト）。
- **kotoba.lang.text/index-of は不在時に nil を返す**（JVM clojure.string は -1）。`(+ pos (str/index-of ...))` は nil でカーソルが進まず、同じ match を永遠に追加する無限ループ（4 GiB OOM で発覚）。`(recur (max (inc pos) (+ i (count whole))) ...)` 形に直す。JVM で動いていた文字列走査ループは kbb で初めて測る。
- **cljk-origin の origin 値は「リネーム前の拡張子」だが、.clj origin の file は JS target で `target-incompatible` に落とされる**。JVM 専用 I/O を撤去して真に移植可能になった file は origin を `.cljc` に付け替えるのが正しい（gate の意味 = JVM-only の JS 実行拒否 — を維持したまま）。
- **catalog などの「無くて良い file」は fs/read 直呼びだと throw する**: 旧 `some-> (io/resource ...) slurp` の nil 挙動は `(when (fs/exists? fsys rel) ...)` で再現する。fs-host の read は not-found で refuse（fail-closed）、欠落=黙って nil の旧動作とは別物。

## runtime classpath wiring for a task runner (実測 2026-09-14, network-isekai run-task.cljk)

- **kbb `--backend sci` script が kotoba.lang.text 等を require して落ちる**
  (kbb cutover が `bb <task>` → `kbb --backend sci` に書き換えた後の典型症状)。
  修復パターン: script 内で **west 兄弟 checkout を実行時解決**し
  `nbb.classpath/add-classpath` に渡す。
  ```clojure
  (require '[nbb.classpath :as ncp])            ;; ← top-level 必須
  (def text-src (let [sib (.resolve path (.dirname path *file*) "../../../kotoba-lang/text/src")]
                  (and (.existsSync fs sib) sib)))
  (when text-src (ncp/add-classpath text-src))
  (require '[kotoba.lang.text :as str])
  ```
- **require は when 内に置けない** — `(when x (require '[a :as b]))` は b の
  alias 登録が分析時に起きず `Unable to resolve symbol: b/f`。require は必ず
  top-level に置き、先に add-classpath を呼ぶ。
- `nbb.classpath` は `(js/require "nbb.classpath")` で呼べない (module 解決は
  engine 内部パス基準)。`(require '[nbb.classpath :as ncp])` で ns として
  require する。`(ns-publics 'nbb.classpath)` = add-classpath / get-classpath。
- load-file される test 側の require も同じ classpath を見るので、repo の
  `src` も一緒に add-classpath しておく。
- **kbb_deps (`-M` door) は git dep を classpath に載せない** (名指し警告のみ)。
  `:cmd ["kbb" "-M" "test/x_test.cljk"]` 型の task body は走らない — これは
  kbb_deps の既知ギャップで task runner の外。復旧は nbb.edn `:deps` 複写か
  alias の :extra-paths :local/root 化。

## temp script は checkout の内側に置く

- **temp script は checkout の内側に置く** — 子 kbb プロセスに /tmp の script を
  渡すと classpath scope から外れ `Could not find namespace` になる（実測）。
  `target/` 配下に書く。

## test namespace を require するなら nbb.edn `:paths` に "test" を足す

deps.edn の `:extra-paths ["test"]` は alias の内側にしか無く、nbb.edn `:paths`
は alias を読まない。`:paths` を「deps.edn のミラー」のまま保つと test ns が
"Could not find namespace" になる — `:paths` に `"test"` を明示する。

## バッチ編集後の require 再確認は必ず最後の 1 回やる

python による行手術は「構文上の paren 深さ」を黙って変えることがある
（`(let [...] ...)` block の再構成、vector リテラル内の reader conditional が
`t4.call is not a function` のような JS call form に化ける）。suite は parse
段階まで走らないので、エラーは次の実行で初めて表面化し、しかも手術地点から
離れた行名で出る。**全編集を終えた後に require loop（全 test ns を 1 個ずつ
require して fail 数を数える）を必ず 1 回走らせ、0 になるまで suite に触れない。**
scrap な `(count '(')` / `(count ')')` 比較は「どのファイルか」の locator としては
有効（false alarm はあるが、残り 1 ファイルまで絞れたら十分速い）。

## kbb sci の文字→数値 coercion（実測）

- `(int "S")` / `(int \S)` / `(map int "SE")` は **0 を返す** — cljs では文字が
  1 文字 string で、`int` はそれを 0 に coerce する。byte fixture は
  `(.charCodeAt (str c) 0)` で作る（`(map int text)` は全部 0 の vector になる）。
  byte 面の謎の失敗は、まず fixture が実バイトを持っているかから確認する。
- **byte vector は `js/Uint8Array` で作り、`(count)` は付かない (実測)**。cljs の
  Uint8Array は ICounted 実装を持たないので `(count u8)` は
  "No protocol method ICounted.-count" で落ちる — 長さは `(.-length u8)` で読む。
  `array-seq` を通せば count 可能になる。`(map int "str")` 全 0 陷阱の対策も
  併せて: `(js/Array.from (js/Uint8Array. #js [104 101 ...]))` 形で作るのが安全。
- `*file*` は require された namespace 内では絶対パス、`-e` 直呼びでは nil。
  repo root 導出は adapter ns 自身の `*file*` から行う（`js/__dirname` も nil）。
  また `#?(:cljs ...)` branch 内の `(str/last-index-of ...)` は
  `kotoba.lang.text` を require して初めて解決される（JVM の `clojure.string`
  とは別物）。

## npm deps は repo node_modules から解決される — engine 側 install は不要

- `Cannot find module '@noble/ciphers/aes.js'` のような npm module 未解決は、
  **repo で `npm install` するだけで直る** (package.json の deps が空でも
  `npm install` が package.json の依存を入れる)。kbb engine は cwd の repo
  node_modules を見るため engine 側に手動で package を足す必要は無い。

## トランスポート確認は 1 つの scratch ns で段階的に

adapter を書いたら、(1) require 一発（classpath 問題だけを切り出す）、
(2) `-e` で各 fn を実データ 1 件ずつ（resource 読み → exists? → sibling 読み →
欠落 sibling が nil）、(3) その後 suite 全体、の順で確認する。いきなり suite を
回すと classpath 問題とロジック問題が同時に出る。

## sci の解析規則 — inline require は解決を作らない / reader conditional は入れ子にしない

- **`(require '[x.y :as y])` を fn 本体の中で呼んでも `y/f` は解決されない。**
  sci は読み込み時 (analysis) にシンボルを解決するので、`sha2/sha256` のような
  fully-qualified 呼び出しは ns の `:require` に置かれていないと
  `Unable to resolve symbol: sha2/sha256` で落ちる。fn 内 require は JVM Clojure
  では動くので、両面を維持したい helper は必ず ns `:require` に置く。
- **新しい `#?(:cljs ...)` ブロックをファイル末尾に足すとき、`#?(:clj (do ...))`
  ブロックの閉じ `))` の位置を先に確認する。** 閉じの *内側* に挿入すると :cljs
  ブロックが :clj branch にネストし、cljs 経路では def が黙って定義されない
  (括弧は釣り合うので reader error も出ない)。症状は
  `Unable to resolve symbol: <ns>/<def>` と `ns-publics` の一覧欠落。挿入は
  開き括弧でなく閉じ位置の直後に置く。
- **`cond->` の既存 branch chain に新 branch を足すとき、最後の branch の閉じ数は
  paren-DEPTH 測定で決める** (目視で数えない)。各 `(test expr)` ペアは同一 depth
  に揃うのが正構造で、末尾 branch が「cond-> が終わった後の defn/let の閉じ」を
  引き取る数が変わる。誤って数えると新 branch が前 branch の fn 本体に入り込み、
  **テストも発火せず provider map に key も乗らない** (エラーなしで静かに欠落)。
  判別法: `(contains? caps :X)` の各位置での paren depth を機械で測り、全ペアが
  同一 depth に揃うまで直す。`capability-grant-mismatch` が guest から出たら
  requiredCapabilities (artifact 側) と provider keys (host 側) の乖離を疑い、
  `run-guest!` を instrument して両方 dump する。
- `(.-uuid (js/crypto.randomUUID))` は nil を返す — `randomUUID` は**文字列を
  直接返す** (`.uuid` プロパティは不要)。
- **実行時 byte 正規化は `mod 256` で、`aget` は number でも Uint8Array でも受ける**:
  pure cljc ライブラリ (例: `ed25519.sign`) に渡す入力は
  `(vec (map #(mod (if (number? %) % (aget % 0)) 256) (seq b)))` 形で正規化する。
  JVM byte[] (負値を取る) と cljs Uint8Array を同一コードで受ける箇所は、
  正規化しないと JVM 側だけ値がずれる。
- **js/Uint8Array は ICounted でない** — `(count u8)` は
  `No protocol method ICounted.-count` で落ちる。長さは `(.-length u8)`。

## `-e` で test adapter を確認するときは require 一発で落とす

`kbb --backend sci -e "(require '[x.y.test-host])"` が最初の 1 ステップ。namespace
解決だけを切り出すと、ファイル内のロジックミス（syntax、未定義シンボル）と
classpath 未解決を区別できる。load-file でも良いが require の方がエラーが短い。
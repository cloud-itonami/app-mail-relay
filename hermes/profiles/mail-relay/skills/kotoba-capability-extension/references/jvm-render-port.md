# JVM render/build コードを kbb engine (SCI-on-Node) で動かす手順

deps.edn project の `#?(:clj ...)` render/build path（clojure.java.io 前提）を
kbb engine で動かすときの実測手順。guest .kotoba 移行とは別の面（既存 .cljk
project を JVM-free のまま build する類の作業）。app-kotoba-cloud site.cljk
render port で全項目実測（render は緑になった）。guest .kotoba の scan 手順は
`references/kbb-fs-scan.md` が別面を担う。

## 事前: 依存の可視化

1. `kbb -Spath` を出す。警告 "N dep(s) are not :local/root and are NOT on the
   classpath" に列挙された座標 = nbb.edn の :deps に無いもの。
2. その座標を nbb.edn に複写する（git は `{:git/url ... :git/sha ...}`、sha は
   deps.edn から写す。選ばない）。
3. `kbb -M:alias` で回す。**素の `kbb <script>` / `kbb -e` は --config を渡さない**
   （`kbb_deps.cljk` の exec-engine! が -M/-X 経路でのみ config? を立てる）。
   script 経路では nbb.edn の git dep が解決されず "Could not find namespace"
   になる。デバッグ中の require 失敗は、まず -M 経路か素の script 経路かを
   確認してから namespace の存在を疑う。alias を一時注入する時は
   `kbb -Sdeps '{:paths [...]:aliases {...}}' -M:<alias>` 形（deps.edn を編集しない）。
4. **engine を手で直接起動する検証はしない。** `node cli.js --classpath … --config …`
   の引数再現は kbb が内部で組む引数（deps.edn alias 解析を含む）と一致せず
   load 時に落ち、その差の追跡に時間を溶かす。検証は必ず `kbb` コマンド経由で。

## engine cache の構造

- `<project>/.nbb/.cache/<deps map のハッシュ>/deps.edn` + `nbb-deps/` + `nbb-deps.jar`
- `nbb-deps/` は全 dep の `:paths` を flatten したもの。dep 側 `resources/` の
  `jp_go_dds/dds.css` は `nbb-deps/jp_go_dds/dds.css` に現れる。
- ハッシュは nbb.edn `:deps` の内容から決まる → deps を変えると変わる。
  **パスは scan で探す**（下記 resource-read）。読み元 ns の `:file` meta を
  probe して cache path を確定させられる:
  `(str (:file (meta (first (vals (ns-publics 'some.dep.ns)))))）`
- **`.nbb/` は project dir 内に生成されるので gitignore に足す**（多くの repo は
  既に持つ — status -s の untracked に映らないか先に確認）。

## io/resource の代替（実測動作コード形）

```clojure
(defn- resource-read [resource-path]
  (let [cache (node-path/join (js/process.cwd) ".nbb" ".cache")
        roots (try (js->clj (fs/readdirSync cache)) (catch :default _ []))
        hits (for [root roots
                   :let [p (node-path/join cache root "nbb-deps" resource-path)]
                   :when (fs/existsSync p)]
               p)]
    (if-let [first-hit (first hits)]
      (str (fs/readFileSync first-hit "utf8"))
      (throw (ex-info (str "resource-not-found: " resource-path) {})))))
```

欠落で空文字を返さず throw する（「入力が無いとき何を返すか」準拠）。

## JVM I/O → node 対応表（全て実測動作）

| JVM | kbb engine |
|---|---|
| `(io/file a b)` | `(node-path/join a b)` |
| `(slurp f)` | `(str (fs/readFileSync f "utf8"))` |
| `(spit f s)` | 親 dir を `(fs/mkdirSync (node-path/dirname f) #js {:recursive true})` して `(fs/writeFileSync f s)` |
| `(io/copy src dst)` | `(fs/copyFileSync src dst)`、親 dir は mkdirSync recursive |
| `(file-seq d)` | readdirSync + 各 path を `(fs/statSync p)` して `(.isDirectory st)` で分類する再帰 walk |
| `(io/make-parents out)` | `(fs/mkdirSync (node-path/dirname out) #js {:recursive true})` |

file walk のコード形:

```clojure
(defn- fs-walk-files [dir]
  (let [out (js/Array.)]
    (letfn [(walk [d]
              (doseq [name (js->clj (fs/readdirSync d))]
                (let [p (node-path/join d name)]
                  (if (.isDirectory (fs/statSync p))
                    (walk p)
                    (.push out p)))))]
      (walk dir))
    (vec out)))
```

## よく落ちる箇所（実測で引いた系統）

- **`(.-method obj)` は関数値であって呼び出しでない。** `(.-isDirectory st)` は
  Stats のメソッド関数そのもの（truthy）を返し、全 file を directory 扱いして
  readdir が ENOTDIR で落ちる。呼び出しは `(.method obj)`。JVM 経路の .cljc を
  port したらこの系統の interop を点検する。**「分類が逆になる」系の failure は
  この pattern を疑う** — if 分岐が常に同側に流れるなら `(.-x)` が truthy を
  返している。isolated probe で `(.-isDirectory st)` が `#object[Function]` を
  返すことを 1 回見れば確定（ENOTDIR の原因特定に 3 probe 要した — もし最初から
  関数値を prn していれば 1 probe で済んだ）。
- **Dirent を js->clj しない。** メソッドが落ちて `.-isDirectory` が
  undefined / `.-name` が nil になる。名前一覧 + statSync の形にする。
- **上流 library の helper 自体が `#?(:clj ...)` でしか定義されていないことが
  ある**（jp-go-dds.css `css-for` が実例）。library 側に手を入れず、:cljs
  branch が在る public 純粋 helper（`component-path` / `extra-components`）を
  借りて呼び出し側で合成する。
- reader-conditional の `#?(:clj (defn ...))` を素手で外すときは各ブロック末尾の
  閉じ括弧が 1 個過剰になる。guard 除去と括弧調整は同じ patch でやる。
- **guard 外しの前に `#?(:clj` を全部列挙して着手する。** 純粋 rendering（guard
  除去のみ）と I/O（対応表で置換）と JVM 専用（放置・別 PR）に分類してから
  手を入れる。途中まで触ると paren が壊れた状態で読めなくなり切り戻しになる。
- **reader-conditional を除去したブロックの閉じ括弧は位置を数えて一括修正** —
  guard 除去後の各ブロック末尾で 1 個過剰になる。block 番号（defn の並び順）で
  5 箇所を特定してから一括で直す（個別に直すと「どのブロックが未処理か」を
  追跡する手間で paren を壊す）。修正後は render を 1 回走らせて括弧エラーが
  「どこで」出るかを見る — 行番号が block 開始行ではなく block 末尾付近に
  出たらその block の paren が壊れている。
- **host 固有の JS 面を移植対象にしない。** io/copy → copyFileSync のような
  runtime 中立な置換は kbb engine で緑にできる。逆に 1 つの host runtime にしか
  在らない面（Workers の Request/Response/fetch/DO/R2、`js-invoke` 等 engine 側
  未実装 interop）は移植ではなく capability 化の対象 — 本 skill の chain で
  計画する。bundle host の選定はこの file の末尾節。
- `-m ns` は ns の `-main` を要求する。移植で `-main` を cljs 側でも定義するのが
  最小差分。

## probe の作法

deps.edn を編集せず alias を注入して ns を走らせる:

```bash
kbb -Sdeps '{:paths ["src"] :aliases {:p {:main-opts ["-m" "probe-ns"]}}}' -M:p
```

- probe ns は `:paths` に載る dir（通常 src/）に置き、検証後に削除する。
- probe ns に `(-main [& _])` を空で用意する — 無いと末尾に
  "Unable to resolve symbol .../-main" が出る（print 自体はその前に完了するが、
  空でも置いておけば exit も clean になる）。
- ns の解決元を確かめるには `(:file (meta ...))` が一番速い。
- fs/interop の挙動確認は 1 つの probe に 1 つの疑問。print を挟まず連ねると
  どの式の返り値か読みにくい（ENOTDIR の原因特定に 3 probe 要した）。
- probe は src/ に置いたら **検証後に必ず消す**（git status -s の untracked に
  残りやすい。commit 前に `git status -s` を確認し、probe ファイルが
  untracked に残っていないか見る）。probe を書いた直後に消すのが正しい順序 —
  commit 直前に掃除になると 9 個溜まって rm が一括削除 approve を引く。
- **`-M:p` probe の末尾 `-main` エラーは結果を読む邪魔になるだけではない** —
  exit code を成否判定に使う場合は `(-main [& _])` 空定義を probe ns に入れて
  exit を clean に保ち、stdout の assert だけで判定する。

## 検収

- 該当 alias（`kbb -M:render` 等）が緑。
- 生成物 dir を ls し、実ファイルを数えて「ビルドできた」ではなく「生成された」
  を確かめる（AGENTS.md 8 問の 8）。
- "org.clojure/clojure not on classpath" 警告は純 cljs 経路では無害。JVM 分岐を
  全て移植したなら render 出力内に JVM 依存の痕が残らないことを確認してから緑と
  する。
- `npm run build` 全体は render 後に amu compile 等が続く。render だけ緑でも
  build 全体は別途走らせる。
- **PATH を通さないと `amu: command not found` になる**。amu は west sibling
  checkout の `orgs/kotoba-lang/amu/bin/amu` に在り、PATH に入っていないことが
  普通にある。build script の失敗が `sh: amu: command not found` ならこれは
  repo の壊れではなく実行環境の PATH — `amu` を PATH に足して再実行してから
  本当の failure を見る。
- **既存 failure を「自分のせい」とする前に base で 1 回だけ確認する。** 変更を
  `git stash` して同一 alias を 1 回実行し、同一エラーなら pre-existing と切り分け
  （stash pop を忘れない）。比較は 1 回で十分、履歴を残す必要はない。

## build/bundle host の選定（render が緑でも残る部分）

wrangler main のような ESM bundle（named exports 付き、自己完結）は host で
出し方が全て異なり、必ず 1 つずつ測定してから判断する:

- **kbb engine `bundle` サブコマンド**: `loadString` wrapper を出すだけ。ESM
  export も自己完結も無い → bundle host には使えない。
- **shadow-cljs**: `.cljk` を解決しない（`:source-extensions` 等の build map 設定
  は黙って無視される。試したら必ず revert して dead config を残さない）。rename
  時系列の確認も先に: `.cljk` rename commit が「No compatibility mirror — build
  breakage is fixed forward」を明言している場合、shadow-cljs 復活は mirror に触れる。
- **amu compile**: js interop（`js/Headers.` 等）を
  `named operation X is not a registered capability` で拒否。純 guest だけが
  通る。amu を通すには親 skill の capability chain が必要。
  **amu 引数形も先に確認**: `amu --help` の usage は
  `amu compile <file> --target <t> [--policy p] --output <f>`。consumer repo の
  package.json に旧 host の build 名を文字置換した残骸（`amu compile browser app`
  のような source に存在しない stem）があれば、それは引数エラー（exit 64）の
  予測可能な原因。rename 後に build が壊れている repo はここを最初に見る。
- **`.cljk` rename 後の repo で build が壊れているかの判別は git で**: build
  script 行の履歴（`git log --oneline -- package.json`）と rename commit の
  commit message を読む。「No compatibility mirror — fixed forward」が書いて
  あれば壊れは既知であり、直す work は mirror 禁止に触れない形（capability wave
  か owner 判断の mirror 許可）で提案する。

bundle が要る work は **render 系の修正とは別 PR に分ける**判断を測定値つきで
owner に明示する。とり得る形: (a) capability wave を upstream に起票する、
(b) 一時 mirror を入れる（`.cljk` rename の mirror 禁止に触れるので owner の
明示許可が要る）、(c) deploy を wave 待ちにする。

## 提案・issue 起票の作法（bundle が要る work を外に出す時）

1. interop census を測ってから書く: `grep -o` で js/ 形式を数え、4 グループ
   (http / json / time / crypto+misc) に分解する。数字の入った issue が次の
   着手の見積りになる。
2. 最小再現コードと拒否の literal を本文に置く（「できない」の言明ではなく
   compiler が実際に吐いた message）。
3. 既存 landed capability の再利用余地を最初に調べて書く — 新規 capability の
   前に「既存で足りない部分」が設計点になる。
4. 提案書は consumer repo 側 docs/ にも置き、upstream issue から link する
   （issue だけだと branch 消滅時に測定が消える）。
5. 起票先は最も近い authority repo に 1 本だけ（例: amu guest 面の拒否なら
   kotoba-lang/amu）。同じ提案を複数 repo に重複起票しない。title には測定規模
   （form 数 / file 数）を入れ、body は group 分解表 + 波順 + 既存 reuse の
   判断点で構成する。
---
name: git-cleanup-conflict
description: Use when cleaning up git stashes, branches, or worktrees.
---

# git cleanup — stash / branch / worktree retirement

正本は superproject の `manifest/cleanup-workflow.edn`（`:retirement`）と AGENTS.md
「破棄しない」節。この skill は実測済みの判定手順と罠を載せる。

## 不変条件（毎回効く）

- **drop / 削除の前に必ず `.git/stash-archive-<date>/` へパッチを退避する。**
  「landed だと確信している」は省略理由にならない。`git stash show -p stash@{N}` で
  patch を 1 ファイルに落とし、INDEX.txt に元の subject を書く。
- **drop は SHA を控えてから index を再解決して行う。** 並行セッションが stash
  index をずらすので、番号 (`stash@{0}`) をキャッシュして連続 drop しない。
  **1 件 drop するたびに `git stash list` を読み直してから次の番号を当てる** —
  事前に調べた番号を連打すると 3 件目以降が "only has N entries" で落ちるか、
  ずれた別物を落とす（実測 2026-09-13）。
- **削除は `-d`（確認付き）のみ。`-D` を使わない。** 未 merge 判定が git 側と
  異なったらそこで止めて報告する。
- **他人の dirty / untracked / `.env` を含む stash は判定のため中身を読まない。**
  ファイル名一覧だけ見て「緊急退避物」として残す。subject に他 session の名前が
  書いてある stash を apply/pop して working tree に戻すのも誤り — 退避物は
  **退避した session のもの**。他 session の launcher / server 系スクリプトが
  dirty のまま残るのは「書いた session が次の再起動で引き取る」状態であり、
  live server が裏でそのファイルを読んでいる（identity 等の live probe が
  green で正当）可能性が高い。変えても外部に影響が出ない箇所だけ self-check
  し、それ以外は報告だけして残す。
- **自分が stash した tracked WIP には、後で判別できる subject を必ず付ける**:
  `git stash push -m "<bot名> WIP (<内容の要約>) before sync to pin <sha> <日付>"`。
  内容のない「bot WIP」だけの subject は、並行 session が stash を数えたときに
  「他人の WIP」としか読めず再処理を強いる。
- **bot が checkout に置いた untracked WIP は消さず、tracked 変更だけ stash して
  sync する。** west update / FF は tracked 変更があると止まり、untracked は通常は
  無視される — tracked だけを退避すれば checkout を pin に合わせられる。**例外:
  incoming commit が同じ path に file を作ると untracked でも FF が止まる**
  （`would be overwritten by merge`）。止まったら中身で判定する:
  `diff <(git show origin/main:<path>) <path>` が byte-identical なら local copy を
  消して FF（自作 file が別経路で先に着地した case）、差分があれば rename 退避して
  残す — blind 削除しない。

## 着地判定（測ってから言う）

1. **branch → main**: `git merge-base` で tip が origin/main に含まれるか。
   `unique=N` が出たら **patch-id containment で追試する** — squash-merge された
   PR は履歴上 unique に見えるが中身は着地している:

   ```bash
   pid=$(git show <branch-tip> | git patch-id --stable | cut -d' ' -f1)
   # origin/main 直近 N commit の patch-id と照合
   ```

   patch-id でもヒットしないときは **PR 実在確認** (`gh pr list --head <branch>
   --state all`) と **file-blob containment** (`git rev-parse <branch>:<path>` を
   main 履歴の各 commit の blob と照合) まで落とす。
2. **生成物は「文字列一致」で判定しない。** 自動生成 file（pin roster 等）は
   差分が pin 値の入替だけなら**陳腐化**であって未着地ではない — main の行数・
   世代が先行しているかを確認する（`;; roster size:` 等の世代カウンタ）。
   **pin 値の集合差分は `:bot/pin` 行を除外して数える** — 古い pin が
   「差分」として数え上がり、実質差分ゼロが埋もれる。
3. **content search で「main に無い」と言う前に対象 directory の実在を
   `git cat-file -e origin/main:<dir>` で引く。** 索引・`git log -- <path>` が空
   でも sparse checkout / 未 checkout の可能性がある。

### tick 台帳 stash の着地判定（as-of 値で行単位）

launchd tick が生成する append-only 台帳（observatory / funnel-pulse 等）の stash は、
行単位で判定する — 番号（stash@{N}）で判定しない:

1. `git stash show -p stash@{N}` から台帳の安定キー（`:as-of` 等の時刻 literal）を
   全部抜き出す。
2. 各値を main の台帳ファイルで `grep -c` する。**着地済みの行と未着地の行が混在する**
   — 並行 session が新しい tick を着地させていると、stash の古い行の一部だけが
   main に在る。
3. 全行が着地済み → **陳腐化**: pop は no-op（"Already up to date" で何も適用せず
   stash を drop する）。手で apply しない — apply は conflict marker を作るだけ。
4. 未着地行が在る → pop して、未着地行が入ったことを grep で確認してから
   台帳 1 file だけ commit。commit message に「recovered from ff-sync stash」と
   出所を書く。
   ⚠ **再生型 snapshot 台帳（各 tick が actor 行を丸ごと差し替える observatory 型）では
   「行が main に無い」は未着地の証拠にならない。** 新しい tick が旧 as-of の行を置き
   換えるので、着地済みでも旧行は main から消える。判定は**世代比較**: 台帳の最新
   `:as-of` を stash と main の両方で取り、main の方が新しいなら stash 全体が陳腐化
   （pop 不要、行を数え直す必要もない）。main が古い、または stash に main 未収録の
   actor が在るときだけ行単位判定に落ちる。**時刻の辞書順比較は裸の文字列で行う** —
   quote 付き (`vals[-1] < '"2026-..."'`) のまま比較すると `"` の影響で常に
   False になり「stash は新鮮」という誤判定になる（実測 2026-09-13）。
5. runtime 実体（state.db / -wal / -shm / schema_columns.json）は行判定の対象外 —
   **pop しない**。bin 差分のみで「緊急退避物」として残す。生成キャッシュの世代差分
   （schema_hash の違い等）は「stash 版が新世代」であっても適用しない: それを書いた
   runtime が管理する。
   **退避した runtime 実体は必ず pop で戻す** — それらは live server が開いている
   working state であり、stash に入れたままにすると次の session の ff-sync 監査で
   恒久的な modified 残りになる。push →（他の stash や ff を処理）→ 即 pop、を
   1 つの sync 手順の中で閉じる。pop 後は兄弟 3 ファイルが全部 working tree に
   戻ったことを status で確認する。

### index.lock と並行 session（superproject 本体）

- **`.git/index.lock` は「中身 + 保持プロセス + 経過時間」で判定してから触る。**
  `ls -la` でサイズと時刻、`lsof <lock path>` で保持プロセス、`ps aux | grep '[g]it'`
  で並行 git 関連プロセスを確認。**0 byte・数十分放置・lsof 空・git プロセス無し** →
  stale として除去してよい。並行 session の java/ssh/node プロセスが見える、または
  除去後に lock が**再出現する** → 生きている競合なので消さず、lock が空くのを待つ
  （sleep 数分 → 存在確認、を数回）。
- **lock がある間に add / commit / stash push を再試行しない。** 失敗自体は無害だが、
  「stale と決めつけて rm → 並行 session の書き込みと衝突」が実際の損害。待って空いて
  から 1 回やる。
- **stale と判定した lock は stash より先に除去する。** lock が在ると
  `git stash push` 自身が `error: could not write index` で落ちる — ff-sync の順序は
  lock 除去 → stash push → ff merge → stash pop。runtime db は `.db` と `-wal` / `-shm`
  を 3 つセットで stash する（`.db` だけ戻すと兄弟が modified 残りで次の ff を止める）。
  **自分の操作が中断した直後の 0 byte lock は自分が作った stale** — `could not write
  index` の直後なら確認なしで除去して再開してよい。
- **launchd tick bot が追記する生成型台帳 (observatory 等) の tracked 変更は ff を
  永遠に阻み続ける** — bot が数時間おきに再生成するため、待っても空かない。ff が
  必要なときは stash push で退避して先に進める (subject に内容と経緯を書く)。
- **stash push の前に、dirty tracked file が incoming 版と byte-identical でないか
  確かめる** — identical なら stash 不要で、`git restore <path>` してから ff する
  （`git show origin/main:<path> > /tmp/x && diff <path> /tmp/x` で判定。退避と
  pop の丸ごと 1 巡が要らなくなり、lock/stash 競合面も減る）。
- 並行 session が同じ superproject の main を先に進めていることがある（HEAD が自分の
  知らない commit に動く）— 触る前に `git fetch` + `git status -sb` で現在地を取り直す。
  **push が非 FF で弾かれたら、自分の commit が既に origin/main に含まれていないか
  `git merge-base --is-ancestor <sha> origin/main` で確認する** — 含まれていれば
  fight せず checkout を pin に合わせるだけでよい。乖離の着地ルーチン:
  `git checkout -b agent/<topic> origin/main` → `git cherry-pick <sha>` → push →
  `gh api …/merges`（サーバ側マージ）→ 後片付け。checkout が tracked dirty で
  阻まれたら stash push してから行う（untracked は通常無視。incoming commit が同
  path を作る時は例外 — 上の untracked WIP 節）。**launchd bot が追記する生成型台帳
  (observatory 等) の tracked 変更は待っても空かない** — bot が数時間おきに
  再生成する。ff が必要なら stash push で退避して先に進める (subject に経緯を書く)。
  **中断した自分の操作が残した 0 byte index.lock は直ちに除去してよい**
  (git プロセス確認は不要) — 自分の stash push 失敗が `could not write index` を
  出したら次の試行の前に lock を見る。

## worktree

- dirty 判定は `git status --porcelain | wc -l`。untracked のみの dirty は
  「未着地候補」として残す（消さない）。
- **着地 (merge → worktree remove) は 1 コマンド列で閉じる**: branch push →
  `gh api repos/<o>/<r>/merges` でサーバ側マージ → **origin/main に着地物の実在を
  `git cat-file -e origin/main:<path>` で確認** → `git worktree remove --force` →
  local/remote branch 削除。merge API が 200 を返しても worktree に居続けると
  並行 session の ff を阻む tracked WIP が生える — 着地確認までが 1 手順。
- 一括撤去は `nbb scripts/worktree-retire.cljs --root . [--apply]`
  （着地済み・clean・7日超・idle のみ削除。dirty は触らない）。**走査は全 worktree
  を数えるので長い（実測: SCANNED 205 で 300s timeout）** — background で回すか、
  自分の worktree は merge 確認後に手動で `git worktree remove --force <path>` +
  `git worktree prune` + branch 削除で片付け、一括走査に巻き込まない。手で消すなら
  clean かつ branch 着地済みのものだけ `git worktree remove`（`--force` 無し）。
- superproject の `orgs/` 直下に切られた worktree は場所だけで誤り（削除候補）。

## 報告形式

表で出す: 対象 / 内容 / 判定（MERGED・LANDED・UNIQUE・陳腐化・緊急退避）。
判定の根拠（patch-id / PR URL / blob 一致 / 差分内訳）を同じ行に載せる。
削除候補は **propose として提示し、owner のゴーアイドを待ってから実行する**
（owner は短い日本語承認。propose-only 進行が既定）。

references/landed-judgment.md に stash / branch 別の判定レシピ（コマンド列）がある。
references/runtime-dir-retirement.md に ~/.gftd 等の runtime directory の
prune 判定（lsof / launchctl / secrets）がある。

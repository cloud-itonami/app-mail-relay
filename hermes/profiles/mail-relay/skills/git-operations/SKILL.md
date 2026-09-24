---
name: git-operations
description: この superproject と west 管理の子リポで git を触るときの正本 — shallow を使わない理由と unshallow の確かめ方、ancestry / merge-base 判定が嘘をつく条件、`(forced update)` と `unrelated histories` が force-push を意味しない理由、west.yml を安全に変える唯一の経路（GitHub API single-entry commit）、pin 検証と pin 鮮度、main 同期・rebase 禁止・force-push 禁止・本番 deploy の包含条件、未コミット変更で同期がブロックされたときの安全な順序、worktree が object store を隔離しない話。「shallow」「unshallow」「merge-base がおかしい」「forced update」「force push していいか」「pull して」「main に同期」「deploy 前の確認」「worktree」で発火。CLAUDE.md の Git operations 節から切り出した正本。 並行エージェント運用（worktree-per-agent、分岐前の同期、stash を積まない、着地後の後片付け、worktree-retire / node_modules dedupe）と Agent 委譲（fork は調査専用、実行系は fresh agent + worktree 隔離、base SHA を渡す）の本文も 2026-09-11 に CLAUDE.md から逐語で移した（ADR-2609112300）。「並行セッション」「worktree を切る」「Agent に委譲」「fork」「subagent」でも発火。
---

# Git operations（詳細）

**CLAUDE.md の「Git operations」節はここへ委譲している。** CLAUDE.md 側には
skill を読まなくても効く禁止・手順だけが残っており、理由・実測・罠はこの文書が正本。
pin 前進の操作面は skill `west-pin-advance`、stash / branch / PR の棚卸しは
skill `git-cleanup-conflict`。

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-08、ADR-2609081000）。

## Git operations

- **shallow（`--depth 1`）は使わない。full 履歴がデフォルト**（2026-07-21、
  ADR-2607211600。ADR-2606241600/2606302100 の shallow 既定を reverse）。
  west が `clone-depth` を fetch のたびに再適用し、触るたびに新しい shallow
  graft（親情報を持たない境界コミット）を作り続けていたことが、下記の
  「forced update」偽陽性・pin 到達失敗を繰り返し引き起こす根本原因だった
  （実測: superproject `.git` が 226 shallow boundary / 18GB に肥大していたのに
  reachable commit はわずか2件）。

  ```bash
  git fetch origin
  git pull --ff-only
  west update --fetch smart        # 各 project を full 履歴で取得
  ```

  大容量バイナリを含む heavy project（旧 `manifest/repos.edn` `:heavy`）も
  含め、2026-07-21 にオーナー判断で全 unshallow する決定をした。disk/帯域コストより
  ancestry の正しさを優先する。恒久的な disk 対策は shallow ではなく
  B2 + DataLad への移行（skill `large-binary-datalad`）。

  **ただしその unshallow は完了していない**（ADR-2608124400。この節は
  2026-08-12 まで「全 unshallow 済み」と完了形で書いていたが、事実ではなかった）。
  shallow のまま残っている子リポがあり、**superproject root 自身も retirement の
  後に ad-hoc な `--depth` fetch で shallow 化されていた**（実行者は特定できて
  いない。west ではないことは実測済み）。**shallow clone の ancestry 回答は
  間違っていて、しかも権威があるように見える** — 実測では「その commit は stale な
  side branch からしか到達できない」と答えたが、実際は `main` の 643 commit 手前に
  在った。したがって下記「マージ / ancestry 判定」がローカル解決を勧めるのは
  **full 履歴が実在する repo でだけ**正しい。判定を出す前に確かめる:

  ```bash
  git rev-parse --is-shallow-repository   # true なら、その repo の ancestry 判定を信用しない
  git fetch --unshallow                   # 直す
  ```

  ⚠ **`git fetch` の `--dry-run` は preview ではない** — ref 更新を飛ばすだけで
  fetch 自体は実行される（`--dry-run --unshallow` が実際に unshallow を完了させた）。

- **マージ / ancestry 判定（full 履歴なら通常は素直に解決する）。**
  `merge-base` / `--is-ancestor` / `rev-list --count` はローカルでそのまま
  正しく解決する（旧 shallow 既定では graft 境界の外に共通祖先があると
  誤判定した）。外部から持ち込まれた一時的な shallow clone と比較する必要が
  生じた時だけ、その場で GitHub 側に計算させる:

  ```bash
  BASE=$(gh api repos/<org>/<repo>/compare/main...<branch> --jq .merge_base_commit.sha)
  git fetch origin "$BASE"      # full 履歴なのでそのまま繋がる
  ```

  さらに、**manifest の pin 前進のような単純更新は、ローカルでマージを戦うより
  GitHub API でサーバ側にクリーン commit を起こす方が確実かつ安い**（optimistic
  lock で conflict が構造的に発生しない）。実例: PR #61 / #62 / #86 は main の
  tree をベースにクリーン commit を API で作成してマージした（#86 は 31 リポの
  west 移行を regression なしで取り込み）。

- **`git fetch` の `(forced update)` 表示や `git merge` の
  `fatal: refusing to merge unrelated histories` は、それ単独では本物の
  force-push と断定しない。** 旧 shallow 既定では `--depth 1` フェッチのたびに
  新しい shallow graft ができ、upstream が**純粋な fast-forward で前進しただけ**
  でも同じ症状（`(forced update)` 表示・`unrelated histories` エラー）が出て
  いた（実測 2026-07-01、`root` superproject: 6 commit 遅れの純前進で両症状が
  発生。これが ADR-2607211600 で shallow 既定を撤回した主因）。full 履歴の今は
  この graft 由来の偽陽性は構造的に起きないが、判定に迷ったら GitHub API で
  比較する:

  ```bash
  gh api repos/<org>/<repo>/compare/<old-local-tip>...<new-origin-tip> \
    --jq '{status, ahead_by, behind_by, merge_base_commit: .merge_base_commit.sha}'
  # status:"ahead" かつ behind_by:0 かつ merge_base_commit == old-local-tip なら
  # 純粋な fast-forward。diverged や merge_base が別物なら本物の force-push。
  ```

  本物の force-push と判明した場合は、下記「force-push は禁止」節の対応
  （ユーザーへの報告）に進む。

- **`manifest/west.yml` への変更（登録 / rename / pin 前進）は GitHub API の
  サーバ側 single-entry commit を「唯一の正経路」にする。** west.yml は生成物
  （手書き禁止）なので、行指向 pin の textual 3-way merge はアンチパターンで、
  conflict marker の手編集は **pin を静かに壊す**。**登録・rename・pin 前進は
  `--entry <name>` で当該 entry のみの最小 diff を生成する — wholesale 再生成
  commit は禁止**（1 件の登録のつもりが未 push HEAD 由来の壊れた pin を 44 件
  main に流した実事故 `90852b86` の再発防止）。

- **west.yml の pin 変更はサーバ側 pin 検証を必ず通す**（`scripts/verify-west-pins.cljk`、
  ADR-2607022900）。pin に許されるのは「上流 repo の default branch から到達可能な
  commit」だけ — ①存在（未 push のローカル HEAD の pin 化は禁止）②default branch
  到達性 ③旧 pin からの前進（behind = 静かな pin 退行）。判定は GitHub API で行い、
  **ローカルの ancestry 判定だけに頼らない**。強制するのは PreToolUse hook
  `.claude/hooks/west-pin-verify-guard.cljk` と murakumo fleet の
  `root-west-pin-policy` gate。

- **`git push` / `git pull` / `west update` の前に、manifest の pin が upstream
  GitHub の最新から取り残されていないか（pin 鮮度）を必ず確認する。** `west update`
  は pin へ checkout を合わせるだけで、GitHub 側の新しいコミットを pin に反映する
  コマンドではない。

  **上記 3 点の手順・コマンド・実測済みの罠は skill `west-pin-advance`。**

### pin の既定状態は「upstream default branch の tip」（repo-wide mandatory、2026-08-20）

**オーナー指示（2026-08-20）「west pull, remote pull また基本的に pin を最新に進める
運用となるように」。** pin が upstream の default branch より遅れているのは、放置して
よい平常状態ではなく**是正対象**である。

- **`git pull` / `west update` /「pull して」の類を指示されたら、checkout を pin に
  合わせるだけで終わらせない。** pin 鮮度まで見て、遅れているものは前進させる。
  「pull」は 3 つの別物を含む: (1) superproject を origin/main に合わせる
  (2) pin を各 repo の default branch tip に進める (3) checkout を pin に合わせる。
  (2) を落とすと、(1) と (3) をいくら回しても workspace は古いまま止まる。
- **前進の経路は変わらない** —— `scripts/west-pin-put.cljk <entry> HEAD`（1 件）か
  `scripts/west-pin-put-batch.cljk`（多件、1 commit に束ねる）。どちらも
  (1) default branch 到達性 (2) 旧 pin からの前進 (3) blob SHA precondition を
  **entry ごとに**検査する。速いから検査を省く、はしない。
- **repo の中の pin も同じ規則に従う。** `deps.edn` の `:git/sha`、lock ファイル、
  `resources/*.edn` に焼いた sha —— どれも「upstream の default branch から到達
  可能」でなければならない。**west pin には `verify-west-pins` という gate があるが、
  `deps.edn` の pin には無い。** 実測 2026-08-20: `kotoba-native` の deps.edn は
  `kotoba-codegen` を `c85088b` に固定していたが、その commit は codegen の main に
  無く、未 merge branch `agent/aarch64-madd-mc` にしかなかった（main はそこから
  5 commit 遅れ）。branch が消えるか force-update された時点で production の依存が
  壊れる。**未 merge branch 上の commit を pin にしない。**
- **例外は「進めない理由を書いた」ときだけ。** 上流の tip が壊れている、API が
  互換性を壊した、意図的に古い挙動に留めている —— どれも正当だが、pin の隣か
  commit message にそう書く。**黙って遅れているのと、理由があって留めているのは、
  出力から区別できなければならない。**
- ⚠ **これは「引数なしの `west update` を回せ」という意味ではない**（上記の罠 2 の
  とおり全 project を歩く）。進めるのは**遅れている pin だけ**で、その集合は
  `gh api repos/<org>/<repo>/compare/<pin>...<default>` の `ahead_by` で決まる。

- **常に `main` と同期し、乖離を作らない（最優先）。** 何らかの git 操作
  （pull / checkout / commit / branch 作業の開始など）を行う前に、上流 `main`
  に更新があれば必ず先に同期する。ローカルが `main` より遅れている状態
  （`git rev-list --left-right --count origin/main...HEAD` の左側が非ゼロ）で
  新しい作業を積み上げない。fast-forward 可能なら `--ff-only` で取り込む:

  ```bash
  git fetch origin
  git pull --ff-only                                 # 乖離していなければ FF で取り込む
  west update --fetch smart                          # project 群を pin に合わせて同期
  ```

  **これは prose instruction だけに頼らず、SessionStart hook
  （`.claude/hooks/session-start-branch-sync-check.cljk`、`.claude/settings.json`
  に登録済み）で毎セッション開始時に自動チェックする。** 実測インシデント
  （2026-07-20）: `agent/pin-docs-edn-only` ブランチが誰も気づかないまま
  `origin/main` から 848 commits ahead / 1607 commits behind まで積み上がった
  （592 ファイル・56万行超の diff）。agent が都度思い出して確認する運用は
  機能しなかったため、hook で ahead/behind を強制的に可視化する
  （閾値超過時は `systemMessage` + `additionalContext` で警告、閾値内でも
  非ゼロなら軽量に表示、失敗時は fail-open でセッション開始をブロックしない）。
  乖離を見つけたら rebase せず、この節の手順か `git-cleanup-conflict` skill
  （848 commits 級の乖離は content-containment 判定 → 新しい clean branch を
  origin/main から切って必要な差分だけ移植、が正解）で解消する。この実インシデントの
  詳細（`projects/` 旧 submodule クローン削除・各リポの actor 外部化検証・
  848 commits 乖離の解消経緯）は `90-docs/adr/2607206700-west-multirepo-monorepo-era-cleanup-audit.edn`
  に記録している。

- **`git push` の前に必ず `origin/main` との遅れを解消する。** push しようとする
  リポ（superproject / 各 project とも）が `origin/main`（既定ブランチ）より遅れて
  いる場合は、先に同期してから push する:

  ```bash
  git fetch origin
  git merge --ff-only origin/main      # FF 不可なら停止。rebase しない
  ```

  これは PreToolUse フック `.claude/hooks/git-push-main-sync-guard.cljk`（nbb）で強制される
  （遅れた状態の `git push` は deny され、同期を促すメッセージが返る）。フックは
  破壊的な自動マージはしない（判定と指示のみ、fail-open）。

- **rebase は基本禁止。** `git rebase` / `git pull --rebase` / rebase での乖離解消を
  標準手順にしない。FF できない stale branch は、最新 `origin/main` から clean branch /
  一時 worktree を作り、必要な小差分だけを `cherry-pick` または patch として載せ直す。
  `manifest/west.yml` の pin 前進は、ローカル rebase で解かず GitHub API single-entry
  commit（または最新 main ベースの clean worktree で当該 entry のみ commit）にする。
  既に rebase を開始して競合した場合は `git rebase --abort` し、marker 手編集で続行しない。
  fleet 活動中など `origin/main` が逐次前進して `git push main` が race する時は、変更を
  feature branch に push し（push 同期ガードは非-main を許可）、`gh api repos/<org>/<repo>/merges
  -f base=main -f head=<branch> -f commit_message=...` で **サーバ側マージ commit** を作る。
  push race に触れず、409(conflict/race) で再試行。実績: ADR-2606302300（org 分類
  そのものは**その後 superseded**。ここで引いているのは当時この経路で着地させたという
  記録であって、現行の org 分類の根拠ではない）の doc commit をこの経路で main 化
  （rebase も force-push も使わず）。

- **force-push は禁止（`git push --force` / `--force-with-lease` / `+refs` を使わない）。**
  共有リポ（superproject / 各 project）のいかなるブランチに対しても、履歴を書き換えて
  上流を上書きする push をしてはならない。force-push は他の clone・west pin・
  ancestry 判定を静かに壊し、`upload-pack: not our ref` 由来の checkout 失敗を引き起こす。
  **逆に `(forced update)` 表示や `unrelated histories` エラーだけでは本物の force-push と
  断定できない**（判別法は上述「マージ / ancestry 判定」節）。
  確度の高い実サインは `upload-pack: not our ref` によるチェックアウト失敗。乖離は
  **force-push ではなく fast-forward できる clean branch / clean commit** で解消し、
  それが不可能な場合（既に push 済みの履歴を変えたい等）は**勝手に強制せず必ずユーザーに報告**する。
  履歴書き換えが本当に必要なときも**行わない**。upstream を進めたいだけの単純更新は、ローカルで
  戦うより GitHub API でサーバ側にクリーン commit を起こす（PR #61/#62/#86 の実績）。

- **`main` への同期が未コミット/未追跡のローカル変更でブロックされた場合**、
  勝手に破棄しない。次の順で安全に同期する:
  1. ブロック原因の未追跡ファイルが **incoming とバイト同一** なら（origin に
     既に存在する掃き出しファイル）削除して安全。`shasum` で確認してから消す。
  2. 本物のローカル編集（incoming に未含有）は `git stash push -- <paths>` で
     退避してから pull する。**stash は drop せず温存**して owner が後で
     reconcile できるようにする。
  3. stash pop で衝突したら、本リポジトリの方針として **upstream(`main`) 側を
     採用**して解決し（`git checkout --ours -- <file>`）、ローカル差分は stash
     と未追跡実体として残す。乖離より main 同期を優先する。

- ユーザーが「git pull」とだけ指示した場合も、上記の main 同期 + `west update`
  まで含めて実行する（プルだけで終わらせない）。

- **本番デプロイは `origin/main` を包含した checkout からのみ行う。** デプロイは
  push と違って fast-forward 検査を持たない——**最後に実行した人が勝つ**ので、
  main より古い checkout から出荷すると、その間に他セッションが入れた変更を
  黙って巻き戻す。実インシデント（2026-07-25）: kotobase.net の signup funnel が
  404 だったのを直して 07:01 に deploy した11分後、別セッションが**その変更を
  含まない古い checkout** から同じ Worker を deploy し、funnel が 404 に戻った
  （誰も気付かなかった）。デプロイ前に:

  ```bash
  git fetch origin && git merge --ff-only origin/main   # FF 不可なら乖離。rebase しない
  ```

  これは PreToolUse フック `.claude/hooks/wrangler-deploy-main-sync-guard.cljk`
  （nbb、`.claude/settings.json` に登録済み）で強制される。`wrangler deploy` /
  `wrangler versions deploy` / `npm|pnpm|yarn run deploy` を対象に、checkout が
  `origin/main` より遅れていれば deny する。**隔離環境（`--env <name>`：
  staging / testnet / b2 等）と `--dry-run` はブロックしない**——feature branch を
  隔離環境で検証するのは正常な作業であり、そこを塞ぐと検証自体ができなくなる。
  フックは破壊的な自動同期をしない（判定と指示のみ、fail-open）。

- **`git push` / PR 作成・更新の前に、superproject と west の両方を最新化してから
  行う。** push や PR（`gh pr create`/`gh pr ready`/PR への追加 commit 等）の直前に、
  逐次・省略せず、以下を必ず実行してから push/PR する:

  ```bash
  git fetch origin                                 # origin/main 他を取得
  git merge --ff-only origin/main                  # superproject を main に同期（FF 不可なら停止。rebase しない）
  west update --fetch smart                        # 子リポ群を manifest の pin に合わせて同期
  kbb --backend sci scripts/gen-west-manifest.cljk --check          # west.yml が canonical か（生成器と一致か）確認
  ```

  これらを飛ばして push/PR すると、main 乖離・west.yml の pin 退行・子リポの
  checkout 不一致が他者 clone や CI に伝播する。`west.yml` は生成物（手書き禁止）
  なので、`--check` が STALE なら **ローカル pin 退行の罠**（`gen-west-manifest.cljs`
  はローカル working HEAD で pin する＝子が遅れていると黙ってロールバック）に注意しつつ
  再生成し、`--check` が通ってから push/PR する。子リポ単位の push/PR も同様に、
  その子リポの `origin/<default-branch>` との遅れを解消してから行う。

- ユーザーが「cleanup」とだけ指示した場合、または PR/merge/stash/merge conflict の
  整理を依頼した場合、あるいは自分から `git stash drop` / `git branch -D` をしようと
  している場合は、**Skill ツールで `git-cleanup-conflict` を呼ぶ**
  （`.claude/skills/git-cleanup-conflict/SKILL.md`。Codex 側の同名 skill
  `$git-cleanup-conflict` と同じ runbook を共有）。手順の正本は
  `manifest/cleanup-workflow.edn`（readable 版が `manifest/cleanup-workflow.md`）—
  **trigger した節だけでなく edn 全体（`:retirement`/`:stash-pop`/`:west-conflict`
  含む）を読む**。superproject と `orgs/` 配下などの子リポを含め、WIP を破棄せず、
  `cleanup` メッセージで PR を作り、merge 可なら main へ merge し、残った stash/
  未追跡 repo を報告する。**stash/branch を drop/削除する前は「もう landed だと
  確信していても」必ず `.git/stash-archive-<date>/` へ退避してから**（実際に
  2026-07-04、確信を理由に archive を省略して drop した事例あり — 幸い
  `git fsck --unreachable` で拾えたが、運に頼らない）。

- **west project の checkout が「ローカルの未コミット変更」で失敗（衝突）した場合**、
  勝手に `west update --force` 等で破棄しないこと。`west` は既定で破壊的更新を
  しない（衝突時は当該 project を skip）。ユーザーに確認するか、まず差分を提示する。
  ローカル作業が残る project は manifest の pin を進める前に reconcile（commit &
  push）する。

- **project の checkout が「リモートに存在しない ref」（`upload-pack: not our ref`）**
  で失敗した場合は、上流で force-push された可能性が高い。`manifest/west.yml` の
  当該 pin（= repos.edn 経由）の見直しが必要なので、ユーザーに報告する。

- **複数セッション/エージェントが並行作業する可能性がある時は、共有の west checkout
  （`orgs/<org>/<repo>`）を直接編集せず、セッションごとに `git worktree` を切る。**
  west が管理するパスは1つの共有 working tree なので、別セッションがそこで
  `git checkout`（ブランチ切替）すると、自分がまだコミットしていない編集が
  working tree 上で**黙って巻き戻される**（実例: 2026-07-01、`orgs/kotoba-lang/
  kami-engine` で `sip.render`/`sip.world` への未コミット編集が、並行していた
  別セッションの `kami-isaac-sim-wasm` 作業のブランチ切替で失われかけた）。
  作業前に一時 worktree を切って、そこで完結させる:

  ```bash
  git worktree add -B <session-branch> <path> origin/main   # 独立 working tree
  # ... <path> で編集 / commit / test ...
  git push origin <session-branch>
  gh api repos/<org>/<repo>/merges -f base=main -f head=<session-branch> \
    -f commit_message="..."                                 # サーバ側マージ（ローカル merge/rebase を戦わない）
  git worktree remove <path>                                 # 使い終わったら片付ける
  ```

  **`<path>` は superproject ルートの外（例: scratchpad / `/tmp` 配下）にする。**
  `.claude/worktrees/` 等 superproject 内側に worktree を作ると、west は `.west/`
  を親ディレクトリへ辿って発見するため topdir が superproject ルートのままになり、
  worktree 内で `west update` しても実際には共有の `orgs/` を操作してしまう
  （false isolation。`WEST_TOPDIR` 環境変数でも直らない）。west コマンドを worktree
  内で使う必要がある場合は、外側に作った上でさらに `west init -l manifest` を
  worktree 内で実行し、worktree ローカルな `.west/` を作って topdir を固定する。
  詳細は ADR-2607011345。plain git（commit/push、west 不使用）だけなら
  superproject 内側の worktree でも問題ない。

  **worktree が隔離するのは working tree であって object store ではない。**
  linked worktree は `$GIT_COMMON_DIR` を元リポジトリと共有するので、
  **`/tmp` に作った「使い捨て」worktree の中で `--depth` 付き fetch をすると、
  superproject 本体が shallow になる**（`.git/shallow` は共有される）。
  実測 2026-08-12: root が shallow になっていた最有力経路がこれで、
  痕跡はどのログにも残っていなかった（**ref を動かさない depth fetch は
  reflog に entry を書かない**ため）。**worktree は `.git` に書くものに対する
  sandbox ではない。** 詳細は ADR-2608124400。

  共有 checkout（west 管理パス）には直接 commit/push しない。worktree 経由で
  main に着地させたあと、共有 checkout 側は `git fetch` と（内容一致を `shasum`
  で確認した上での）重複ファイルの削除だけで追従させる。

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## Git operations

**理由・実測・罠は Skill ツールで `git-operations` を呼ぶ。** pin 前進の操作面は
`west-pin-advance`、stash / branch / PR の棚卸しは `git-cleanup-conflict`。
ここに残すのは skill を読まなくても効く禁止と手順だけ。

### 履歴と ancestry

- **shallow（`--depth 1`）は使わない。full 履歴がデフォルト**（2026-07-21、
  ADR-2607211600）。`west update --fetch smart` で各 project を full 履歴で取得する。
  恒久的な disk 対策は shallow ではなく B2 + DataLad（skill `large-binary-datalad`）。
- **その unshallow は完了していない**（ADR-2608124400）。**shallow clone の ancestry
  回答は間違っていて、しかも権威があるように見える。** 判定を出す前に確かめる:

  ```bash
  git rev-parse --is-shallow-repository   # true なら、その repo の ancestry 判定を信用しない
  git fetch --unshallow                   # 直す
  ```

  ⚠ **`git fetch` の `--dry-run` は preview ではない** —— ref 更新を飛ばすだけで
  fetch 自体は実行される。
- **full 履歴なら `merge-base` / `--is-ancestor` はローカルでそのまま正しい。**
  外部由来の shallow clone と比べる必要があるときだけ GitHub に計算させる
  （`gh api repos/<org>/<repo>/compare/...`）。
- **`(forced update)` 表示や `unrelated histories` エラーは、それ単独では本物の
  force-push と断定しない。** 確度の高い実サインは `upload-pack: not our ref` に
  よるチェックアウト失敗。迷ったら `gh api .../compare/<old>...<new>` の
  `status` / `behind_by` / `merge_base_commit` で判定する。

### 禁止

- **force-push は禁止**（`--force` / `--force-with-lease` / `+refs`）。共有リポの
  いかなるブランチにも、履歴を書き換えて上流を上書きする push をしない。乖離は
  fast-forward できる clean branch / clean commit で解消し、それが不可能なら
  **勝手に強制せず必ずユーザーに報告する。**
- **rebase は基本禁止**（`git rebase` / rebase 付き pull）。FF できない stale branch は、
  最新 `origin/main` から clean branch / 一時 worktree を作り、必要な小差分だけを
  `cherry-pick` または patch で載せ直す。競合したら `git rebase --abort` し、
  marker 手編集で続行しない。
- **`manifest/west.yml` は生成物。行指向 pin の textual 3-way merge はアンチパターンで、
  conflict marker の手編集は pin を静かに壊す。** 登録 / rename / pin 前進は GitHub API の
  サーバ側 **single-entry commit**（`--entry <name>`）を唯一の正経路とし、
  **wholesale 再生成 commit は禁止**（未 push HEAD 由来の壊れた pin を 44 件 main に
  流した事故 `90852b86` の再発防止）。
- **pin に許されるのは「上流 repo の default branch から到達可能な commit」だけ**
  （`scripts/verify-west-pins.cljk`、ADR-2607022900）。①存在 ②default branch 到達性
  ③旧 pin からの前進。判定は GitHub API で行い、**ローカルの ancestry 判定だけに
  頼らない。** 強制するのは PreToolUse hook `.claude/hooks/west-pin-verify-guard.cljk` と
  fleet gate `root-west-pin-policy`。
- **未 merge branch 上の commit を pin にしない** —— `deps.edn` の `:git/sha`、lock、
  `resources/*.edn` に焼いた sha も同じ規則。**west pin には gate があるが、
  `deps.edn` の pin には無い。**

### 同期（最優先）

- **セッションを始める前に、toolchain の checkout を west pin に合わせる**（repo-wide
  mandatory、2026-09-09、ADR-2609092500）。名簿は `manifest/session-sync.edn`、実行は
  SessionStart hook `.claude/hooks/session-start-toolchain-pin-sync.cljk`（`--dry-run`
  で測るだけ）。**clean な checkout は黙って pin に合わせ、次の 3 つだけ触らずに報告する**
  —— tracked な変更がある／branch 上に未 push の commit がある／pin の commit が手元に
  無く fetch が予算内に終わらなかった。untracked は checkout を妨げないので無視する。
  - **これは警告ではなく同期である。** 既存の `session-start-checkout-staleness` は
    checkout を「自分の remote の default branch」と**読み手の多い順**で比べるので、
    誰も `:local/root` しない toolchain repo は順位に入らない。実測 2026-09-09:
    共有 `amu` checkout が**自分の west pin より 200 commit 遅れ**、その checkout が
    pin する kotoba-sema は main より 71 遅れで、pure S-expression core が
    「無い」ものとして数日間拒否され続けた。**pin は正しく、tree だけが腐っていた。**
    hook 登録初日の実測でも `kotoba-sema` / `kotoba-native` が pin より遅れていた。
  - **pin 自体の鮮度も同じ hook が出す**（pin が最後に fetch した `origin/main` より
    遅れていれば行数と `kbb --backend sci scripts/west-pin-put.cljk <name> HEAD` を示す）。**pin の
    前進は自動でやらない** —— 到達性検証を伴う書き込みで、共有 checkout からは行わない
    （上記「pin を動かす・同期する」節）。
  - **`checkout` / `west pin` / `repo の main` は 3 つの別物**という既存の規則の、
    3 番目ではなく**1 番目**を機械で閉じるのがこの hook。結論を出す前に origin/main を
    読む規則（ADR-2608136800）はそのまま生きている。


- **常に `main` と同期し、乖離を作らない。** 何らかの git 操作の前に、上流 `main` に
  更新があれば必ず先に取り込む。ローカルが遅れた状態で新しい作業を積み上げない。

  ```
  git fetch origin
  git merge --ff-only origin/main    # FF 不可なら停止。rebase しない
  west update --fetch smart          # project 群を pin に合わせて同期
  ```

  SessionStart hook `.claude/hooks/session-start-branch-sync-check.cljk` が毎セッション
  ahead/behind を可視化する。**警告を読むことと同期することは別の動作**で、
  前者は後者を保証しない。
- **push の前に `origin/main` との遅れを解消する**（`git merge --ff-only origin/main`。
  FF 不可なら停止、rebase しない）。PreToolUse hook `git-push-main-sync-guard.cljs` が強制する。
- **push / PR 作成・更新の前に、superproject と west の両方を最新化する。** 逐次・省略せず
  `git fetch origin` → `git merge --ff-only origin/main` → `west update --fetch smart` →
  `kbb --backend sci scripts/gen-west-manifest.cljk --check` を実行してから push / PR する。
- **pin の既定状態は「upstream default branch の tip」**（オーナー指示 2026-08-20）。
  「pull して」は 3 つの別物を含む —— (1) superproject を origin/main に合わせる
  (2) pin を各 repo の default branch tip に進める (3) checkout を pin に合わせる。
  **(2) を落とすと、(1) と (3) をいくら回しても workspace は古いまま止まる。**
  前進の経路は `scripts/west-pin-put.cljk` / `west-pin-put-batch.cljs`、**千本単位なら
  `west-pin-put-bulk.cljk`**（同じ 3 検査を GraphQL 50 repo/query で行い、409 は差分だけ再検証。
  実測 2026-09-11: 3,907 pin を 1 commit・約 4 分。batch は同じ量で 5,000/h を食い潰した）。
  進めない理由があるなら pin の隣か commit message に書く ——
  **黙って遅れているのと、理由があって留めているのは、出力から区別できなければならない。**
  ⚠ これは「引数なしの `west update` を回せ」という意味ではない。
- **本番デプロイは `origin/main` を包含した checkout からのみ行う。** デプロイは push と
  違って fast-forward 検査を持たない —— **最後に実行した人が勝つ**（2026-07-25、
  kotobase.net の signup funnel が 11 分後に古い checkout からの deploy で 404 に戻った）。
  PreToolUse hook `wrangler-deploy-main-sync-guard.cljs` が強制する
  （`--env <name>` の隔離環境と `--dry-run` はブロックしない）。
- **ユーザーが「pull して」とだけ指示した場合も、main 同期 + `west update` + pin 鮮度まで
  含めて実行する**（取り込みだけで終わらせない）。

### 破棄しない

- **`main` への同期が未コミット/未追跡のローカル変更でブロックされたら、勝手に破棄しない。**
  ①incoming とバイト同一なら（`shasum` で確認して）削除 ②本物のローカル編集は
  `git stash push -- <paths>` で退避し、**stash は drop せず温存** ③pop で衝突したら
  upstream 側を採用し、ローカル差分は stash と未追跡実体として残す。
- **west project の checkout がローカル変更で失敗しても `west update --force` で破棄しない**
  （west は既定で破壊的更新をしない）。`upload-pack: not our ref` で失敗した場合は
  上流 force-push の可能性が高いのでユーザーに報告する。
- **「cleanup」と言われたら、また自分から `git stash drop` / `git branch -D` をしようと
  しているときは、Skill ツールで `git-cleanup-conflict` を呼ぶ。** drop / 削除の前は
  「もう landed だと確信していても」必ず `.git/stash-archive-<date>/` へ退避する。

### worktree

- **並行作業の可能性があるときは、共有 west checkout（`orgs/<org>/<repo>`）を直接編集せず
  worktree を切る。** 別セッションのブランチ切替で未コミット編集が黙って巻き戻る。
- **`<path>` は superproject ルートの *外*にする。** 内側に作ると west が親の `.west/` を
  見つけて topdir を誤認し、**本体の `orgs/` を書き換える**（`WEST_TOPDIR` でも直らない。
  ADR-2607011345）。west を worktree 内で使うなら、そこで `west init -l manifest` を
  やり直して topdir を固定する。
- ⚠ **worktree が隔離するのは working tree であって object store ではない。**
  `/tmp` の使い捨て worktree で `--depth` 付き fetch をすると、**superproject 本体が
  shallow になる**（`.git/shallow` は共有。ref を動かさない depth fetch は reflog にも
  残らない）。**worktree は `.git` に書くものに対する sandbox ではない。**
- 着地は共有 checkout へ直接 push せず、branch を push して
  `gh api repos/<org>/<repo>/merges` でサーバ側マージする。

## 並行エージェント運用（worktree-per-agent / stash を積まない）

複数セッション・エージェントが同時に走る前提の標準フロー。stash・branch・worktree の
無限増殖はこのフローからの逸脱の症状（実測: 2026-07-01→02 の一晩で、共有 checkout 上の
WIP を並行セッションが約40分間隔で退避し続け stash が20個堆積。棚卸しの結果、実質的な
未着地は2件だけで残り18件は着地済み/陳腐化だった）。

### 分岐を作る前に、必ず local を remote に同期する（前提条件・repo-wide mandatory、2026-07-29）

**agent loop の起動・Agent への委譲（fork / fresh agent）・`git worktree add`・
`git checkout -b` / `git switch -c`・新しい clone からの作業開始 — これらを行う「前」に、
対象リポジトリを必ず remote と同期する。** 同期していない状態で分岐を作らない。
「agent loop の起動」には **`Workflow` の実行・`/loop`・スケジュール routine
（`RemoteTrigger` / cron）の開始**を含む — 反復して agent を起こす仕組みは、1回目の
base が古ければ以降の全反復が古い base に載る。

```bash
git fetch origin
git merge --ff-only origin/main      # FF 不可なら停止。rebase しない
west update --fetch smart            # 子リポ群を manifest の pin に合わせる
# 子リポも触るなら、その repo でも fetch + merge --ff-only origin/<default-branch>
```

**FF できない（diverged / ahead）場合は、分岐を作る前にその乖離を先に解消する。**
rebase も force-push もしない — 未着地のローカル commit は feature branch へ push して
`gh api repos/<org>/<repo>/merges` でサーバ側マージし、それから分岐する（手順は上記
「Git operations」節と skill `git-cleanup-conflict`）。`manifest/west.yml` の pin だけなら
GitHub API の single-entry commit で tip に直接載せる方が確実。**乖離を抱えたまま
「とりあえず枝を切る」は、その乖離を枝の数だけ複製する。**

**分岐元は必ず `origin/main` を明示する**（ローカル `main` ではなく）。これが最も確実で、
ローカルが遅れていても正しい base から始まる:

```bash
git worktree add -b <branch> /tmp/root-<name> origin/main   # ✅ 分岐元が明示されている
git worktree add -b <branch> /tmp/root-<name>               # ❌ 遅れたローカル HEAD から分岐する
```

**なぜ「分岐の瞬間」が特別なのか。** 遅れた base の上に積んだ commit は、後から同期しても
遅れたままになる — その worktree で行った作業**全部**が古い base に載っており、着地時に
乖離・conflict・pin 退行として現れる。push 直前に同期しても手遅れで、そこから救うには
CLAUDE.md が禁じている rebase か、clean branch への移植が要る。**同期のコストは分岐前なら
`git fetch` 1回、分岐後なら作業のやり直し**という非対称性が、この規則が独立して存在する
理由。

**SessionStart hook（`session-start-branch-sync-check.cljs`）はこれを代替しない。**
あれはセッション開始時点の ahead/behind を1回警告するだけで、その後セッション中に上流が
進んだ場合も、警告を見たまま同期せず分岐した場合も止めない。実測（2026-07-29、この規則が
生まれたセッション）: hook が「main が origin/main から 0 ahead / 78 behind」と正しく警告
したにもかかわらず、同期しないまま作業を開始し、superproject の同期は数十分後の
push 直前まで行われなかった。**警告を読むことと同期することは別の動作**で、前者は後者を
保証しない。

**これは PreToolUse hook `.claude/hooks/branch-create-main-sync-guard.cljk` で強制する**
（`.claude/settings.json` に登録済み）。対象は `git worktree add` / `git checkout -b` /
`git switch -c` / `git branch <new>`。**分岐元を `origin/<default>` で明示していれば
ブロックしない**（それが推奨形であり、ローカルの遅れと無関係に正しい base になるため）。
判定不能時は fail-open（セッションを止めない）。

**同期を省略してよいのは、git を一切書き換えない読み取り専用タスクだけ**（`Explore` での
検索、既存ファイルの読解、`gh api` の GET など）。書き込みが 1 バイトでもあるなら省略しない。

- **superproject 本体 checkout（このフォルダ）は「統合・閲覧専用」。** ここでは編集・
  commit・ブランチ切替をしない。やってよいのは `git fetch` / `--ff-only` pull /
  `west update` / 読み取りだけ。本体に未コミット編集が転がっていると、並行セッションの
  main 同期のたびに「他人の WIP を stash 温存」が発火して stash が堆積する。
- **作業は 1 task = 1 branch = 1 worktree（superproject の外、sibling path）。**
  既定入口は `kbb --backend sci scripts/root-worktree.cljk create <task>`（ADR-2608291248）。
  `origin/main` fetch → `--no-checkout` → cone sparse checkout + sparse-index を行い、
  root 23万件を毎回展開しない。ADR/政策は `--profile docs|policy`、追加 directory は
  `--include <path>`。west child が必要なら `--west <name>` を明示し、対象だけを
  `west update --fetch smart` する。full root は `--profile full` を**明示した場合だけ**。
  worktree 内の `west init -l manifest` と superproject 外配置で topdir を固定する。
- **WIP の退避は stash でなく session branch への commit。** commit は名前・履歴・
  所有者が付き branch 単位で棚卸しできるが、stash は無名の共有スタックで誰のものか
  追えなくなる。stash を使ってよいのは「共有 checkout で見つけた他人の未コミット WIP を
  消さないための緊急退避」だけで、積んだら cleanup で必ず棚卸しする。
- **着地後の後片付けまでがタスクの完了条件。** push → サーバ側マージ
  （`gh api .../merges`）→ `git worktree remove` → `git branch -D <branch>` →
  マージ済み remote branch の削除。「マージしたのに branch/worktree が残っている」
  状態を作らない。
- **worktree モデルはディスクを理由に捨てない。捨てる理由になるのは「同時書き手が 1 人」だけ**
  （オーナー判断 2026-09-06、ADR-2609061800）。「この端末だけで開発する」に変わっても、
  この端末では Claude セッション・codex・launchd の `cloud.itonami.bot.*` bot が同時に書いている
  （数え方: `ps -axo command | grep -c '^claude'`、`launchctl list | grep -c com.gftd`）。
  worktree の作成は sub-second・object store は共有・working tree は再生成物を除けば
  ディスクの 1% 台で、**本当のコストは「着地したのに残る worktree」と「worktree ごとに
  複製される node_modules」の 2 つ**。どちらも機械で消す:
  - **片付け**: `kbb --backend sci scripts/worktree-retire.cljk --root . [--apply]` —— 着地済み・clean・
    7 日超・idle（lsof の cwd / ps の argv に無い）・unlocked・非 bot の worktree だけを
    `git worktree remove`（`--force` 無し）+ `git branch -d` で撤去し、stale entry を
    prune する。dirty は触らない（git-cleanup-conflict の領分）。lsof が引けなければ
    `REFUSED`（exit 2）。launchd `cloud.itonami.bot.worktree-retire` が日次で `--apply`。
    ⚠ **2026-09-09 実測: この job は install も load もされていない。** 名簿にあることと
    走っていることは別で、この節が書いている日次実行は起きていなかった。
  - **node_modules は pnpm store 経由で入れる**: `kbb --backend sci scripts/worktree-node-modules-dedupe.cljk
    --root . [--apply]` が npm lockfile の worktree を `pnpm import` + `.npmrc`
    `node-linker=hoisted` + `pnpm install --frozen-lockfile` に置き換える。pnpm は APFS で
    store から clonefile するので **`du` は減らない。実消費は `df` で測る**（worktree
    1 本あたり約 1 MB）。新しい repo は最初から `pnpm-lock.yaml` + `packageManager` +
    `.npmrc`（hoisted）を持たせる。`npm run <script>` の呼び出しは変えなくてよい ——
    変わるのは install だけ。
  - superproject の **内側**（`orgs/<org>/` 直下）に切られた worktree は、どのモデルでも
    誤り（ADR-2607011345）。retire は場所で除外しないので着地済みから順に消える。
- **stash / branch の棚卸し（retirement）は Skill `git-cleanup-conflict` を使う**
  （手順の正本は `manifest/cleanup-workflow.edn` の `:retirement`、readable 版は
  `manifest/cleanup-workflow.md` の Retirement 節）: 着地判定（追加行が現 main に
  含まれるかの content-containment。生成物 `manifest/west.yml` は判定から除外）→
  **drop/削除の前に必ず** `.git/stash-archive-<date>/` へパッチを退避（「landed だと
  確信している」は archive 省略の理由にならない）→ drop / 削除。並行セッションが
  stash index をずらすので、drop は SHA を控えて毎回 index を再解決してから行う。


## Claude Code の Agent 委譲 — fork は調査専用、実行系は fresh agent + worktree 隔離（2026-07-12）

**`subagent_type: "fork"` は会話コンテキスト全体（この CLAUDE.md 含む）を継承する。**
このため「調査だけしてコードは書くな」とプロンプトで明示しても、継承した
コンテキストに本ファイルの「標準作業の常時許可」（新規 project 起こし → scaffold →
push → 登録を確認なしで一気通貫）や、直前のユーザーとの設計判断が含まれていると、
fork がそちらを実行許可として拾い、指示範囲を超えて実装・scaffold・push 準備まで
勝手に完了させることがある（実測 2026-07-12: 「調査のみ」と明示した fork が
`orgs/kotoba-lang/crm` / `orgs/cloud-itonami/cloud-itonami-isic-5820` に新規
ライブラリ+アクターの本実装一式を無断で書き込み、TaskList に push/registry更新/ADR
執筆までの段取りを自分で積んだ）。同時に、書き込み先が共有 west checkout 直下
（`orgs/<org>/<repo>`）で `.git` 未初期化のまま裸ディレクトリとして置かれており、
上記「並行エージェント運用」節が禁じる「共有 checkout 直接編集」にも該当した。

- **fork は「読むだけ・調べるだけ」に限定する。** ファイル作成・編集・`git`
  書き込み・`gh repo create`・push を伴う実行系タスクには fork を使わない。
- **実行系タスクは fresh agent（`subagent_type` に `fork` 以外を指定、または省略）
  に振る。** fresh agent は会話コンテキストを継承しないため、本ファイルの標準作業
  許可を本人が読んでいない限り「勝手に許可を拾って暴走」しない。プロンプトは
  self-contained に書き、実行してよい範囲を明示する。
- **共有 `orgs/` 配下に触れる実行系タスクは、fresh agent に `isolation: "worktree"`
  を付けて隔離する。** それが使えない/不十分な場合は上記の sibling-path
  `git worktree add` を手動で切ってから作業させる。superproject 本体の `orgs/` に
  直接書き込ませない。
- **委譲・agent loop の起動の前に、local を remote に同期しておく**（上記
  「分岐を作る前に、必ず local を remote に同期する」）。`isolation: "worktree"` の
  worktree はその時点のローカル HEAD から切られるので、**遅れた checkout から委譲すると
  agent の作業全部が遅れた base に載る**。`git worktree add` を手で切る場合と違い、
  委譲や loop の起動は git コマンドではないので **PreToolUse hook は止められない** —
  ここだけは prose の規律で守るしかない。委譲前に `git fetch origin &&
  git merge --ff-only origin/main` を済ませてから `Agent` を呼ぶ。
- **委譲する agent のプロンプトに、同期済み base の commit SHA を書いて渡す。** fresh agent は
  会話コンテキストを継承しないので、自分がどの base で作業しているかを本人は知らない —
  SHA を渡しておけば、agent 側が着地時に「自分の base が現 main と一致するか」を自力で
  検証でき、古い base への上積みが黙って進むのを防げる。


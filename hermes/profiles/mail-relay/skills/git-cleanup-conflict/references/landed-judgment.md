# 着地判定レシピ — stash / branch

## stash の着地判定フロー

1. `git stash list` で subject と件数を数える。
2. **patch-id containment**: `git stash show -p stash@{N} | git patch-id --stable`
   と、origin/main 直近 200-500 commit の `git show <c> | git patch-id --stable`
   を照合。1 件でも一致すれば LANDED。
3. patch-id でヒットしない stash は **file 別に中身を確認**:
   - 自動生成 file（roster, manifest, lock 等）→ pin 値等の世代差分だけか見る。
     pin 行を除外した実質差分ゼロなら陳腐化（drop 候補）。
   - ADR / ledger / ソースへの手編集 → UNIQUE。preserved branch か新しい
     landing 先が必要。**このとき内容が別 branch に保持されていないか確認**
     （preserved/* に同じ blob があれば stash だけ削っても内容は生きる）。
   - `.env` や他人の WIP を含むもの → 中身を読まず「緊急退避物」として残す。

## branch の着地判定フロー

```
# 1. 一括スキャン
for b in $(git branch --format='%(refname:short)'); do ...; done
# merge-base == tip -> MERGED(fully contained)
# unique=N -> 追試へ

# 2. 追試 A: patch-id（squash-merge 検出）
pid=$(git show <tip> | git patch-id --stable | cut -d' ' -f1)

# 3. 追試 B: PR 実在
gh pr list --head <branch> --state all
# MERGED なら branch は -d で削れる（squash 由来の unique=1 は正常）

# 4. 追試 C: file-blob containment（preserved 系）
bv=$(git rev-parse <branch>:<path>)
# main 履歴の各 commit の <path> blob と照合。全 file 一致なら contained
```

## 実測上の注意

- `git log --format='%H' -500 origin/main -- <path>` の形は `-- <path>` を
  取らない呼び方を混ぜると `unrecognized argument: -` で全件死ぬ。commit 列は
  先に変数へ落とす。
- directory の実在確認は `git cat-file -e origin/main:<dir>`。sparse checkout
  では手元に無い file が main に存在する（cat-file は tree を直接見る）。
- 総当たり blob 照合は file 数 × commit 数で重い。候補 file は
  `git diff --name-only origin/main...<branch> | head` で絞る。

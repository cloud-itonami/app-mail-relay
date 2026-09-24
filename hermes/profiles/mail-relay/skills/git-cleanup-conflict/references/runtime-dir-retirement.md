# runtime directory の prune 判定（~/.gftd 等）

superproject 外の runtime directory（bot 状態 / cache / worktree / 鍵）は共有
checkout と違う規則で判定する。「使わないから消して」は lsof / launchctl で
反証してから受ける。

## 生存測定（propose の前に全て実行）

1. `lsof +D <dir>` — directory 内の file を掴んでいる process。worktree 配下に
   cwd がある node/python/bash の PID はその worktree が live。
2. `launchctl list | grep -c <prefix>` — 登録済み job 数。30+ job が読んでいる
   directory の全面 prune は job 移設タスクであって削除タスクではない。
   **全面撤去を約束せず、安全部分集合に絞って提示する。**
3. secrets は file 名だけで判定し中身を開かない: `*.pem` / `*.did` /
   `*token*` / `*seed*`。鍵 material がある dir は移動・削除とも owner 判断。
4. superproject への symlink（`orgs/...` 宛）は skip — 実体は orgs/ 側にあり
   ここでは回収できない。
5. `du -sh` を subdirectory 毎に取り、撤去で実際に回収できる量を順位付けする
   （大半は worktrees subtree が占める。log や小さい状態 file ではない）。

## 安全部分集合（propose → owner ゴーアイド → 実行）

- 削ってよい worktree: clean（status --porcelain 空）かつ process 無しかつ
  対応 branch が着地済み。**dirty でも process があるものは bot が書き込み中 —
  触らない。**
- 削らない: 稼働中 job の cache（job 停止とセットでないと消せない）、鍵、
  live server の状態 dir、symlink。

## 注意

- log の mtime が数分前なら「使っていない」前提は崩れている — directory が
  live かどうかは file 名一覧と mtime で取れる安い観測で確かめる。

# last_error → 対応 決定表

`last_error` の substring か、その `last_status` / `failure_streak` からどう扱うかを
一方向で決める。**後で loadavg を見る前に**これを当てて buckets を先に数え、
その後で host 要因として一括解釈する。間違えやすい3つ（shutdown / drift_skip /
script-not-found）は太字。

| last_status | last_error substring | 分類 | 扱い |
|---|---|---|---|
| error | **"Gateway shutdown" / "Interrupted by shutdown"** | interrupt 巻き添え | host 過負荷・ゲートウェイ再起動波の self-heal。load 落ちても streak 伸び続ける時だけ真の障害 |
| error | **"drift_skip" / "Skipped to prevent unintended spend"** | 費用手ガード | 意図的スキップ、次 tick で再実行。修正不要。error 率計算前に分離 |
| error | "Connection error" | conn | 新 profile 初回 = .env の API key 欠如（config だけでは足りない）。稼働済み profile なら外部接続 |
| error | "Broken pipe" / "connection_refused" | transient conn | gateway 再起動作業と cron 発火のタイミング衝突。murakumo 503 "Loading model" も同型（cold start）。対処は murakumo-main alias の健全性確認 (POST 200) 後 `hermes cron run <id> --profile <p>` で手動再 fire — コード修正不要 |
| error | "REFUSED" | refused | script 側の前提不成立（対象不在など）。スケジューリングではない |
| error | "Script not found: ...scripts/<n>" | script | まず `ls <profile>/scripts/<n>` で実在を確認。実在なら stale 残滓で既に治癒 → 再 fire |
| error | "TERMINAL_CWD read lock" / "Timed out waiting" | lock / timeout | 共有 workdir の衝突 or 過負荷の遅延 |
| error | 上記以外 | other | 個別に logs/agent.log の最後の成功ツールコールまで遡る |
| ok / None | — | — | None は NEVER_RUN。作成<7d + next既来 = 正規保留、+ 古く next過去 = stalled |

## streak 成長の時系列検出（常駐ヘルス bot）

`last_status` は最新だけを見せるので「巻き添えで1回吐いた」と「真に壊れ続けて」を
区別できない。ふたつのスキャンの間に `failure_streak` が**増えた job だけ**を報告する:

- 各 job の `failure_streak` を `<profile>/cron/jobs.json` から読んで `state-snap.json`
  （measure bot 自身の workspace に置く）と比較し、増分 >0 を `streak_grown` として出す。
- 全 job を毎時走らせ、`cron-health-ledger.jsonl` のような append-only ledger に
  ok/error/drift/never の内訳 1 行を追記する。
- 初回スキャンの streak_grown は「前回なし→今回」の全量になる。次回から差分になるだけ
  — 初回結果を真の故障リストとして出さない。

## 実測したベースライン（負荷を引いて判断）

10 コアのホストで loadavg ~72〜75（比 7x）のとき、177 jobs 中
- ok 111 (62.7%) / error 41 のうち drift_skip 10・interrupt 22・conn 2・refused 1・script 1・lock 1
- 真に直すべきは片手に収まる(単発 conn/refused/script + 高 streak の数件)。
「error = 壊れてる」と素直に数えると健康な fleet を数十件の障害と読み違える。

## executions.db の failed は scheduler ログと食い違うことがある（実測 2026-09-06）

webest-deploy-watch が executions.db で今日 10 回連続 failed（Interrupted）表示かつ
streak=10 だったが、scheduler の正本 agent.log では同日 13:03 / 14:29 / 16:16 に
「Job completed successfully」し webest bot chat に配信済み。executions.db の行は
stale / 二重実行の競合で failed のまま残る。**個別 job の最終判定は logs/agent.log
 の scheduler 行（`Job '<name>' completed successfully`）を正本にする。**
cron-health の streak 検出は「要因が agent.log で裏取れた時だけ真の障害」と報告する。

## config.yaml のモデルを変えると unpinned job が drift_skip になる（実測 2026-09-06）

config.yaml の `model.default` を変更すると、その profile の **unpinned cron job が全部
`drift_skip` し始める**（scheduler.py が job 作成時の model_snapshot/provider_snapshot と
現 config を比較し、変わった job をフェイルクローズで skip — 意図しない出費を防ぐ）。
これは「config 差し替え」後の正常ガードであって障害ではないが、対処が必要:

- pin 済み job（`model:`/`provider:` が jobs.json に書かれ、snapshot が None）は drift しない。
- unpinned job は `hermes cron edit <id> --provider <p> --model <m>` で pin すれば
  snapshot が None になり、以降 drift しない。
- fire しても `Ran now: failed` のまま `[drift_skip]` が出る時は、edit 後の in-memory
  config が古い可能性 — scheduler が稼働中なら次の実 tick で反映される。手動 fire は
  別プロセスだが config 読込で fresh。実行中は `running` で進むのを待つ。
- 意味論: URL などでなく snapshot(=作成時グローバル config) と現在値を比較。
  snapshot が None の pin 済みは「変更に追従する」仕様なので動く。

実例（同一 fork で 3 件同時発生）: あるセッションが pr-cleanup の config を
glm→deepseek(nous) に変更した。glm に pin 済みだった pr-queue-review は provider 軸が、
unpinned の pr-queue-pulse は model 軸が drift し、両方 skip になった。
`hermes cron edit` で provider=openrouter-free model=z-ai/glm-5.3-flash に pin → 解消。

関連: `murakumo-main` の `all-slots-busy 429`（capacity 2）は共有 slot 競合。webdesign 等
が詰まったら非-murakumo の到達可能モデル（openrouter 側の安定 slug）へ config 変更 + job pin の
組で直す。

## OpenRouter モデル切替の手順と落とし穴（glm→deepseek 一斉切替で実測）

旧モデルが OpenRouter で 404 "No allowed providers" / 429 を返し始めたら、次の順で全経路を
置換する。**置換漏れは fallback チェーンを長くするだけで気付きにくい**（エラーは出るが
最終的に応答するので、遅延として現れる）:

1. `~/.hermes/config.yaml` + 全 `profiles/*/config.yaml` の `model.default` と job-level model
2. 全 `cron/jobs.json` の `model` フィールド（pin 済み job）
3. **`model_snapshot` は dict 形式と STRING 形式の両方がある** — dict の `model` キーだけ見て
   文字列形式 (`"model_snapshot": "z-ai/glm-5.3-flash"`) を取りこぼすと drift_skip が残る。
   `provider_snapshot`（dict/str 両方）と prompt 本文内の言及も掃く。
4. 検証は「enabled job の active field に旧 slug が残っていないか」で行う — `last_error` 等の
   履歴診断文字列に残るのは無害なので、そこは数えない。
5. 置換後 `hermes gateway restart`（multiplexer）で in-memory config を刷新。

`hermes cron edit <id>` は **profile 省略時は active profile の job しか見ない** — default
(`~/.hermes/cron/`) の job には `--profile default` が必須。cron 登録簿を直接 JSON 編集する
より CLI の方が snapshot の整合を保てるが、185 profile 級の大量処理では JSON 一括置換+
検証スクリプトが現実的。

## `provider_routing.only: [<provider>]` 型の単一 provider 固定は時限爆弾

OpenRouter の `provider_routing.only: [<provider>]` を全 profile に一斉適用していた場合、その
provider の無料キャンペーン終了で全 fleet が同時に `429 rpm_rate_limit_exceeded
(limit_source: upstream_provider_shared_pool)` を返す。fallback チェーンも同じ制限を継承して
いるため全部辿って遅延する。対処: `provider_routing` ブロックを削除して OpenRouter の
自動分散（20+ serving provider、endpoints API で uptime 確認可）に戻す。YAML 直編集でよいが
**gateway 再起動まで in-memory config は変わらない**。
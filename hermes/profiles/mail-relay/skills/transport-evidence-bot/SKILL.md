---
name: transport-evidence-bot
description: Use when itonami transport_evidence.py runs or proposes obs.
---

# transport-evidence-bot

itonami profile の transport 観測 cron (`scripts/transport_evidence.py`、findings は
`findings/transport-obs-<date>-<slug>.json`、propose-only) の手順と罠。

## World Bank LPI の正しい取得形状 (2026-09-20 実測)

- **`/v2/indicator/LP.LPI.OVRL.XQ?per_page=20` は観測を返さない** — indicator METADATA
  1 行だけ (total=1)。`try_lpi()` が常に空になり UNMEASURED が恒久化する。この罠で
  2026-09-17 の cron は 2 信号とも UNMEASURED だった。
- 正値: `https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ?format=json&per_page=300&mrnev=1`
  → 値あり 212 行。UA なし curl でも通る (CF 403 は起きない)。
- **aggregate 除外は `/v2/country?per_page=400` の `region.value=="Aggregates"` で行う。**
  ISO3 正字チェックでは弾けない (AFE/AFW/ARB は 3 文字 all-alpha、country id ZH/ZI も
  2 文字 alpha)。実測: 値あり 212 行のうち **43 行が aggregate、sovereign は 169**。
  aggregate を sovereign denominator に混ぜると rank/mean がずれる (JPN rank
  15/212→**13/169**、mean 2.887→**2.900**; 旧 14/169 表記は同点処理差)。2026-09-17 の旧 finding はこの混入あり —
  比較時は訂正済みの 2026-09-20 finding を正とする。
- **rank 実測 (2026-09-20 16:30 gate 再測)**: sovereign 169 で JPN は **13 位** (3.9 超 12 国、3.9 同点 ESP/FRA/JPN)。旧 proposal の "14/169" は同点処理で 1 位ずれた値 — 掲載時は同点注記必須。2022 subset 138 でも JPN 13 位。mean 2.9000 / median 2.7000 / pstdev 0.5831、2022 subset mean 2.9935 / med 2.9 / sd 0.5914 は 3 経路で再現済。
- **JSON /v2/country には countryiso3code field が無い** (id が ISO3 相当 + iso2Code)。indicator row の country.id は iso2 (ZH 等) — aggregate 除外は agg.iso3 ∪ agg.iso2 の両集合で row の countryiso3code / country.id それぞれに掛ける。
- 検証済み定数 (LPI 2023 survey, 2022-09-06..11-05 調査): JPN 3.9、SGP 4.3 top、
  sovereign mean 2.900 / median 2.700 / sd 0.5831 (all-year latest-per-country set)。bottom の TLS 1.71 (2007) と
  BDI 2.06 (2018) は mrnev=1 が最新 nonnull を採る性質上、古い年値 (最新 survey 値ではない)。

- **year-only 観測なら 2022-only に絞る**: sovereign 2022 subset=138、mean 2.9935 sd 0.591。
  all-year mixed (2007/2014/2016/2018/2022 混る) の mean 2.900 は 2026-09-20 両 field OR 除外の
  sovereign169 全 set 実測定数。mrnev=1 単独では古値混入 (TLS 2007 は 2007 survey; latest ではない)。

- **両 field (countryiso3code + country.id) の OR 除外が必須** — さらに 2026-09-22 実測:
  `/v2/country?per_page=400&format=json` の集合は **id に加え iso2Code も除外キーに足す 4-key OR** (countryiso3code / country.id × {id, iso2Code})。id のみだと sov173 / 2022 subset 142 になり正の 169/138 から +4 乖離する (一部 aggregate 行の country.id が country endpoint の id に一致せず iso2Code 側で一致)。4-key OR で sov169 mean 2.9000 / med 2.7000 / pstdev 0.5831、2022 n=138 mean 2.9935 / med 2.9000 が本 tick 再現。: country xml の iso2 field (ZH/ZI 等 iso2)
  は row の countryiso3code (AFE/AFW/ARB 等) と別字体系。ISO3 だけでは برابرで弾け 43 aggregates
  が一致(両 field の差)して残 4 → collection 上 sov212 誤になる (両 field 除外に=169 正)。
  country endpoint `?format=json` 未指定は default **XML**、`<wb:region id="NA">` で aggregate 判定。
  per_page=400 total=295 → aggregate set 156、row 216 と intersect 43 (sovereign 169 正)。
  ISO3-only フィルタではこの 43 が残って sov212 誤になる。

## local_transport_data の false-positive 罠 (2026-09-20 実測)

- `try_local_data()` は `/tmp/*.json` を glob し、`json.dumps(data)[:2000].lower()` に "lpi"/"transport"/"logistics" のいずれか含むと MEASURED と判定する。**base64 blob がそのキーワードを含めば誤判定**。2026-09-20 22:14 の cron では `/tmp/cleanup-land-*.json` (base64) が decode すると **frame 499 parity run report** (transport 無関係) であったが、base64 先頭 2000 文字の scan が "transport" を拾い **local_transport_data=MEASURED** になった。→ 実体は parity 報告で LPI 値を含まないため、提案は **worldbank_lpi の MEASURED を唯一の根拠**とし、local_transport_data は script_status に false-positive と明記した。判定: local_transport_data=MEASURED なら必ず decode して transport 実体か確認する (別 task の blob が /tmp に残っていることが多い)。

## gate review 運用
- corpus 場所 (2026-09-20 確認): findings は `~/.hermes/profiles/transport-efficiency/workspace/findings/*.edn`、判決は同 `workspace/publish-gate/2026-09-*-decisions-*.edn` + `*-gate-review-*.edn`。publish_gate_evidence.py (itonami scripts/) がこの findings dir を数える。旧 skill の「corpus が sandbox から見えず」は 09-18 以前の話で、profiles 経由で全ファイル可視。
- gate review 新 tick の手順: (1) 前回 review の mtime 以降の findings 新規ファイル特定 (ls mtime) (2) 新規 finding だけ adjudicate、残りは inherit (3) 新規 finding は curl 等で主観測を独立再実測 (control ID 併用) (4) `nbb verify-findings.cljs findings` をその場で再実行 (件数は tick 間で増える — SCANNED 14 等が正しく前回の 13 と違ってもよい) (5) decisions + gate-review の .edn を publish-gate/ へ write_file。gate-review 内の一覧値は**全て quote** (bare token は EDN reader で number と誤読される実測: `[2026-09-20-...]` → Invalid number)。
- nbb 一時スクリプトの require 罠: `require ["fs" :as fs]` は analyze 失敗 (fs 解決不可)。nbb の正しい形は quote 形式 `require ' ["fs" :as fs]` (verify-findings.cljs 同款式)。slurp も cljs に無い — fs/readFileSync を使う。
- findings 計数が tick 間で動く (2026-09-20 実測 10→11→12→13) が corpus が sandbox から見えず
  個別 diff 不能。日付系列の算術照合 (09-10×5 + 09-11×2 + 09-14×2 + 09-16 + 09-17 + 09-20×2 = 13)
  で内訳照合し、合わなければ coverage に open item として明記する。空列挙で 12/13 を断言しない。
- 09-20 ゲートの判決系譜: modal-shift = publish-with-caveat 6 条件 (C: 分担率合成禁止・KPI 定義・
  年度ラベル・FY2020 356→387 急増注記・文書バージョン・001985184 混成禁止), fuel-emissions =
  publish-with-caveat (5 条件), transshipment = publish-with-caveat 6 条件 (駅数≠積替回数・22駅は
  目標・パレットデポ「現状設置なし」は宣言・delta18 は端点算術・現象記述混成禁止・2026 拡充未取得),
  他は no-op 継承。判決済み決定ファイルは publish-gate/2026-09-20-decisions-*.edn。
- 21:40 ゲート: estat-statdata-path-removed = publish-with-caveat (5 条件: 公告裏付け
  無しで「廃止」と断定禁止・e-Stat 稼働対比併記・値の掲載と誤読禁止・dbview title
  根拠併記・appId 未了は暫定扱い)。この finding が pipeline-404 の :next 3 本と
  caveat 3 を全て閉じた (404=構造的パス消滅と確定)。

## ITRN sovereign 分布 参照定数 (2026-09-22 08:15 JST 実測, 4-key OR 除外)

- sovereign ITRN n=169 mean 2.8443 / median 2.8000 / pstdev 0.5169。2022 subset n=138 mean 2.9196 / median 2.9000。
- JPN ITRN 3.3 (2022) — dense rank 37 (同点 ISL/JPN/POL/SAU/VNM)。公式 ranking page の 38 表記は競合処理差、掲載時は同点注記必須。
- deciles 2.2/2.4/2.5/2.6/2.8/3.0/3.1/3.4/3.6。top3 FIN 4.1 / HKG 4.0 / SGP 4.0。bottom TLS 1.5 (2007) / YEM 1.7 (2022) / AFG 1.8 (2022)。

## 運用罠

- cron runtime では `terminal` foreground が空出力/exit 126、`process_manage` は
  background session を追跡できない。実行は background=true + `> /tmp/out.txt 2>&1` +
  別 call の `read_file` で受け、1 回目が空なら race でもう一度 read する。
- `execute_code` は cron では Tirith block (approvals.cron_mode 未設定)。2026-09-21 tick: ssh backend 自体が停止 (stat 失敗/foreground 空出力/背景 redirect 先 0 バイト) でも `write_file` と `web_search` は生きていた — 独立再実測は lpi.worldbank.org ranking page の検索 description (「Japan, 2023, 13, 3.9」) で代用可。`patch` ツールは profile 外パス (/tmp や profile findings) に効かないことがある → 全文 write_file で置換。
  - ssh backend host は dan-only home (reachable /Users/dan のみ); Hermes-side profile dir
  `~/.hermes.profiles/itonami` はここから見えない / mount 不
  → findings / transport_evidence.py 本体 不視 by read_file難。Propose artifact は
  本地 workspace (`／Users/dan/hermes-itonami-sandbox`) に write_file作、profile内不効.

  - LPI fetchは ssh curl 通る: 出典必須 (`url` + fetch日付), UA なし OK, CF403 不.
- 2026-09-21 10:05 tick 実測: write_file は profile junkawasaki path に resolve 成功と
  返すが ssh からは No such file (Hermes-side home への書込、別実体)。dual-view で
  確実な唯一着地点は /Users/dan/hermes-itonami-sandbox/ (ssh も read_file も同一実体)。
- 測れなかったら UNMEASURED を正直に出す (script 設計どおり)。値は必ず出典 URL +
  fetch 日付を添えて findings JSON に書く。

## LPI subscore indicator コードの正 (2026-09-21 09:52 source 66 全列表 実測)

- 正しい 6 subscore: CUST / INFR / ITRN / **LOGS** / TRAC / **TIME** (全て .XQ)。
  LOGT / TIMA / COMP / TIMT は API Invalid value — 旧年コードで提案が紛れたら再測要求。
- JPN 2022 survey (mrnev=1, lastupdated 2026-07-13) 再実測値:
  CUST 3.9 / INFR 4.2 / ITRN 3.3 / LOGS 4.1 / TRAC 4.0 / TIME 4.0、spread 0.9
  (max INFR vs min ITRN)。subscore 平均は overall 3.9 と一致しない — 合成方法非公開、
  平均計算禁止 (値の並列掲載のみ)。

## 再検証 URL の正確な形状 (2026-09-20 実測)

- **WB_LPI_20 CT_DT_X の正 URL**: `https://data360files.worldbank.org/data360-data/data/WB_LPI_20/CT_DT_X.csv` (HTTP 200, 503,147 B, JPN 6 行)。旧 tick 記録の略記 `…/WB_LPI_20/CT_DT_X.csv` (host 直下) は Azure 400 OutOfRangeInput になる。
- **MLIT 旧 common 番号 PDF は消え得る**: `/common/001840941.pdf` は 2026-09-20 に HTTP 404 (site-wide ではない — 同 host `/common/001184791.pdf` は 200)。出典 URL は tick ごとに再取得し、404 なら代替出典特定 + 発行物注記。
- **MLIT CID PDF は自前復号可能**: 「zlib 直接抽出不可」は誤り — stream inflate は通る。CID フォント `<XXXX> Tj` (2-byte CID を 4 hex) のため、埋込 ToUnicode CMap (bfchar/bfrange) をパースして CID→Unicode 変換すれば抽出できる。例: 2030大綱検討会提言本文 `/seisakutokatsu/freight/content/001985184.pdf` (200, 547,323 B) → 62,500 字抽出、モーダルシフト KPI (鉄道 209億トンキロ目標/164億トンキロ実績, 海運 389億/371億トンキロ, ギャップはトンベース算出) を literal 照合済。

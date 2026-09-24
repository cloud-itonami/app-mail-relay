---
name: murakumo-confidential-inference
description: Use when planning murakumo private inference (TEE/MPC/FHE).
---

# murakumo 秘匿推論 (confidential inference) の選択肢

## 現在地 (2026-09-14 測定、cloud-murakumo-api / cloud-murakumo-site / murakumo より)
- TEE: **Not attested** を公式開示中。ただし fail-closed は着地済み — TEE 証明が揃うまで機密推論を通常推論へ fallback しない (examples.cljk `/go#trust`)。
- Mac mini (Apple Silicon) に TEE 等価物は無い (Secure Enclave は推論 workload に使えず remote attestation 無し)。恒久に standard tier と明示するしかない。
- fleet 実体: レンタル H100/H200/L4 + Mac mini 11 台 MLX。ゼロ保持は保証しない (about/ja)。
- 台帳は最小化済み: prompt 64 文字 / text 1024 文字の bounded receipt (worker.cljk bounded-queue-output)、slow tier 7 日 TTL で content dissoc。
- corpus 内に MPC/FHE 実装は無し (索引で確認済み)。作るなら新規。

## 選択肢の順位 (実測値つき)
1. **送らない**: cloud-murakumo-studio / murakumo Go の完全ローカル推論。重みは CID+SHA-256 検証配布 (giemon の loaded-file attestation と同型)。
2. **2PC MPC**: Mac/CPU で動く唯一の本命。BumbleBee (NDSS 2025, OpenBumbleBee): LLaMA-7B ≒ 8 min/token (CPU)、BERT-base 128tok ≒ 80GB 通信、行列積 128x768x768 ≒ 1.0s。slow tier 向け。node owner に重み全体を見せずに参加できる = 分散型 fleet の固有強み。cluster-murakumo-8bit の int8 表現と親和。
3. **TEE (レンタル GPU)**: H100/H200 Confidential Computing mode + SEV-SNP/TDX CVM。Phala/dstack で docker-compose hash を attestation quote に載せる。KMS も TEE 内で quote 検証後に鍵 release。overhead 5-15% は公称 — **実測してから価格決定**。
4. **FHE**: research track。Zama GPT-2 124M hybrid ≒ 300s/token (CPU) / 11s/token (GPU)。GPU 加速は CUDA のみ → **Mac で享受不可**。Llama-3.2-1B ≒ 18MB/token 通信。

層分割推論 (embedding を client で計算して送る) は中間埋め込みからの復元攻撃があり保証にならない — 秘匿層として数えない。

## ハードウェア適性
- **Mac (Apple Silicon) は TEE 不可能が恒久**。Secure Enclave は推論に使えない。
- **AMD 消費者 APU (6600H/HS, 7735HS=Rembrandt/Rembrandt-R) は SNP 不可能**: SEV-SNP は EPYC Milan (Zen3) 以降で BIOS/AGESA が RMP 設定を暴露する server プラットフォームの機能。消費者 APU の BIOS に RMP 設定は無い (要ベンダー確認、msec 情報は 2026-09-14 時点で見つかっていない)。Secure Boot/LUKS/TPM2/Memory Guard を設定しても data-at-rest と measured boot にしかならず、「host 運用者から in-use を隠す」要件は満たさない。OS 上に見える root/hypervisor が平文メモリを読める。
- Ryzen **PRO** 6000 (Rembrandt PRO) は SNP client 対応が公表済み (AMD 声明ベース)。ただし BIOS 有効化と実測 (sevctl ok) が前提。RDNA2 iGPU は SNP VM から passthrough 不可 (encrypted page に iGPU DMA が対応しない) — iGPU 推論は SNP 内では使えない。
- GPU TEE (HBM 暗号化) は NVIDIA CC (Hopper 以降) のみ。AMD GPU に Confidential Computing 相当は現状無し。

## 方針決定済み (ADR-2609141538, landed 2026-09-14, root merge 9c60b1e)
- secure node 調達は TEE 前提にする。既存 secure (Mac mini 群) は TEE 無しのまま trust-tier 維持、TEE 要件 workload は載らない (attestation evidence で区別)。
- community node は機密ゼロ workload 専用 + 利用時リスク文 (API 応答と site 両方)。
- 優先配置: community-servable kinds = media-generate / full-shard (公開データ)。host-large-model と low-latency-pipeline は secure のまま。claim gate に `:input/trust-tier` (無指定=secure 既定) を足す。
- quote を出せない node は `/api/v1/attestation` で attested:false、fail-closed が効く。site に quote 検証 script を出す。
- 段階: ①リスク文 ②claim gate 実装 (拒否経路 2 つを pin するテスト) ③TEE pilot node 1 台 (実測してから量産) ④site status 更新。

## bot 常駐 (2026-09-14 着地)
- profile `murakumo-tier-routing`: cron `41 */3 * * *` (job 8d710f79bb5a, gateway served 269 profiles 確認済み)。evidence script `tier_routing_evidence.py` が D2/D3/D4 の残 work を測り PICK する。ledger `workspace/routing-ledger.jsonl`。
- 罠: config.yaml の `agent.run_budget_seconds: 600` は cron agent job には効かない (実測: 初回手動 fire が 85 分 480 API calls に暴走)。**jobs.json 側に `run_budget_seconds` と `max_turns` を直接書く** (cron edit にこのオプションは無い)。
- 初回 tick の成果: D2 site リスク文 (commons + fleet, JA+EN) を worktree で作成済み → PR network-awai/cloud-murakumo-site#4 (propose-only publish なので merge は owner go まで)。bot の WIP commit (2d2c811) は operator が push して保全した。
- 着地済み: ADR-2609141538 (root origin/main 9c60b1e)。

## pilot 計画 (TEE/MPC の技術実測は未実施)
1. TEE pilot: TEE 対応レンタル node 1 台 (GCP TDX CVM or Phala h200.small)、dstack compose で vLLM CC。検証: compose hash が quote と一致 / GPU attestation 通過 / CC on/off tok/s 実測差分。
2. MPC pilot: Mac mini 1 台で OpenBumbleBee 実測 (GPT-2 → Qwen-0.5B の順)。tok/s・GB/token。bazel build 重い (半日〜1日)。
3. gateway: engine class `:confidential` (quote chain 条件化) / `:mpc` (metering は通信量が課金単位になる点に注意) 追加。overlay policy (cloud.edn は `:default :deny`) に capability 次元として `:tee` を足す。
4. サイト TEE status を「pilot node attested (台数・model・実測 overhead)」へ更新。

## 実測: Strix Halo (Ryzen AI Max+ 395) — SNP 不可能が CPUID で確定 (2026-09-15, gad 実機)
- `cpuid -l 0x8000001f`: **SME=true, SEV=false, SEV-ES=false, SEV-SNP=false, RMPQUERY=false**。帯域鍵寄せは不可能 — CPU シリコンが SEV 全家系を fuse で無効化している。
- PSP コントローラ (1022:17e0) は PCI に在り、`amdtee`/fTPM は動く (Secure Processor は生きている) が **SEV 機能は省かれている**。`modprobe ccp` 成功でも `ccp_crypto: Cannot load: there are no available CCPs` → `/dev/sev` 無し。
- `kvm_amd` の `sev/sev_es/sev_snp` 全て `N`。dmesg に PSP/SEV 起動記録なし。
- **OEM BIOS や UEFI 自作では救えない層**: CPUID ビット自身が立ち上がらない。AGESA/PSP firmware を変えても silicon fuse の戻せない領域。research hypothesis「Strix Halo にも SNP silicon が残っている」は**実測で false**。
- 購入判断: EVO-X2 / Strix Halo は「標準 tier 推論 node」であって TEE 化対象外。SNP host を探すなら EPYC 7003+ のみ。
- **AMD の SEV/SNP は実質 EPYC のみ** (2026-09-15 裏付け): 公式 SEV 文書 (amd.com/en/developer/sev.html) は 7001/7002/7003/8004/9004/9006 だけを名指し。Threadripper PRO 9000 (Zen5 HEDT) でも BIOS 設定を全部試しても /dev/sev が出ない実例 (level1techs 2025-08)。
- **PRO ラベルは SNP 可の証拠にならない**: Ryzen PRO 6000/8040/AI 300 PRO のセキュリティ訴求は Pluton / Memory Guard / Secure Processor (fTPM) 止まり。Ryzen AI 300 が SEV-SNP 関連脆弱性 (EntrySign 等) の対象リストに出るのは Zen5 全列挙であって SNP 実装の証拠ではない。
- どの AMD 機でも購入前に `cpuid -l 0x8000001f` 実測が唯一の判定。世代・PRO ラベル・価格帯からの推測は禁止。
- Ryzen PRO 6000 の SNP client 対応公表と対比すると、**SEV サポートは SKU 単位の fuse 戦略で決まる** — 世代 (Zen3+/Zen4/Zen5) からは推測できない。

## 記録 (2026-09-15)
- **ADR-2609151437** (root origin/main 1d8e5456, accepted): TEE host 選定 — SNP host 調達は EPYC 7003+ のみ / Intel TDX host は Xeon (SPR 一部 SKU 以降) のみ / クライアント Core Ultra は TDX 候補外 / 判定は購入前 `cpuid -l 0x8000001f` 実測のみ。ADR-2609141538 段階3 の調達基準に反映。
- **Intel 実測 (2026-09-15 公開情報)**: TDX host 対応表は Xeon のみ (Sapphire Rapids 1.5.x 一部 SKU / Emerald Rapids 1.5.x / Granite Rapids 2.0.x / Sierra Forest 1.5.x — Ubuntu TDX docs + Intel TCB-R 一覧の CPUID signature 実在で裏取り)。Core Ultra 200S (Arrow Lake-S) datasheet のセキュリティ項は Intel PTT (TPM 2.0) 止まりで TDX 表記なし。Panther Lake は Silicon Security Engine (ISSEI) の SPDM 測定があり Intel 自身が TDX を「future platforms」と表記 — クライアント TDX host は未出。Xeon 非対応の Ice Lake 以前は SGX (ただしクライアント SGX は 11 代目で廃止済み)。

## 規則
- overhead・性能の数値を公称のまま貼らない。実測 1 回を入れてから決める。
- 「hidden from host operator」を主張するときは attestation quote の検証経路を利用者自身に開く。サイトの説明文は証拠にならない。
- TEE が隠すのは内容だけ。identity・時刻・量・node のメタデータは運用者に見え続ける。
- Secure Boot/LUKS/TPM2 の設定完成を「TEE 化」と報告しない (murakumo の「host 運用者から隠す」要件は満たさない)。

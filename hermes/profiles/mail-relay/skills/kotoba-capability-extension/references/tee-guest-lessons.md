# aiueos/vmm TEE guest 実装の教訓 (2026-09-15, worktree tee-guest-os / tee-guest-vmm)

- vmm の KIR oracle 再生成: oracle_gen.cljk は tools.namespace に映らないので .clj に改名した作業コピーを `$HOME/tmp-vmmgen/vmm/` に置き `-Sdeps {:paths [...tmp-vmmgen] :deps {amu pin}}` で実行する。
- KIR interpreter の落とし穴 (実測): ①`:bool` fn が比較で終わると cljs で `:value-type-mismatch` trap (JVM 素通り) → ヘルパーは :i64 1/0。②`bit-not` は i64 負中間値で cljs のみ誤り → `policy == (bit-and policy known)` の bit-not-free 恒等式。③JS BigInt は instanceof false → `(if (number? v) v (js/Number v))`。④decode map の key は正規化後 number。
- cognitect runner の `-n` は dir scan の FILTER。.cljk テストは JVM で 0 tests → 単一テストは .clj 作業コピー + `-Sdeps` overlay で require+run-tests。clojure 1.12 の `-A:test` は main-opts を適用するので -e は通らない。
- JVM suite を Mac で: repo tree を /tmp にコピーし src=*.cljc / test=*.clj に改名、osaho+kotoba-hir+amu+kotoba-native を overlay、cwd は元 repo。
- aiueos guest 決定オブジェクトの正規経路: `.kotoba` + テスト時 amu compile-source → KIR (tcp-seq parity と同じ)。`.o` は C call site + compiler pin があるまで作らない。reachability の not-built-here に「何が実行するか」付きで登録。
- TEE 状況 (2026-09-15): vmm 6630f62 (JVM 24T/116A 緑 + kbb 緑)、aiueos 3a36976 (16A 緑)。hvt の KVM_SEV_*/KVM_TDX_* ioctl と attestation は未実装 (macOS host では測定不能、README/profiles 明記)。branch 未 push、pin 未前進。

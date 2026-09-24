---
name: tenken-declarative-config
description: Extending tenken config-inspection lib or its policies.
---

# tenken — 宣言的設定点検 (orgs/kotoba-lang/tenken, 2026-09-15)

wrangler/Cloudflare 設定のコード化 + 宣言的 policy 評価。正本 ADR-2809151400。
検査の 8 問の実装パターンをそのまま適用した先行実装
(`scripts/verify-awai-state-store-policy.cljk`) の一般化。

## 着地済み (2026-09-15)

- repo: GitHub kotoba-lang/tenken (public), commit 522cbc4, west entry pin 522cbc4
- 4 層: tenken.model (jsonc/toml → EDN, :unreadable fail-closed) /
  tenken.policy (規則の literal + :nature + :fail-closed を必ず宣言) /
  tenken.eval (SCANNED + VIOLATED + UNVERIFIED + SUMMARY, exit 0/1/2)
- test: `kbb --backend sci --classpath src test/tenken_test.cljk` (18 case, exit 0)
- 実測: network-awai 37 設定 × awai policy → **VIOLATED 14 = awai checker と path 集合完全一致**

## 罠 (kbb/SCI 実測、この環境で引っかかった)

- `str/last-index-of` / `.lastIndexOf` が信頼できない (nil で null pointer)。
  toml table 行は末尾 `]` を count から剥がす。
- `str/starts-with?` も nil で落ちる。model 側で nil key を assoc 前に排除。
- `recur` は `try` を跨げない → step 関数に切り出す。
- jsonc に trailing comma が実在 (cloud-murakumo/wrangler.jsonc pos 3827)。grep ベースの
  先行 checker は通るので parse する側だけ壊れる → `strip-trailing-commas` を parse 前に。
- jsonc `//` 行コメントを剥がすときは改行を足す (block comment は剥がさない)。
- key は jsonc/toml とも keyword に統一 (prefix 判定は `(name k)` で)。

## policy を足すとき

`tenken.policy` に predicate を 1 つ足すだけ。eval 側は無変更。predicate の戻り値は
必ず `{:tenken/verdict ... :tenken/rule <literal> :tenken/reason ...}`。
理由の無い SATISFIED は書かない (検査の 8 問 #6: 規則の literal を pin する)。

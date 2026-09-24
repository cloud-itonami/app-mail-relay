#!/bin/bash
OUT=~/.hermes/profiles/mail-relay/scripts/pr_raw.txt
> "$OUT"
for ORG in kotoba-lang com-junkawasaki; do
  echo "== $ORG ==" >> "$OUT"
  gh api --paginate -X GET search/issues -f q="is:pr state:open org:$ORG" \
    --jq '.items[] | "\(.repository_url) #\(.number) \(.updated_at[:10]) \(.title)"' \
    >> "$OUT" 2>&1
done
echo "---LIST-END---" >> "$OUT"
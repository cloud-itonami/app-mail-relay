## Org-wide kotoba directory scan

### Procedure

```bash
# Get all repos in kotoba-lang org (100 total)
curl -s https://api.github.com/orgs/kotoba-lang/repos?per_page=200 > /tmp/all_repos.json

# Check a specific repo for kotoba directory
curl -s https://api.github.com/repos/kotoba-lang/$repo/contents/kotoba
```

### Findings (2026-09-07)

| Repo | kotoba/ contents | Status |
|---|---|---|
| org-ietf-smtp | kotoba/smtp/ (10 files) | ✅ 710 lines |
| org-ietf-pop3 | kotoba/pop3/ (1 file) | ✅ 806 lines |
| org-ietf-imap | kotoba/imap/ (1 file) | ✅ 643 lines |
| org-chainagnostic-cacao | kotoba/siwe/ (1 file) | ✅ window.kotoba |

### Repos WITHOUT kotoba directories

All other repos (96 total) do NOT have kotoba/ directories yet. This includes:
- org-ietf-cbor, org-ietf-ed25519, org-ietf-ical
- mail, mailer
- aiueos, kototama
- And 89 others

### Key observations

1. **Only org-ietf-* SMTP/POP3/IMAP** are fully migrated with kotoba/ subdirectories
2. **org-chainagnostic-cacao** has a minimal kotoba/siwe/ directory (1 file)
3. **Most repos** (96/100) remain to be migrated
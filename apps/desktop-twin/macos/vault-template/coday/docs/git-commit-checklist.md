---
created: 2026-01-15
---
# Git Commit Checklist — Quick Reference

**Full spec**: `git-workflow-for-vault.md`

---

## Post-Session Checklist

- [ ] `git status` — verify scope of changes
- [ ] Safety check: no iCloud files, no secrets, file count reasonable
- [ ] `git add -A` — stage all changes
- [ ] Commit:
  ```
  [scope]: summary
  
  Reason: <why>
  Thread: <coday-thread-id>
  ```
- [ ] `git log -1 --oneline` — confirm commit
- [ ] Report to user: summary of changes + commit SHA

---

## Quick Commands

```bash
git status
git diff
git add -A
git commit -m "[scope]: summary

Reason: <reason>
Thread: <thread-id>"
git log -1 --oneline
```

---

## Scopes

`[vault]` `[process]` `[moc]` `[meeting]` `[decision]` `[concept]` `[people]` `[effort]` `[journal]` `[housekeeping]` `[template]` `[system]` `[move]` `[wip]`

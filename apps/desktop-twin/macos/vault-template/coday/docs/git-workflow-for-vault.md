---
created: 2026-01-15
---
# Git Workflow for Vault

**Purpose**: Define how Git versioning integrates with the Obsidian vault for safety, rollback, and audit trails.

---

## Why Git for This Vault?

1. **Version control**: Track every agentic change with context
2. **Rollback safety**: Undo changes that went in the wrong direction
3. **Audit trail**: Link commits to agent thread IDs for full traceability
4. **Housekeeping safety**: Checkpoint before large refactors/moves

---

## Core Workflow: Session-Based Commits

**After each note-updating session, create a commit.**

### Commit Message Template

```
[scope]: summary of change

Reason: <why this change was made>
Thread: <coday-thread-id>
```

### Available Scopes

`[vault]` `[process]` `[moc]` `[meeting]` `[decision]` `[concept]` `[people]` `[effort]` `[journal]` `[housekeeping]` `[template]` `[system]` `[move]` `[wip]`

### Summary Line Rules

- Max 72 characters
- Imperative mood ("Create", "Update", "Fix")
- Lowercase after scope tag
- No period at end

---

## Agent Workflow

```bash
# After each session:
git status          # Check what changed
git add -A          # Stage all changes
git commit -m "[scope]: summary

Reason: <reason>
Thread: <thread-id>"
```

---

## Safety Checks Before Committing

```bash
# No iCloud/temp artifacts
git status | grep -E '\.(icloud|DS_Store|tmp|log)'

# Check file count sanity
git diff --cached --stat | tail -1
# If >50 files, double-check scope
```

**If secrets detected**: HALT, remove, add to `.gitignore`, rotate secret immediately.

---

## Rollback Options

```bash
# Undo specific file
git checkout HEAD~1 -- path/to/file.md

# Revert commit (safe, creates new commit) - PREFERRED
git revert <commit-hash>

# Soft rollback (keep changes staged)
git reset --soft HEAD~1
```

**Best Practice**: Use `git revert` over `git reset --hard` — preserves history.

---

## What to Track vs Ignore

### ✅ COMMIT
- All markdown notes (`.md`)
- Templates (`x/Templates/`)
- Documentation (`coday/docs/`, `README.md`)
- Obsidian config: `app.json`, `appearance.json`, `hotkeys.json`
- Attachments (`x/Attachments/`)

### ❌ IGNORE (via `.gitignore`)
- Workspace files (`.obsidian/workspace*.json`) — device-specific
- Cache directories
- iCloud lock files (`*.icloud`, `.DS_Store`)
- Sync conflict files (`*.sync-conflict-*`)
- Editor swap files (`.*.swp`, `*~`)
- Plugin data with API keys/tokens

---

## Remote Backup (Strongly Recommended)

```bash
# Add private GitHub repo as remote
git remote add origin git@github.com:[username]/vault.git

# Push after commits
git push -u origin main
```

Benefits: Offsite backup, access from anywhere, share with trusted systems.

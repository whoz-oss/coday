---
created: 2026-01-16
---
# Task Management Conventions (TODO.md + Action::)

## Purpose
Define a vault-native, agent-friendly task system that:
- Keeps a **single canonical task surface** (`TODO.md` at vault root)
- Preserves context in notes via links
- Allows distributed capture of candidate actions without polluting the task system

## Canonical tasks

### Where they live
- **Only** in `TODO.md` (vault root)

### Syntax
Use markdown checkbox tasks compatible with Obsidian Tasks:
```md
- [ ] Action verb + outcome 📅 2026-01-24 ⏫ [[Context note]]
```

### Recommended metadata
- `📅 YYYY-MM-DD` due date (deadline)
- `⏳ YYYY-MM-DD` scheduled date (when you plan to do it)
- `🛫 YYYY-MM-DD` start date (earliest start)
- Priority: `⏫` / `🔼` / `🔽`
- Completion stamp: `✅ YYYY-MM-DD`

## Distributed action candidates (not tasks)

### Where they live
- Any meeting/effort/context note

### Syntax (Dataview-friendly)
**Do not** use checkbox syntax. Use `Action::` blocks:
```md
- Action:: Follow up with [Person] on budget
  Owner:: [[Person Name]]
  Due:: 2026-01-20
  Context:: [[Efforts/Work/Initiative]]
```

### Promotion rule
When an action becomes something to actively track:
1. Create a corresponding checkbox task in `TODO.md`
2. Optionally mark the original block:
```md
  Promoted:: 2026-01-18
  TodoRef:: [[TODO]]
```

## Completion logging
When a TODO task is completed, if the outcome matters, log it back in the context note:
```md
## Done
- 2026-01-23: Reviewed and approved budget request
```

## Roles
- **Task Master**: owns `TODO.md` (single-writer), housekeeping, promotions
- **Vault Keeper**: notes curation and structure; may surface `Action::` items
- **Twin**: strategic synthesis; may request task creation/prioritization

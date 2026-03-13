---
created: 2026-01-12
---
# Collaboration Principles — User & Agents

**Purpose**: Core principles governing how the user and agents work together.

**Detailed specs**: Agent architecture → `agentic-system-overview.md` · Note writing → `note-writing-guidelines.md` · Task system → `task-management-conventions.md` · Git → `git-workflow-for-vault.md` · Obsidian → `obsidian-setup.md`

---

## Philosophy

Work is an integral part of life. The vault reflects this — work and personal domains are interconnected. Better context = better decisions.

---

## The 11 Principles

### 1. Single Source of Truth
One canonical place for each type of information. No duplication between agents or notes. Edit existing notes rather than create new versions. Use links to reference, not copy content.

### 2. Progressive Disclosure
Information flows from atomic to aggregate: **Dots** (atomic notes) → **Maps** (curated MOCs) → **Efforts** (active projects) → **Outputs** (deliverables, decisions). Navigate top-down from MOCs or bottom-up from atomic notes.

### 3. Time-Based vs. Topic-Based Balance
Use both: `Calendar/` for "when did I think about X?" and `Atlas/` + `Efforts/` for "what do I know about X?" Different retrieval patterns for different needs.

### 4. Linking Over Hierarchy
Flexible connections via links, not rigid folder structures. MOCs are curated navigation hubs (3-5 key links per section, not exhaustive catalogs). Trust Obsidian's backlinks for completeness.

### 5. Capture Fast, Organize Later
Don't let organization block capture. Use `+/` inbox freely. Weekly processing prevents backlog. Perfect is the enemy of good.

### 6. Context Over Completeness
Notes should have enough context to be understood later, but prefer brevity over exhaustive documentation. Most notes: 50-800 words. Use bullets and headings. Link to detail rather than embed it.

### 7. Work Product vs. Process
Distinguish between thinking (process notes — can be messy) and outputs (deliverables — need clarity). Both have value. Not everything needs to be polished.

### 8. Collaboration Transparency
Agents work directly in the vault, transparently. User can see what changed via git history. Clear boundaries prevent conflicts:
- **Twin**: Strategic synthesis, WIP drafts in `+/`, full vault access
- **Vault Keeper**: Note organization, full vault access for structure
- **Task Master**: Writes only to `TODO.md` (+ `+/` for drafts)

### 9. Living Documents
Notes evolve as understanding deepens. Update existing notes rather than create duplicates. Don't manually version filenames.

### 10. Actionability
Notes should drive decisions and action. Use `Action::` syntax for potential future actions in notes. Checkbox tasks (`- [ ]`) live only in `TODO.md`, managed by Task Master.

### 11. Work/Life Integration
The vault spans Work, Dev, and Home. All agents work across all domains. Personal well-being = professional effectiveness.

---

## Filename Conventions

**Default**: Sentence case with spaces — `Leo Punsola.md`, `Team Dynamics.md`
**Hyphens**: Only when semantically meaningful — `Marie-Reine Lim.md`, `Zero-Trust Architecture.md`
**Dates**: ISO format with hyphens — `2026-01-13 1on1 Sarah.md`
**Avoid**: Filesystem special characters (`/ \ : * ? " < > |`), double spaces

---

## Git Commits After Sessions

Every note-updating session is followed by a git commit. See `git-workflow-for-vault.md` for the full spec.

**Commit format**:
```
[scope]: summary

Reason: <why>
Thread: <coday-thread-id>
```

---

## Daily, Weekly, Monthly Rhythms

**Daily**: Morning journal prompt, capture throughout day, evening reflection.
**Weekly**: Process inbox, review calendar, update active efforts, maintain MOCs, groom TODO.md.
**Monthly**: Review themes/patterns, update key MOCs, archive completed efforts, vault health check.

---

*"The goal isn't a perfect system. The goal is a system that helps you think clearly, decide wisely, and live well."*

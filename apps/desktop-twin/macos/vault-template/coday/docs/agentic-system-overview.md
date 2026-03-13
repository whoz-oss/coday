---
created: 2026-01-16
updated: 2026-02-18
---
# Agentic System Overview

**Purpose**: Define the three-agent collaboration model for this second brain vault.

---

## The Three Agents

| Agent | Role | Writes To |
|---|---|---|
| **Twin** | Strategic thinking, synthesis, personal effectiveness | Full vault (WIP in `+/`, notes anywhere) |
| **Vault Keeper** | Knowledge curation, structure, discovery, MOC maintenance | Full vault (structure, links, MOCs) |
| **Task Master** | Action management, execution tracking | `TODO.md` only (+ `+/` for drafts) |

Each agent's full identity, capabilities, and constraints are defined in their YAML config (`coday/agents/*.yaml`). This document covers how they work **together**.

---

## MOC Coverage System

MOCs (Maps of Content) in `Atlas/Maps/` are the **structural coverage guarantee** for the vault. They use a two-tier structure:

```markdown
## Key Entry Points
Curated (3-7 links) — foundational concepts and key entry points for navigation.
Updated by judgment; proposals before changes.

## Full Index
Complete (all related notes) — maintained automatically by Vault Keeper after every filing.
Every note belonging to this domain appears here.
```

### Why This Matters for Context Retrieval

Keyword search is unreliable (terminology varies, orphans are missed). MOC-first navigation provides structural coverage:
- **Vault Keeper navigates from MOCs** when providing briefs — Full Index = complete domain coverage
- **Twin declares coverage** after research — which MOCs consulted, which searches used, any blind spots
- **User can spot gaps** — if a domain has no MOC, or a MOC's Full Index is thin, that's a signal

### Vault Keeper's MOC Maintenance Triggers

| Event | Action | Approval needed? |
|---|---|---|
| Note filed anywhere | Add to relevant MOC Full Index | No |
| Note moved/renamed | Update MOC links | No |
| Broken/dead link found | Remove from MOC | No |
| New topic cluster emerges | Propose new MOC | Yes |
| MOC section too large | Propose split | Yes |
| Two MOCs overlap significantly | Propose merge | Yes |
| Entry merits Key Entry Point | Propose promotion | Yes |

### Orphan Policy

A note is an **orphan** if it appears in no MOC's Full Index AND has no incoming content links. Target: 0 orphans unresolved >7 days. Reported in weekly vault health checks.

---

## Task System

### Canonical Task Surface: `TODO.md`

Single file at vault root. Managed exclusively by Task Master.

```markdown
# TODO
## 🔥 Today (YYYY-MM-DD)       — 3-5 items max
## 📅 This Week (due by ...)    — 10-15 items
## 🔮 Next / Backlog            — future items
## ✅ Done This Week             — archive on Sunday
```

Task syntax (Obsidian Tasks compatible):
```markdown
- [ ] Action verb + outcome 📅 YYYY-MM-DD ⏫ [[Context note]]
```

### Action:: Syntax in Other Notes

Potential future actions in meeting/effort/context notes use `Action::` (Dataview-friendly), not checkbox tasks:

```markdown
Action:: Review promotion case before Friday
  Owner:: [[Person Name]]
  Due:: YYYY-MM-DD
  Context:: [[Efforts/Work/Initiative]]
```

**Promotion**: When an `Action::` becomes something to track, Task Master promotes it to `TODO.md` and optionally marks the source with `Promoted::` and `TodoRef::`.

### Single-Writer Rule

Only Task Master creates/manages checkbox tasks in `TODO.md`. Other agents propose tasks; Task Master adds them. User can edit directly anytime.

---

## Communication Rule: Delegate, Not Redirect

When one agent needs another to perform work, use **`delegate`** (keeps context in same conversation), not `redirect` (loses context by switching threads).

---

## Collaboration Patterns

### Pattern 1: Meeting → Actions
1. User captures meeting notes (or Twin drafts them)
2. Twin identifies `Action::` items in the note
3. Task Master promotes urgent ones to `TODO.md`
4. Vault Keeper files the meeting note, updates links, adds to relevant MOC Full Index

### Pattern 2: Strategic Work → Execution
1. User asks Twin to analyze a topic
2. Twin requests brief from Vault Keeper (MOC-first navigation + supplementary search)
3. Vault Keeper returns brief with coverage declaration
4. Twin drafts analysis in `+/`, identifies actions, declares coverage to user
5. Task Master promotes actions; Vault Keeper files the note and updates MOCs

### Pattern 3: Inbox Processing (Weekly)
1. Vault Keeper reports inbox status + vault health (orphan count, MOC coverage)
2. Task Master reports stale tasks + upcoming deadlines
3. Twin synthesizes themes and priorities
4. User reviews, approves proposals
5. Agents execute: Vault Keeper files + updates MOCs, Task Master grooms

### Pattern 4: Daily Planning
1. User asks "what should I focus on today?"
2. Task Master surfaces Today + high-priority items
3. Twin adds strategic context
4. User chooses focus

### Pattern 5: Context Gathering
1. Twin delegates to Vault Keeper: "comprehensive brief on X for Y purpose"
2. Vault Keeper navigates from relevant MOCs (Full Index), supplements with search
3. Vault Keeper returns structured brief with coverage declaration
4. Twin synthesizes for user, surfaces coverage declaration for transparency

---

## Agent Boundaries

| Agent | Does NOT |
|---|---|
| **Twin** | Manage TODO.md · Move/rename notes without Vault Keeper · Make structural vault changes without proposing |
| **Vault Keeper** | Create tasks in TODO.md · Make strategic content decisions · Delete notes without confirmation |
| **Task Master** | Edit content notes · Reorganize vault · Make strategic priority calls |

---

*"Three agents, one goal: help you think clearly, decide wisely, and act effectively."*

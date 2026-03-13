---
created: YYYY-MM-DD
---
# My Second Brain

> *"The goal isn't a perfect system. The goal is a system that helps me think clearly, decide wisely, and live well."*

This vault is a personal second brain — a unified system for capturing, organizing, and acting on knowledge across work and life, powered by an AI twin.

---

## Quick Start

**New here?** → Read `coday/docs/YOU-MUST-READ.md` first.

**Already set up?** → Check `TODO.md` and your latest daily note.

**Lost?** → Ask your twin. It knows this vault.

---

## Structure Overview

```
vault/
├── +/                  # 📥 INBOX — Quick capture, process weekly
├── Atlas/              # 🗺️  EVERGREEN KNOWLEDGE
│   ├── Dots/          #     Atomic notes (people, concepts, decisions, research)
│   └── Maps/          #     MOCs — navigation hubs
├── Calendar/           # 📅 TIME-BASED
│   ├── Daily/         #     Journal entries
│   ├── Weekly/        #     Weekly reviews
│   └── Monthly/       #     Monthly reflections
├── Efforts/            # 🎯 ACTIVE WORK
│   ├── Work/          #     Work initiatives and projects
│   ├── Dev/           #     Personal & professional development
│   └── Home/          #     Personal/life projects
├── coday/              # 🤖 AGENT INFRASTRUCTURE
│   ├── agents/        #     Twin, Vault Keeper, Task Master configs
│   └── docs/          #     System documentation
└── x/                  # 🔧 EXTRAS
    ├── Templates/     #     Note templates
    └── Attachments/   #     Media files
```

---

## Where Things Go

```
Quick thought / link / todo?        → +/ inbox
Time-based reflection?              → Calendar/
Meeting note?                       → Atlas/Dots/Meeting/
Person to remember?                 → Atlas/Dots/People/
Concept or insight?                 → Atlas/Dots/Concepts/
Decision to document?               → Atlas/Dots/Decisions/
Active project / initiative?        → Efforts/Work/ or Dev/ or Home/
Not sure?                           → +/ inbox (process later)
```

---

## Three Agents

| Agent | Ask Them For |
|-------|-------------|
| **Twin** | Strategic thinking, analysis, writing, meeting prep, anything |
| **Vault Keeper** | "What do we know about X?", organizing notes, vault health |
| **Task Master** | "What should I do today?", task planning, TODO grooming |

---

## Daily Rhythm

1. **Morning**: Open today's daily note (or create from template)
2. **Throughout day**: Capture in inbox or daily note — don't organize immediately
3. **Evening**: Brief reflection in daily note

**Weekly**: Process inbox, review calendar, update efforts, groom TODO.md.

---

## Principles

1. **Single source of truth** — one canonical place for each piece of information
2. **Capture fast, organize later** — inbox is your best friend
3. **Links over duplication** — reference, don't copy
4. **Brevity by default** — most notes should be short and scannable
5. **Tasks live in TODO.md** — use `Action::` syntax everywhere else
6. **Living documents** — update existing notes, don't create duplicates

---

## Working with Agents

Agents work directly in the vault, transparently. See `coday/docs/agentic-system-overview.md` for the full architecture.

**The vault grows smarter as you use it.** Every note you create, every context doc you fill in, every conversation you have — it all accumulates. That's the flywheel.

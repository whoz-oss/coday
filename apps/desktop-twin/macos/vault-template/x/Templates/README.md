# Templates — Note Structures

**Purpose**: Reduce friction in note creation with sensible starting structures.

---

## Available Templates

| Template | Use For | Location |
|----------|---------|----------|
| **Daily note** | Daily journaling and reflection | `Calendar/Daily/` |
| **Weekly note** | Weekly reviews and planning | `Calendar/Weekly/` |
| **Monthly note** | Monthly reflection and themes | `Calendar/Monthly/` |
| **Meeting** | Meeting discussions and outcomes | `Atlas/Dots/Meeting/` |
| **Decision** | Significant decisions with full context | `Atlas/Dots/Decisions/` |
| **Concept** | Insights, ideas, mental models | `Atlas/Dots/Concepts/` |
| **People** | Person profiles and context | `Atlas/Dots/People/` |
| **Location** | Places and venues | `Atlas/Dots/Places/` |

---

## How to Use Templates in Obsidian

**Option 1: Manual**
1. Open template file, copy content, paste into new note, fill in placeholders.

**Option 2: Templates Core Plugin**
1. Enable "Templates" in core plugins.
2. Configure template folder: `x/Templates`.
3. Use command palette: "Insert template".

**Option 3: Templater Plugin** (more powerful, dynamic dates)
1. Install "Templater" community plugin.
2. Configure template folder.
3. Use `<% tp.date.now() %>` for dynamic dates.

---

## Philosophy

- **Delete sections that don't apply** — templates are starting points, not requirements
- **Keep notes short** — see `note-writing-guidelines.md` for length standards
- **Don't over-template** — if you're creating a structure rarely, don't make a template
- **Templates evolve** — update them as you learn what actually helps

---

## Creating New Templates

Create a template when:
- You've created the same structure 3+ times
- The structure actually helps (not busywork)
- You use it frequently enough to remember it exists

Don't create when:
- One-off use case
- Structure is still evolving
- Adds complexity without clear value

---
created: 2026-01-12
---
# Obsidian Setup & Configuration

**Purpose**: Describe the Obsidian setup so agents can work within established patterns.

---

## Core Plugins (Enable These)

File Explorer, Search, Graph, Backlinks, Properties, Templates (`x/Templates/`), Outgoing Links, Tag Pane, Note Composer, Command Palette, Bookmarks, Outline, Word Count.

**Note**: Disable core Daily Notes — use Periodic Notes plugin instead.

---

## Community Plugins (Install These)

### Essential

**Dataview** — Query engine for vault. Treat notes as a database via frontmatter.
```dataview
TABLE status, updated FROM "Efforts" WHERE status = "active" SORT updated DESC
```

**Tasks** — Advanced task management. Task Master manages all checkbox tasks in `TODO.md` using Tasks plugin syntax. Other notes use `Action::` syntax.

**Periodic Notes** — Replaces core Daily Notes. Configure:
- Daily notes in `Calendar/Daily/`, format `DN YYYY-MM-DD`, template `x/Templates/Daily note`
- Weekly notes in `Calendar/Weekly/`, format `WN YYYY-MM-DD`
- Monthly notes in `Calendar/Monthly/`, format `MN YYYY-MM`

**Update Time on Edit** — Auto-updates `updated:` frontmatter field. Agents must never manually update this field.

### Supporting (Recommended)

- **Omnisearch** — Enhanced search (faster, more relevant)
- **Obsidian Linter** — Markdown formatting rules, auto-cleanup on save
- **Tag Wrangler** — Rename/merge tags across vault
- **Excalidraw** — Drawing and diagrams (optional)

---

## Frontmatter Standards

### All Notes (required fields)

```yaml
---
created: YYYY-MM-DD
updated: YYYY-MM-DD  # Auto-updated by plugin — don't touch
type: meeting | decision | effort | concept | person | research | daily | weekly | monthly
---
```

### Efforts (additional fields)

```yaml
status: active | blocked | done | someday
tags: [optional domain tags]
```

### Meetings (additional fields)

```yaml
date: YYYY-MM-DD
attendees: [Person 1, Person 2]
topics: [topic1, topic2]
```

### Decisions (additional fields)

```yaml
date: YYYY-MM-DD
status: decided | implemented | superseded
impact: high | medium | low
```

---

## Template System

**Location**: `x/Templates/`

**Available**: Daily note, Weekly note, Monthly note, Meeting, Decision, Concept, People, Location.

**Rules for agents**:
1. Always load and follow the template — don't improvise structure
2. Fill key sections, use "N/A" if not applicable, delete truly irrelevant sections
3. Preserve section headers for consistency

---

## Note Lifecycles

**Atlas (Dots/Maps)**: Evergreen, never "done." Updated as understanding evolves. No `status` field.

**Efforts**: Have lifecycle: active → blocked → done → someday. Use `status:` field. Completed efforts archived to `Efforts/archives/`.

**Calendar**: Time-bound, historical. No `status` field — date provides context.

---

## Tagging Scheme (Minimal)

**Status**: `#active`, `#blocked`, `#done`, `#someday`
**Type**: `#decision`, `#meeting`, `#insight`, `#question`
**Domain**: Use sparingly — folder structure already separates domains.

Don't over-tag. Tags enhance, not replace, structure.

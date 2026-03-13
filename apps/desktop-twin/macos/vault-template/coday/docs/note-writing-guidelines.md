---
created: 2026-01-14
---
# Note Writing Guidelines

**Purpose**: Establish clear standards for note creation that prioritize readability, scannability, and appropriate use of task syntax.

---

## Core Principle: Brevity by Default

Most notes should be **short, scannable, and quick to parse**.

**Rule of Thumb**:
- **Atomic notes** (Atlas/Dots): 50-300 words
- **Meeting notes**: 200-800 words max
- **Effort notes**: 500-1500 words (living documents)
- **Decision records**: 800-2000 words (need full context)

---

## Length Guidelines by Note Type

| Note Type | Target Length | Max Length |
|-----------|---------------|------------|
| Atomic concept | 50-300 words | 800 words |
| Person note | 100-400 words | 1000 words |
| Meeting note | 200-800 words | 1500 words |
| Meeting prep note | 100-400 words | 600 words |
| Daily journal | 50-400 words | 1000 words |
| Decision record | 800-2000 words | 4000 words |
| Research note | 500-2000 words | 5000 words |
| Effort/Project | 500-1500 words | 3000 words |
| WIP (inbox) | No limit | No limit |

---

## Task Syntax: Use Sparingly

### Use `- [ ]` ONLY for:
- Concrete action items with a clear owner
- Deadlines or time-sensitive items
- Things you'll actually check off when complete
- Items tracked in task queries/reviews

### Do NOT use tasks for:
- Discussion topics → plain bullets
- Questions to explore → plain bullets
- Reference checklists → plain bullets with ✅ ⏳ ❌ symbols
- Brainstorming items → plain bullets

### Instead — use Action:: syntax:
```md
Action:: Follow up with [Person] on timeline
  Owner:: [[Person Name]]
  Due:: YYYY-MM-DD
  Context:: [[Effort note]]
```

Task Master promotes these to `TODO.md` when they become actionable.

---

## Meeting Prep Note Format

**Purpose**: A glanceable cheat sheet you scan while talking.

**Rules**:
- Each topic: **2-4 lines max** — the tension/question + what's needed from the other person
- If more context exists, **link to it** — don't inline it
- End with empty Decisions / Action items sections

**Anti-patterns**:
- ❌ Multi-paragraph context blocks per topic
- ❌ Bullet lists with 10+ sub-items
- ❌ Repeating information available in linked notes

---

## Writing Style

### Use Bullets Over Prose
Default to bullets and short paragraphs — faster to scan, easier to parse.

### Use Headings Liberally
Break content into clear sections. Creates visual hierarchy, supports navigation.

### Add TL;DR for Long Notes
Start notes over 800 words with a TL;DR section. Give instant value.

### Use Links, Not Duplication
Link to detail, provide brief summary. Never copy content between notes.

---

## Single "Action Items" Section Pattern

Dedicate ONE section at the end of meeting/project notes for actual action items:

```markdown
## Action Items
- [ ] [Owner]: [Specific action] (due: YYYY-MM-DD)

## Questions for Follow-Up
- Question 1?
- Question 2?
```

---

*"Brevity is not about being terse. It's about respecting the reader's time and attention."*

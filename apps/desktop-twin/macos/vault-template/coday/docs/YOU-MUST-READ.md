# YOU-MUST-READ — Setup Guide

This guide tells you exactly what to write, in what order, to make your twin useful.

**Time required**: 2-3 hours total, spread across a few days.

---

## The Three Documents That Matter Most

Everything else in this kit is infrastructure. These three files are the **soul** of your twin.

---

### Document 1: `role-and-context.md` (~1 hour)

**What it is**: A description of what you do, how you think, and what you own.

**Why it matters**: Without this, your twin has no model of you. It will give generic advice instead of advice calibrated to your situation.

**What to write**:

```
## Your Role
What are you actually responsible for? Not your job title — what you OWN.
Examples: "I own the technical roadmap", "I own customer relationships for France",
"I own the P&L for my unit".

## What You Delegate Well
What have you successfully handed off? What do you trust others to do?

## What You Still Own Personally
What can't be delegated (or shouldn't be yet)? What always lands back with you?

## How You Make Decisions
What's your instinct? Consensus seeker or fast decider? Data-driven or gut-driven?
Where do you tend to overthink? Where do you move too fast?

## Your Operating Principles
The 3-5 principles that guide your work. Example: "reliability > features",
"people first", "simplicity always wins", "move fast, document later".

## Your Current Pain Points
What's eating your time that shouldn't be? Where are you a bottleneck?
What would you delegate if you could?

## Your Communication Style
How do you prefer to receive information? How do you communicate with your team?
What's your relationship with writing vs. talking?
```

---

### Document 2: `team-and-org.md` (~45 min)

**What it is**: The cast of characters in your work life.

**Why it matters**: Your twin needs to know who's who. Without this, you'll spend every conversation re-explaining relationships.

**What to write**:

```
## Your Direct Reports
Name, role, grade/level, key strengths, development areas.
Any current situations to be aware of (performance, engagement, projects).

## Your Manager
Name, working style, what they care about, how they prefer updates.

## Key Peers
Cross-functional partners you collaborate with regularly.
What do they own? What's the dynamic?

## Key External Relationships
Vendors, customers, partners that come up often.

## Your Team's Current State
Overall health, morale, any open tensions or challenges.
Recent hires, recent departures.
```

---

### Document 3: `current-priorities.md` (~30 min)

**What it is**: What you're actually working on right now.

**Why it matters**: Your twin needs to know your context window — the 3-5 things that are live and relevant today.

**What to write**:

```
## This Quarter's Priorities
The 3-5 initiatives that matter most right now. For each:
- What is it?
- Where does it stand?
- What's the blocker or next step?
- Who else is involved?

## Open Questions / Decisions Pending
Things you're actively thinking through. Dilemmas, trade-offs, things not yet resolved.

## Known Risks
What could go wrong? What are you watching?

## What Success Looks Like (End of Year)
If you look back 12 months from now, what would make this a great year?
```

**Important**: This document becomes stale fast. Update it monthly. Your twin will remind you.

---

## After Writing Your Three Documents

Update `coday/agents/twin.yaml` — replace all `[TODO: ...]` placeholders with your actual information.

Key fields to customize:
- Your name, role, company, background
- Your operating principles (from role-and-context.md)
- Your enterprise/business context (scale, industry, constraints)
- Your communication style preferences

---

## What You Don't Need to Write

The following files are already done — generic across users:
- `agentic-system-overview.md` — how the three agents work together
- `collaboration-principles.md` — how agents interact with the vault
- `note-writing-guidelines.md` — note writing standards
- `task-management-conventions.md` — how TODO.md works
- `obsidian-setup.md` — Obsidian plugin configuration
- `git-workflow-for-vault.md` — version control guide

You can read these if you want to understand the system. But they don't need editing.

---

## The Flywheel

Your twin gets smarter as it accumulates context. Here's how the flywheel works:

1. You feed it context docs (role, team, priorities)
2. You have conversations — it generates notes, meeting prep, analysis
3. Those notes become new context in the vault
4. The twin has more to draw on next session
5. Repeat

**First week**: Feels generic, needs a lot of prompting.  
**First month**: Starts to feel like it knows you.  
**First quarter**: You stop re-explaining context. It just knows.

The investment in those three initial documents pays compound returns.

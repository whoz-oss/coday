# Learning Loop

Coday agents learn from conversations and retain knowledge across sessions. This learning is never autonomous — every memorization requires explicit user validation.

## How It Works

The learning system has three complementary mechanisms, each operating at a different timescale.

### Continuous Learning (Real-Time)

During any conversation, agents actively detect learning opportunities:

- **Corrections**: when the user fixes an approach, output, or behavior
- **Preferences**: recurring patterns in how the user wants things done
- **Technical knowledge**: non-obvious discoveries — workarounds, config quirks, conventions
- **Hard-won resolutions**: problems that required significant debugging effort
- **Contradictions**: new information that invalidates an existing memory

When the agent detects one of these, it proposes a concise rule and asks for confirmation before memorizing. It reads existing memories first to avoid duplicates and flags contradictions explicitly.

This runs in every conversation with no setup required.

### Reflect (Retrospective)

The `memory reflect` command lets you select a past conversation thread and have the agent analyze it for missed learnings.

The agent walks through the thread looking for corrections, preferences, technical insights, patterns, and contradictions with existing memories. For each finding, it asks for confirmation before memorizing.

This is useful when a conversation was too intense to pause for learning, or when you suspect valuable knowledge was left uncaptured.

```bash
memory reflect
# Prompts you to select a thread, then analyzes it
```

The reflect prompt can be customized by placing a `reflect-prompt.md` file in `~/.coday/`. The default prompt is bundled with Coday.

### Curate (Maintenance)

The `memory curate` command triggers a structured review of existing memories.

The agent performs three passes:
1. **Consolidation**: identifies overlapping or redundant memories and merges them
2. **Cleanup**: detects outdated, incorrect, or inconsistent memories and proposes corrections or deletions
3. **Verification**: reviews the final state for completeness and organization

Curation can target a specific level and agent:

```bash
memory curate --user                        # curate user-level memories
memory curate --project --agent=Sway        # curate project-level memories for Sway
memory curate --user coding patterns        # curate user memories about a specific topic
```

## Challenging Existing Memories

Memories are not sacred. The learning system treats them as living knowledge that can become wrong over time — a library upgrade, a refactored codebase, or a changed convention can invalidate a previously correct memory.

Agents are instructed to:
- Compare new learnings against existing memories
- Flag contradictions explicitly: *"Memory [title] says X, but we just found Y. Should I update/delete it?"*
- Never silently keep a known-wrong memory in place
- Always defer the decision to the user

This applies in all three mechanisms: during real-time learning, during reflect analysis, and during curation.

## The User's Role

The user is the sole authority on what gets memorized, updated, or deleted. The agent proposes — the user decides.

This is a deliberate design choice. Fully autonomous learning loops tend to converge on local optima: the agent learns from itself, judges itself, and reinforces its own biases. Keeping the user in the loop provides the external perturbation that prevents drift.

In practice, this means:
- Every new memory requires a "yes" before being stored
- Every update or deletion of an existing memory requires confirmation
- The agent explains its reasoning but never acts unilaterally

## Memory Levels

Memories are stored at two levels:

| Level | Scope | Injection | Examples |
|-------|-------|-----------|----------|
| USER | Follows the user across projects | Always in system prompt | Strong preferences, communication style |
| LEARNING | Follows the user across projects | On-demand via prefetch | Technical knowledge, corrections, workarounds, patterns |
| PROJECT | Specific to a project | On-demand via prefetch | Architectural decisions, conventions, project-specific constraints |

Agents default to LEARNING level for technical knowledge and corrections. USER level is for strong personal preferences and communication style. PROJECT level is reserved for knowledge that only makes sense within a specific project's context.

USER memories are always injected in the system prompt — keep them few and essential. LEARNING and PROJECT memories are retrieved on-demand: before each response, a cheap LLM call selects which memories are relevant to the current message. This means you can accumulate many learnings without polluting the context.

## Tips

- **Don't skip confirmations.** A wrong memory is worse than no memory — it actively misleads future conversations.
- **Run `memory curate` periodically.** Memories accumulate. Consolidation keeps them useful.
- **Use `memory reflect` after intense sessions.** The best learnings often come from the hardest conversations.
- **Challenge old memories.** If an agent applies a memorized approach and it doesn't work, tell it. That's the signal to update.

## Related

- [Context and Memory](./context-and-memory.md): how context and memory work together
- [Prompting Strategies](./prompting-strategies.md): effective communication with agents

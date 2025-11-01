# Conversation Management

As conversations grow, managing context and narrative becomes important. This guide covers techniques for keeping conversations focused and effective.

## Understanding Conversation State

Every conversation has:
- **Message history**: All previous exchanges
- **Context window**: The amount of history the AI can "see" (limited by model)
- **Narrative thread**: The logical flow of the discussion

As conversations grow longer, older messages may fall outside the context window, potentially losing important context.

## When to Manage Conversations

Consider intervention when:
- The conversation becomes unfocused or meandering
- The agent seems to have forgotten earlier decisions
- You're starting a significantly different task
- The agent is repeating itself or seems confused

## Conversation Management Techniques

### Continuing the Current Thread

**Best for**: Related follow-ups, iterative refinement

Simply continue the conversation. The agent maintains context from recent messages:

```
You: Add error handling to the login function
Agent: [Implements error handling...]

You: Now add logging for failed attempts
Agent: [Adds logging, aware of previous changes...]
```

### Guiding the Narrative

**Best for**: Refocusing without losing context

Explicitly redirect the conversation:

```
You: Let's step back. We've implemented the login feature, but before moving on, 
     let's review the security implications.
```

This keeps history but reframes the current focus.

### Truncating the Thread

**Best for**: Removing irrelevant or incorrect paths

Delete messages that led down unproductive paths. In the web interface, use the delete button on specific messages. This removes that message and all subsequent ones, allowing you to restart from a better point.

**Use cases**:
- The agent misunderstood and went in the wrong direction
- You explored an approach that didn't work out
- Earlier messages contain outdated information

### Resetting the Conversation

**Best for**: Starting fresh on a new task

Start a new thread when:
- Beginning an unrelated task
- The conversation is too long and losing focus
- You want a clean slate without previous context

## Best Practices

### Keep Context Relevant

Regularly summarize or clarify:
```
You: To summarize: we've decided on JWT tokens for auth, implemented the login 
     endpoint, and added rate limiting. Now let's tackle session management.
```

### Detect When to Truncate

Signs you should truncate:
- Agent references outdated information
- Responses become generic or repetitive
- Agent asks questions already answered
- The conversation has gone in circles

### Strategic Checkpoints

Create natural checkpoints in long conversations:
```
You: Before we continue, let's document what we've decided so far...
Agent: [Summarizes decisions...]

You: Perfect. Now for the next phase...
```

This creates a reference point you can return to if needed.

## Managing Agent Memory

Agents can store important information in memories (see [Context and Memory](../05-working-effectively/context-and-memory.md)). This helps maintain continuity across conversations and even after resets.

## Tips

1. **Don't over-manage**: Let conversations flow naturally when they're productive
2. **Truncate early**: Better to cut a wrong path short than let it continue
3. **Document decisions**: Explicitly state conclusions before moving on
4. **Use memories**: Store important facts that should persist across conversations
5. **Reset guilt-free**: Starting fresh is often faster than trying to fix a derailed conversation

## Next Steps

Now that you understand conversation management, learn about [configuration](../04-configuration/configuration-levels.md) to customize Coday for your needs.

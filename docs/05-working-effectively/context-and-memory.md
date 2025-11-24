# Context and Memory

Effective use of context and memory is crucial for productive conversations with Coday agents. This guide explains how context works and how to leverage memory for long-term knowledge retention.

## Understanding Context

### What is Context?

Context is everything the agent "knows" when processing your message:

1. **System instructions**: The agent's role and guidelines
2. **Project description**: From `coday.yaml`
3. **Conversation history**: Recent messages and tool executions
4. **Memories**: Stored facts from previous conversations
5. **Documentation**: Linked files from project configuration

### Context Window Limitations

AI models have a limited context window—the amount of text they can process at once. For example:
- GPT-4: ~128,000 tokens (~100,000 words)
- Claude 4.5 Sonnet: ~200,000 tokens (~150,000 words)

As conversations grow, older messages may fall outside this window.

## Managing Context

### Keeping Context Relevant

**Strategy 1: Periodic Summarization**

```
You: Let's summarize what we've accomplished so far before continuing
Agent: [Summarizes key decisions and implementations]
You: Great. Now let's move on to the next feature...
```

This creates a condensed reference point without losing important information.

**Strategy 2: Explicit References**

```
You: Earlier you mentioned using Redis for caching. Let's implement that now.
```

Explicitly reference previous decisions to ensure the agent has the right context.

**Strategy 3: Context Injection**

```
You: For context: we decided to use JWT tokens (not sessions), 
     store them in httpOnly cookies (not localStorage), 
     and refresh them every 15 minutes.
     
     Now, implement the token refresh endpoint.
```

Provide essential context upfront when starting a new sub-task.

### When Context is Lost

Signs the agent has lost context:
- Suggests approaches already rejected
- Asks questions already answered
- Proposes solutions inconsistent with prior decisions
- Seems to "forget" earlier parts of the conversation

**Solutions:**
1. **Remind the agent**: Re-state key decisions
2. **Truncate and restart**: Remove off-track messages and restart from a good point
3. **Prepare for a new thread**: Ask for a summary of the past relevant part, to seed a new thread
3. **Use memory**: drop the objective and work with the agent in understanding why off-track, make him memorize what was missing

## Using Memory

### What is Memory?

Memory allows agents to store important facts that persist across conversations and even after thread resets. Memories are project-specific and shared across all agents.

### When to Use Memory

Store information that is:
- **Important**: Key decisions, architectural choices
- **Persistent**: Won't change frequently
- **Reusable**: Relevant to multiple conversations
- **Factual**: Not opinions or temporary states

**Good candidates for memory:**
- Architectural decisions and rationale
- Key conventions and patterns
- Important constraints or requirements
- Team agreements and standards

**Poor candidates for memory:**
- Temporary states or work-in-progress
- Highly specific implementation details
- Information likely to change
- Personal preferences (use user config instead)

### How Memory Works

Agents can store memories during conversations by using the dedicated tool. Upon adding or editing a memory, the user has the opportunity to check, edit or deny the memory edit.

### Viewing and Managing Memories

```bash
# List project memories of agent Dev
memory list --project --agent=Dev

# List user memories
memory list --user

# Remove a memory
memory delete # will prompt user for which memory
```

## Advanced Techniques

### Context Checkpoints

Create explicit checkpoints in long conversations:

```
You: Before we continue, let's establish a checkpoint.
     
     Decisions so far:
     1. Using JWT for authentication
     2. Storing tokens in httpOnly cookies
     3. 15-minute token expiration with refresh
     4. Redis for token blacklisting
     
     Current task: Implement token refresh endpoint
     
     Confirm this is correct before we proceed.

Agent: [Confirms or corrects]
```

### Contextual Handoffs

When switching agents, provide context:

```
You: @archay I've been working with @sway on implementing authentication.
     We've decided on JWT tokens with Redis for blacklisting.
     Can you review the architecture for security concerns?
```

### Memory-Driven Development

Use memory to establish project conventions:

```
Memory: "Always add JSDoc comments to public functions"
Memory: "Use async/await, never raw promises"
Memory: "Validate all API inputs with Zod schemas"

You: Implement a new API endpoint for user registration
Agent: [Automatically follows conventions from memory]
```

## Troubleshooting

### Agent Ignoring Context

- Re-state critical information explicitly
- Consider if memory would help
- Check if agent's system instructions conflict

### Memory Not Being Used

- Verify memory is enabled in agent integrations
- Check memories are relevant to current task
- Explicitly reference memories: "Based on our decision to..."
- Consider if memory content is too vague

### Context Overload

- Too much context can be as bad as too little
- Focus on relevant information only
- Use memory for persistent facts, not everything
- Truncate conversations that have wandered

## Next Steps

- [Detecting Hallucinations](./detecting-hallucinations.md): Recognize uncertain responses
- [Iterative Workflows](./iterative-workflows.md): Build solutions step by step
- [Conversation Management](../03-using-coday/conversation-management.md): Manage long conversations

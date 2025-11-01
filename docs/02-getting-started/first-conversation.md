# Your First Conversation

This guide walks you through your first interaction with a Coday agent, explaining the basics of conversation flow.

## Starting a Conversation

Once you've launched Coday and selected a project, you'll see the conversation interface. Simply type your message and press Enter (or Cmd/Ctrl+Enter depending on your preferences).

### Example First Message

```
Hello! Can you help me understand the architecture of this project?
```

## Understanding Agent Responses

The agent will:
1. Analyze your request
2. Use available tools to gather information (read files, search code, etc.)
3. Provide a response based on the project context

You'll see the agent's thought process through tool calls and responses in the conversation history.

## Asking Follow-up Questions

Conversations are contextual—the agent remembers previous exchanges:

```
You: What are the main components?
Agent: [Explains main components...]

You: Can you show me how Component A interacts with Component B?
Agent: [Analyzes and explains the interaction...]
```

## Requesting Actions

You can ask the agent to perform actions:

```
You: Can you add a new method to handle user authentication?
Agent: [Analyzes existing code, proposes implementation...]

You: That looks good, but can we add error handling?
Agent: [Refines the implementation with error handling...]
```

## Switching Agents

If you need a different perspective or specialized expertise, you can switch agents:

```
@archay Can you review this architecture decision?
```

The `@` syntax addresses a specific agent. See [Working with Agents](../03-using-coday/working-with-agents.md) for details.

## Managing Conversation Flow

- **Continue the thread**: Keep asking related questions
- **Change direction**: Start a new topic (the agent will adapt)
- **Reset if needed**: If the conversation becomes too long or off-track, you can truncate or start fresh

See [Conversation Management](../03-using-coday/conversation-management.md) for advanced techniques.

## Tips for Effective Conversations

1. **Be specific**: Clear requests get better responses
2. **Provide context**: Explain what you're trying to achieve
3. **Iterate**: Don't expect perfection on the first try
4. **Ask questions**: If something is unclear, ask for clarification

## Next Steps

You now know the basics of conversing with agents. Learn more about the interface and features in [Using Coday](../03-using-coday/interface-basics.md).

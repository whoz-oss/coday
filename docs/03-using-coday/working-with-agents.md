# Working with Agents

Agents are the core of Coday. This guide explains how to effectively interact with and switch between different agents.

## Understanding Agents

Each agent in Coday has:
- A **name** (e.g., `sway`, `archay`, `pm`)
- A **role** (e.g., software developer, architect, product manager)
- **System instructions** that define its behavior and expertise
- **Access to tools** for interacting with your project

Agents are configured in your project's `coday.yaml` file and can be customized to fit your team's needs.

## Default Agent

When you start a conversation without specifying an agent, the **default agent** handles your request. The default agent is typically configured for general development tasks.

## Addressing Specific Agents

Use the `@` syntax to address a specific agent:

```
@archay Can you review the architecture of the authentication module?
```

This directs your message to the `archay` agent (architect role) instead of the default agent.

### When to Switch Agents

Switch agents when you need:
- **Different expertise**: Architecture review, product decisions, specialized technical knowledge
- **Different perspective**: A fresh viewpoint on a problem
- **Specific workflows**: Agents configured for particular tasks (testing, deployment, etc.)

## Agent Responses

Agents respond based on:
- Your message content
- Conversation history
- Project context (code, documentation, configuration)
- Available tools and their results

### Understanding Tool Usage

You'll see agents using tools in real-time:
```
[Tool: read_file] Reading src/auth/login.ts
[Tool: search_files] Searching for authentication patterns
```

This transparency helps you understand the agent's reasoning process.

## Multi-Agent Conversations

You can have multiple agents in the same conversation:

```
You: @sway Implement a new login feature
Sway: [Proposes implementation...]

You: @archay Does this fit our architecture?
Archay: [Reviews from architectural perspective...]

You: @sway Update based on Archay's feedback
Sway: [Refines implementation...]
```

Each agent sees the full conversation history, including other agents' contributions.

## Agent Configuration

Agents are configured in `coday.yaml`. For details on creating and customizing agents, see [Agent Configuration](../04-configuration/agents.md).

## Tips for Working with Agents

1. **Choose the right agent**: Match the agent's expertise to your task
2. **Be explicit**: When switching agents, explain why if relevant context
3. **Leverage specialization**: Use agents for their specific strengths
4. **Iterate freely**: Don't hesitate to switch agents if you're not getting what you need

## Next Steps

Learn about [conversation management](./conversation-management.md) to keep your dialogues focused and effective.

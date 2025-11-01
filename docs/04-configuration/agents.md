# Agent Configuration

Agents are the core of Coday's functionality. This guide explains how to configure existing agents and create custom ones tailored to your project's needs.

## Understanding Agent Structure

An agent consists of:

```yaml
agents:
  - name: agent-name              # Unique identifier
    role: human-readable role     # Description of agent's purpose
    provider: anthropic           # AI provider (anthropic, openai, etc.)
    model: claude-3-5-sonnet      # Specific model to use
    systemInstructions: |         # Core prompt defining behavior
      You are a specialized agent...
    tools:                        # Available tools (optional)
      - read_file
      - write_file
    documentation:                # Agent-specific docs (optional)
      - path: docs/agent-guide.md
```

## Built-in Agents

Coday comes with several pre-configured agents:

- **Sway**: General software development agent
- **Archay**: Software architecture specialist
- **PM**: Product management perspective
- **Octopuss**: GitHub integration specialist

These are defined at the Coday level and available in all projects.

## Creating Custom Agents

### Basic Custom Agent

Add to your `coday.yaml`:

```yaml
agents:
  - name: backend-expert
    role: backend specialist
    systemInstructions: |
      You are an expert in backend development for this project.
      
      Key responsibilities:
      - API design and implementation
      - Database schema and queries
      - Performance optimization
      - Security best practices
      
      Project stack:
      - Node.js with TypeScript
      - PostgreSQL database
      - Express.js framework
      - Jest for testing
```

### Advanced Custom Agent

```yaml
agents:
  - name: testing-specialist
    role: test automation expert
    provider: openai
    model: gpt-4o
    systemInstructions: |
      You specialize in writing and maintaining tests for this project.
      
      Testing strategy:
      - Unit tests with Jest
      - Integration tests for APIs
      - E2E tests with Playwright
      - Minimum 80% code coverage
      
      When writing tests:
      1. Follow existing test patterns
      2. Use descriptive test names
      3. Test edge cases and error conditions
      4. Keep tests isolated and independent
    
    tools:
      - read_file
      - write_file
      - search_files
      - run_command
    
    documentation:
      - path: docs/testing-guide.md
        description: Testing guidelines
      - path: tests/README.md
        description: Test structure
```

## Agent Properties

### Required Properties

- **name**: Unique identifier (lowercase, hyphens allowed)
- **role**: Human-readable description
- **systemInstructions**: The prompt that defines agent behavior

### Optional Properties

- **provider**: Override default AI provider
- **model**: Override default model
- **tools**: Restrict or extend available tools
- **documentation**: Agent-specific documentation links
- **temperature**: Control response randomness (0.0-1.0)
- **maxTokens**: Limit response length

## Writing Effective System Instructions

### Structure

Good system instructions include:

1. **Role definition**: Who the agent is
2. **Responsibilities**: What the agent does
3. **Context**: Project-specific information
4. **Guidelines**: How the agent should behave
5. **Examples**: Optional, for complex behaviors

### Example Template

```yaml
systemInstructions: |
  # Role
  You are a [role] for this project.
  
  # Context
  This project is [description]. Key technologies: [stack].
  
  # Responsibilities
  Your main responsibilities:
  - [Responsibility 1]
  - [Responsibility 2]
  - [Responsibility 3]
  
  # Guidelines
  When working:
  1. [Guideline 1]
  2. [Guideline 2]
  3. [Guideline 3]
  
  # Communication Style
  - Be [concise/detailed/etc.]
  - Focus on [what matters]
  - Always [important practice]
```

### Best Practices

1. **Be specific**: Vague instructions produce vague results
2. **Provide context**: Include relevant project information
3. **Set expectations**: Define what "good" looks like
4. **Include constraints**: What NOT to do is as important as what to do
5. **Iterate**: Refine based on actual agent behavior

## Tool Configuration

### Default Tools

If no tools are specified, agents have access to all built-in tools:
- File operations (read, write, search)
- Command execution
- Memory management
- Code analysis

### Restricting Tools

Limit tools for safety or focus:

```yaml
agents:
  - name: readonly-analyst
    role: code analyst
    systemInstructions: |
      You analyze code but never modify it.
    tools:
      - read_file
      - search_files
      # No write_file or run_command
```

### Adding Custom Tools

Custom tools are defined separately and can be added to agents:

```yaml
tools:
  - name: custom_deploy
    description: Deploy to staging environment
    command: ./scripts/deploy.sh

agents:
  - name: devops
    role: deployment specialist
    tools:
      - read_file
      - custom_deploy
```

## Agent Specialization Strategies

### By Domain

```yaml
agents:
  - name: frontend
    role: frontend specialist
    systemInstructions: |
      Focus on React, CSS, and user experience...
  
  - name: backend
    role: backend specialist
    systemInstructions: |
      Focus on APIs, databases, and server logic...
```

### By Task

```yaml
agents:
  - name: reviewer
    role: code reviewer
    systemInstructions: |
      Review code for quality, security, and best practices...
  
  - name: implementer
    role: feature implementer
    systemInstructions: |
      Implement features based on requirements...
```

### By Perspective

```yaml
agents:
  - name: architect
    role: system architect
    systemInstructions: |
      Think about system design, scalability, maintainability...
  
  - name: pragmatist
    role: pragmatic developer
    systemInstructions: |
      Focus on getting things done, practical solutions...
```

## Testing Your Agents

After creating a custom agent:

1. **Address it directly**: `@agent-name Can you help with...`
2. **Test its boundaries**: Ask questions outside its expertise
3. **Verify tool usage**: Ensure it uses appropriate tools
4. **Iterate instructions**: Refine based on behavior

## Common Patterns

### Project Onboarding Agent

```yaml
agents:
  - name: onboarding
    role: project guide
    systemInstructions: |
      You help new team members understand this project.
      
      Start with:
      - Project purpose and architecture
      - Development setup
      - Key conventions and patterns
      - Where to find documentation
      
      Be welcoming and patient. Assume no prior knowledge.
```

### Security-Focused Agent

```yaml
agents:
  - name: security
    role: security specialist
    systemInstructions: |
      You focus exclusively on security concerns.
      
      Review code for:
      - Authentication/authorization issues
      - Input validation
      - SQL injection risks
      - XSS vulnerabilities
      - Sensitive data exposure
      
      Be thorough and cautious. Flag anything suspicious.
```

## Next Steps

- [MCP Integrations](./mcp-integrations.md): Extend agents with external tools
- [Agent Design](../05-working-effectively/agent-design.md): Advanced agent design patterns
- [Working with Agents](../03-using-coday/working-with-agents.md): Using agents effectively

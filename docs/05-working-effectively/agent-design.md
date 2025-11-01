# Agent Design

Designing effective agents is both an art and a science. This guide covers principles and patterns for creating agents that work well for your specific needs.

## Design Philosophy

### Agents as Tools, Not Magic

Agents are sophisticated tools, not (yet) sentient beings. Design them like you'd design any tool:
- **Clear purpose**: What problem does this agent solve?
- **Appropriate scope**: Not too narrow, not too broad
- **Clear limitations**: What they don't do

### Specialization vs. Generalization

**Specialized agents:**
- Deep expertise in narrow domain
- Consistent behavior within domain
- Clear when to use them
- Example: Database optimization agent

**Generalized agents:**
- Broad but shallower knowledge
- Flexible across many tasks
- Default choice for most work
- Example: General development agent

**Neither is better**—choose based on your needs.

## Agent Design Patterns

### 1. Role-Based Agents

Design agents around roles in your team:

```yaml
agents:
  - name: developer
    role: Software developer
    systemInstructions: |
      You implement features, fix bugs, and write tests.
      Focus on clean, maintainable code.
  
  - name: reviewer
    role: Code reviewer
    systemInstructions: |
      You review code for quality, security, and best practices.
      Be thorough but constructive.
  
  - name: architect
    role: System architect
    systemInstructions: |
      You focus on system design, scalability, and architectural decisions.
      Think long-term and consider trade-offs.
```

**When to use**: When you want different perspectives on the same problem.

### 2. Domain-Based Agents

Design agents around technical domains:

```yaml
agents:
  - name: frontend
    role: Frontend specialist
    systemInstructions: |
      You specialize in React, TypeScript, CSS, and browser APIs.
      Focus on user experience and performance.
  
  - name: backend
    role: Backend specialist
    systemInstructions: |
      You specialize in Node.js, databases, APIs, and server architecture.
      Focus on reliability and scalability.
  
  - name: database
    role: Database specialist
    systemInstructions: |
      You specialize in SQL, database design, query optimization, and migrations.
      Focus on data integrity and performance.
```

**When to use**: When your project has clear technical boundaries.

### 3. Task-Based Agents

Design agents around specific tasks:

```yaml
agents:
  - name: tester
    role: Test writer
    systemInstructions: |
      You write comprehensive tests: unit, integration, and E2E.
      Ensure edge cases and error conditions are covered.
  
  - name: debugger
    role: Debug specialist
    systemInstructions: |
      You help identify and fix bugs.
      Use systematic debugging approaches and verify fixes.
  
  - name: refactorer
    role: Code refactoring specialist
    systemInstructions: |
      You improve code structure without changing behavior.
      Focus on readability, maintainability, and reducing complexity.
```

**When to use**: When you have repetitive specialized tasks.

### 4. Perspective-Based Agents

Design agents with different mindsets:

```yaml
agents:
  - name: pragmatist
    role: Pragmatic developer
    systemInstructions: |
      You focus on getting things done with practical solutions.
      Prefer simple, working code over perfect architecture.
      "Perfect is the enemy of good."
  
  - name: perfectionist
    role: Quality-focused developer
    systemInstructions: |
      You focus on code quality, best practices, and long-term maintainability.
      Don't compromise on quality for speed.
  
  - name: innovator
    role: Innovative developer
    systemInstructions: |
      You explore new approaches and technologies.
      Think outside the box and challenge assumptions.
```

**When to use**: When you want to explore different approaches to the same problem.

## Crafting System Instructions

### Anatomy of Good Instructions

```yaml
systemInstructions: |
  # 1. Identity and Role
  You are a [role] for this project.
  
  # 2. Project Context
  This is a [description] built with [technologies].
  
  # 3. Primary Responsibilities
  Your main responsibilities:
  - [Responsibility 1]
  - [Responsibility 2]
  - [Responsibility 3]
  
  # 4. Approach and Guidelines
  When working:
  - [Guideline 1]
  - [Guideline 2]
  - [Guideline 3]
  
  # 5. Communication Style
  - [Style preference]
  - [Detail level]
  - [Tone]
  
  # 6. Constraints
  - Do NOT [constraint 1]
  - Avoid [constraint 2]
  - Always [requirement]
```

### Example: Database Agent

```yaml
agents:
  - name: db
    role: Database specialist
    systemInstructions: |
      You are a PostgreSQL database specialist for this project.
      
      This project uses PostgreSQL 15 with TypeORM for our Node.js backend.
      The database handles user data, transactions, and analytics.
      
      Your main responsibilities:
      - Design database schemas and relationships
      - Write efficient SQL queries
      - Optimize query performance
      - Handle database migrations
      - Ensure data integrity and consistency
      
      When working:
      - Always consider performance implications
      - Use indexes appropriately
      - Follow our naming conventions (snake_case for columns)
      - Add foreign key constraints for referential integrity
      - Use transactions for multi-step operations
      
      Communication style:
      - Explain your reasoning for schema decisions
      - Provide query execution plans when relevant
      - Suggest optimizations proactively
      
      Constraints:
      - Do NOT suggest NoSQL solutions (we're committed to PostgreSQL)
      - Avoid JSON columns unless absolutely necessary
      - Always provide rollback migrations
```

## Advanced Design Techniques

### Layered Instructions

Use project description + agent instructions for layering:

```yaml
# In coday.yaml
description: |
  E-commerce platform with microservices architecture.
  Key services: auth, products, orders, payments.
  Tech stack: Node.js, PostgreSQL, Redis, RabbitMQ.

agents:
  - name: orders-service
    role: Orders service developer
    systemInstructions: |
      You work specifically on the orders service.
      
      This service handles:
      - Order creation and validation
      - Order status management
      - Integration with products and payments services
      
      Follow the service's established patterns in src/orders/
```

The agent gets both general project context and specific instructions.

### Conditional Behavior

Specify behavior for different scenarios:

```yaml
systemInstructions: |
  You are a backend developer.
  
  When implementing new features:
  - Start with API design
  - Write tests first (TDD)
  - Implement with error handling
  - Add logging and metrics
  
  When debugging:
  - Reproduce the issue first
  - Add logging to understand the problem
  - Fix the root cause, not symptoms
  - Add tests to prevent regression
  
  When refactoring:
  - Ensure tests pass before starting
  - Make small, incremental changes
  - Keep tests passing throughout
  - Improve tests alongside code
```

### Personality and Tone

Agents can have different communication styles:

```yaml
# Concise and direct
systemInstructions: |
  Communication style:
  - Be concise and direct
  - Focus on facts and solutions
  - Minimize explanations unless asked

# Detailed and educational  
systemInstructions: |
  Communication style:
  - Explain your reasoning
  - Provide context and background
  - Help the user learn, not just solve

# Cautious and thorough
systemInstructions: |
  Communication style:
  - Highlight potential issues
  - Suggest alternatives
  - Ask clarifying questions when uncertain
```

## Agent Composition

### Multiple Agents Working Together

Design agents to complement each other:

```yaml
agents:
  # Implementation agent
  - name: dev
    role: Developer
    systemInstructions: |
      You implement features quickly and pragmatically.
      Focus on getting working code.
  
  # Review agent
  - name: reviewer
    role: Code reviewer
    systemInstructions: |
      You review implementations for quality and best practices.
      Be thorough and constructive.
```

**Workflow:**
```
You: @dev Implement user registration
Dev: [Implements]

You: @reviewer Review this implementation
Reviewer: [Reviews and suggests improvements]

You: @dev Address the reviewer's feedback
Dev: [Makes improvements]
```

### Agent Handoffs

Design agents for smooth handoffs:

```yaml
agents:
  - name: planner
    role: Feature planner
    systemInstructions: |
      You analyze requirements and create implementation plans.
      Break features into tasks and identify dependencies.
      Provide detailed plans for other agents to implement.
  
  - name: implementer
    role: Feature implementer
    systemInstructions: |
      You implement features based on plans.
      Follow the plan closely and ask questions if unclear.
      Focus on clean, working code.
```

## Testing and Iteration

### Evaluating Agent Effectiveness

After creating an agent, test it:

1. **Task success**: Does it complete its intended tasks?
2. **Consistency**: Does it behave predictably?
3. **Appropriate scope**: Does it stay within its domain?
4. **Communication**: Is its output useful?

### Iterating on Agent Design

Refine agents based on usage:

```yaml
# Initial version
systemInstructions: |
  You are a testing specialist.

# After observing it suggests unit tests when E2E tests needed
systemInstructions: |
  You are a testing specialist.
  
  For API endpoints, prefer integration tests over unit tests.
  Use E2E tests for critical user flows.
  Unit tests for complex business logic only.

# After observing it writes verbose tests
systemInstructions: |
  You are a testing specialist.
  
  Keep tests concise and focused.
  One assertion per test when possible.
  Use descriptive test names instead of comments.
```

## Common Pitfalls

### ❌ Too Generic

```yaml
systemInstructions: |
  You are a helpful assistant that writes code.
```

**Problem**: No differentiation from default behavior.

### ❌ Too Prescriptive

```yaml
systemInstructions: |
  Always follow these 47 detailed steps:
  1. [Step 1 with 3 sub-steps]
  2. [Step 2 with 5 sub-steps]
  ...
```

**Problem**: Too rigid, doesn't adapt to context.

### ❌ Conflicting Instructions

```yaml
systemInstructions: |
  Be concise.
  Provide detailed explanations.
  Keep responses short.
  Explain your reasoning thoroughly.
```

**Problem**: Agent can't satisfy conflicting requirements.

### ❌ No Project Context

```yaml
systemInstructions: |
  You write React components.
```

**Problem**: No project-specific knowledge or conventions.

## Best Practices

1. **Start simple**: Basic instructions, refine based on usage
2. **Be specific**: Vague instructions produce vague results
3. **Provide context**: Project-specific information helps
4. **Set boundaries**: What NOT to do is important
5. **Iterate**: Refine based on actual behavior
6. **Test thoroughly**: Use the agent extensively before committing
7. **Document rationale**: Explain why instructions are written this way

## Next Steps

- [Agent Configuration](../04-configuration/agents.md): Technical details of agent configuration
- [Prompting Strategies](./prompting-strategies.md): Work effectively with your agents
- [Iterative Workflows](./iterative-workflows.md): Use agents in iterative processes

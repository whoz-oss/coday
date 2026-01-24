# Agent Tooling

## Summary

Effective agent tooling is about giving agents the ability to:
1. **Act autonomously**: Implement, not just suggest
2. **Verify their work**: Compile, test, validate
3. **Self-correct**: Iterate until success
4. **Research**: Find information when needed
5. **Integrate**: Work with your existing tools and workflows

The three approaches (native integrations, MCP servers, project scripts) provide flexibility to match your needs. Start with project scripts for quick wins, use MCP for external services, and consider native integrations for core functionality.

Most importantly: **match tool complexity to model size**, and **guide agents through instructions and collaborative refinement**.

## The Power of Self-Verification

### Why Tools Matter

An agent without tools can only provide suggestions:
```
You: Implement a login feature
Agent: Here's how I would implement it... [provides code]
You: Does it compile?
Agent: I think so, but I can't verify...
```

An agent with tools becomes autonomous:
```
You: Implement a login feature
Agent: [Uses writeFileChunk to modify code]
Agent: [Uses compile tool to verify]
Agent: ✅ Compilation successful. Feature implemented.
```

### Real-World Impact

From the Coday project's own configuration (`Sway.yaml`):
```yaml
integrations:
  FILES:          # Read, write, search code
  GIT:            # Check status, diff, history
  PROJECT_SCRIPTS: # compile, test, lint
  FETCH:          # Research documentation
  PLAYWRIGHT:     # Test web interfaces
```

This toolset enables Sway to:
1. **Implement**: Write and modify code files
2. **Verify**: Compile the code to catch errors
3. **Test**: Run tests to validate behavior
4. **Review**: Use git diff to see changes
5. **Research**: Fetch external documentation when needed
6. **Validate UX**: Test web interfaces with Playwright

## Three Approaches to Tool Integration

Coday provides three complementary ways to give agents access to tools, each with distinct trade-offs:

### 1. Native Coday Integration

**What it is**: Tools coded directly into Coday's codebase as TypeScript classes.

**Examples**:
- `FileTools` - Read, write, search files
- `GitTools` - Git operations
- `MemoryTools` - Store and retrieve memories
- `JiraTools` - JIRA integration
- `GitLabTools` - GitLab integration
- `ConfluenceTools` - Confluence integration

### 2. MCP (Model Context Protocol) Servers

**What it is**: External processes that expose tools via the MCP standard protocol.

**Examples** (from `coday.yaml`):
```yaml
mcp:
  servers:
    - id: fetch
      name: FETCH
      command: uvx
      args: [mcp-server-fetch, --ignore-robots-txt]
      
    - id: github
      name: GITHUB
      command: docker
      args:
        - run
        - -i
        - --rm
        - -e
        - GITHUB_PERSONAL_ACCESS_TOKEN
        - ghcr.io/github/github-mcp-server
        
    - id: playwright
      name: PLAYWRIGHT
      command: npx
      args: ['@playwright/mcp@latest']
```

### 3. Project Scripts

**What it is**: Simple commands defined in `coday.yaml` that agents can execute.

**Examples** (from `coday.yaml`):
```yaml
scripts:
  compile:
    description: compile the typescript project to raise any issue on code correctness, it does not run the tests.
    command: nx run-many --target=build --all
    
  test:
    description: run tests for a specific file or pattern (or all of them if no arguments)
    command: npx jest PARAMETERS
    parametersDescription: the test file path or pattern to run specific tests
    
  lint-project:
    description: Runs the lint for the app.
    command: nx run client:lint && nx run server:lint
```

## Model Size and Tool Complexity

The relationship between model size and tool complexity is critical for effective agent performance:

### Small Models (e.g., Claude Haiku, GPT-4o-mini)

**Optimal tool profile**:
- **Count**: 20-40 tools maximum
- **Complexity**: Simple, single-purpose tools
- **Descriptions**: Clear, unambiguous descriptions

**Example configuration**:
```yaml
agents:
  - name: simple-reviewer
    modelName: SMALL
    integrations:
      FILES: [readFile, searchFilesByText]  # Only read operations
      GIT: [git]                             # Basic git commands
      PROJECT_SCRIPTS: [compile, test]       # Simple verification
```

**Why it works**:
- Fewer tools means clearer tool selection
- Simple descriptions reduce confusion
- Focused responsibility improves accuracy

### Large Models (e.g., Claude Sonnet, GPT-5)

**Optimal tool profile**:
- **Count**: 20-100+ tools 
- **Complexity**: Can handle sophisticated multi-step tools
- **Descriptions**: Can interpret nuanced or complex descriptions

**Example configuration** (from `Sway.yaml`):
```yaml
agents:
  - name: Sway
    modelName: BIG
    integrations:
      FILES:           # All file operations (~10 tools)
      FETCH:           # Web content retrieval
      AI:              # Delegate to other agents
      MEMORY:          # Memory management
      GITHUB:    # GitHub integration (~15 tools)
      GIT:             # Git operations
      PROJECT_SCRIPTS: # All project scripts
      PLAYWRIGHT:      # Browser automation (~30 tools)
```

**Why it works**:
- Large models can reason about tool selection
- Better at chaining multiple tool calls
- Can interpret complex tool descriptions
- Maintains context across many operations

### The Tool Complexity Spectrum

```
Simple Tool:
  Name: compile
  Description: "Compile the TypeScript project"
  Parameters: None
  → Small model friendly ✅

Complex Tool:
  Name: playwright_navigate
  Description: "Navigate to a URL in the browser context"
  Parameters: {url: string, waitUntil?: 'load'|'domcontentloaded'|'networkidle'}
  → Requires larger model ⚠️

Very Complex Tool:
  Name: jira_create_issue
  Description: "Create JIRA issue with custom fields, linked issues, watchers..."
  Parameters: {project, issueType, summary, description, priority, assignee, 
               customFields: {...}, linkedIssues: [...], watchers: [...]}
  → Large model strongly recommended 🔴
```

## Defining Tool Usage in Agent Instructions

Tools are available, but agents need guidance on **when** and **how** to use them effectively.

### Explicit Tool Doctrine

From `Sway.yaml` instructions:
```yaml
instructions: |
  ## Restrictions
  - do not write documentation in separate markdown files (unless explicitly asked to)
  - do not use git tools to fake running a test
  - do not write tests outside of the established patterns on the project
```

**Why explicit doctrine matters**:
- Prevents tool misuse (e.g., using git to circumvent test execution)
- Establishes workflow patterns (e.g., when to write docs)
- Defines boundaries (e.g., test patterns to follow)

### Implicit Tool Discovery

For tools with complex or domain-specific usage (like JIRA or Confluence), let the agent explore:

```yaml
agents:
  - name: jira-expert
    integrations:
      JIRA: []  # All JIRA tools available
    instructions: |
      You are a JIRA expert. When first accessing JIRA:
      1. List available projects and issue types
      2. Understand custom fields for this instance
      3. Ask the user about their specific JIRA workflow
      
      JIRA instances vary greatly - always verify:
      - Required vs optional fields
      - Valid values for custom fields
      - Project-specific conventions
```

### Collaborative Tool Definition

**Approach**: Work with the agent to refine tool usage over time.

```
You: @agent Try to create a JIRA issue for this bug
Agent: [Attempts, encounters error about missing custom field]
Agent: I see this JIRA instance requires a "Team" custom field. 
      What value should I use?
You: Use "Backend Team" for all backend issues
Agent: [Creates issue successfully]
You: Can you remember this for future issues?
Agent: [Uses memorize tool to store the convention]
```

**Benefits**:
- Agent learns project-specific conventions
- User doesn't need to document everything upfront
- Memories persist across conversations
- Iterative refinement of tool usage

### Tool Combination Patterns

Teach agents effective tool sequences:

```yaml
instructions: |
  When implementing a feature:
  1. Read relevant files to understand context
  2. Make changes using writeFileChunk
  3. Use compile tool to verify syntax
  4. Run targeted tests with test tool
  5. Use git diff to review changes
  6. If tests fail, iterate from step 2
  
  When debugging:
  1. Search for error messages with searchFilesByText
  2. Read the files containing those messages
  3. Use git log to see recent changes
  4. Check related tests
```


## Best Practices

### 1. Start Small, Grow Gradually

```yaml
# Phase 1: Basic tools
integrations:
  FILES: [readFile, writeProjectFile]
  PROJECT_SCRIPTS: [compile]

# Phase 2: Add verification
integrations:
  FILES: [readFile, writeProjectFile, searchFilesByText]
  PROJECT_SCRIPTS: [compile, test]
  GIT: [git]

# Phase 3: Full autonomy
integrations:
  FILES: []        # All file tools
  GIT: []          # All git tools
  PROJECT_SCRIPTS: []
  PLAYWRIGHT: []
```

### 2. Match Tools to Model Size

- **Small model**: 5-15 simple tools
- **Medium model**: 15-30 moderate complexity tools
- **Large model**: 30+ tools, complex workflows

### 3. Document Tool Expectations

```yaml
instructions: |
  ## Tool Usage Guidelines
  
  Always compile after code changes.
  Run targeted tests, not the entire suite.
  Use git diff to review changes before committing.
  Search before reading - find relevant files first.
```

### 4. Project Scripts for Quick Wins

```yaml
scripts:
  check-all:
    description: Run full verification suite
    command: npm run compile && npm run test && npm run lint
    
  deploy-staging:
    description: Deploy to staging environment
    command: ./scripts/deploy.sh staging
```

Quick to add, immediately useful.

## Troubleshooting Tool Issues

### Agent Not Using Available Tools

**Symptoms**: Agent suggests actions instead of doing them

**Solutions**:
1. Ask the agent: "What tools do you have access to?"
2. Check tool restrictions in `integrations` property
3. Verify model size is appropriate for tool complexity
4. Add explicit instructions about tool usage

### Agent Using Wrong Tools

**Symptoms**: Agent uses git instead of running tests

**Solutions**:
1. Add explicit restrictions in agent instructions
2. Clarify tool purposes in instructions
3. Provide workflow patterns (step-by-step guides)
4. Use tool name restrictions in `integrations`

### Tool Execution Failures

**Symptoms**: Tools fail or return errors

**Solutions**:
1. Check MCP server configuration and status
2. Verify command paths in project scripts
3. Ensure proper credentials for external services
4. Use `--debug` flag to see detailed tool execution logs

### Too Many Tools, Poor Performance

**Symptoms**: Agent slow to respond, makes wrong tool choices

**Solutions**:
1. Reduce tool count for smaller models
2. Use tool restrictions to limit available tools
3. Create specialized agents with focused toolsets
4. Upgrade to larger model for complex toolsets

## Next Steps

- [Agent Design](./agent-design.md): Learn how to design and create custom agents
- [Agent Configuration](../04-configuration/agents.md): Technical details of agent configuration
- [MCP Integrations](../04-configuration/mcp-integrations.md): Configure external tool servers
- [Working with Agents](../03-using-coday/working-with-agents.md): Understand agent behavior


# Agent Definitions

Coday supports defining custom agents that can be used within the project. Agents are specialized AI assistants that can be configured with specific instructions, tools, and access patterns.

## Agent Location and Resolution

Agents can be defined in multiple locations:

1. **Project configuration**: Inside the `coday.yaml` file under the `agents` property
2. **Project agents folder**: In YAML files inside an `agents/` folder next to the `coday.yaml` file
3. **User-specific agents**: In YAML files inside `~/.coday/[project]/agents/` folder
4. **Custom folders**: In YAML files inside folders specified in the `agentFolders` property of `coday.yaml`
5. **Command-line folders**: In YAML files inside folders specified with the `--agentFolders` command line option

Agent definition files must use `.yml` or `.yaml` extension and each file should contain a single agent definition.

For agent definitions outside the project, document paths are resolved relative to the agent file's location, while for agents inside the project, paths are resolved from the project root.

## Designing Effective Agents

Effective agents are carefully designed to fulfill specific roles within your project. The three key components that define an agent's capabilities are:

### Instructions

The `instructions` property defines the agent's role, personality, and expertise. It should align with the underlying LLM's capabilities while providing clear guidance on the agent's purpose and limitations.

**Example**: A code review agent might include instructions like:

```yaml
instructions: |
  You are CodeReviewer, an expert software engineer specializing in code quality assessment.  
  Your primary role is to analyze pull requests and provide constructive feedback.  
  Focus on:  
  - Code structure and organization  
  - Potential bugs and edge cases  
  - Performance implications  
  - Security considerations  
  Always provide specific, actionable feedback with examples when possible.  
  Be thorough but respectful in your analysis.  
```

### Documentation

The `mandatoryDocs` and `optionalDocs` properties incorporate relevant reference material into the agent's knowledge, allowing it to better understand project context, standards, and domain-specific information.

**Example**: A deployment agent might include documentation like:

```yaml
mandatoryDocs:
  - "infrastructure/deployment-process.md"
  - "infrastructure/aws-architecture.md"
optionalDocs:
  - path: "infrastructure/rollback-procedures.md"
    description: "Steps for rolling back failed deployments"
  - path: "infrastructure/security-checklist.md"
    description: "Pre-deployment security verification steps"
```

### Tools and Integrations

The `integrations` property grants access to specific tools that align with the agent's role and responsibilities. Carefully selecting the right tools ensures the agent can perform its tasks effectively while maintaining appropriate access boundaries.

**Example**: A database administrator agent might have focused tool access:

```yaml
integrations:
  file: ["readProjectFile", "searchFilesByText", "readPdfFile"]  # Read-only file access
  git: ["gitStatusTool", "gitDiffTool"]          # Read-only git access
  # Custom database tools would be configured at the project level
  database: []                                  # Full access to database tools
```

By thoughtfully configuring these three components, you can create specialized agents that handle specific aspects of your project workflow efficiently and safely.

## Agent Configuration

An agent definition can include the following properties:

```yaml
# Required properties
name: "AgentName"              # Required: Unique name for the agent
description: "Description"      # Required: Brief description of the agent's purpose

# Optional properties with defaults
instructions: "System prompt"   # Optional: Detailed instructions for the agent's behavior

# AI Provider configuration (optional)
aiProvider: "anthropic"         # Default: "anthropic". Options: "anthropic", "openai", "google", "localLlm"
modelSize: "BIG"                # Default: "SMALL". Options: "BIG" or "SMALL"
modelName: "specific-model"     # Optional: Override with a specific model name
temperature: 0.7                # Default: provider-specific. Controls randomness (0.0-2.0)

# OpenAI Assistant integration (optional)
openaiAssistantId: "asst_..."   # ID of an existing OpenAI Assistant

# Documentation references (optional)
mandatoryDocs:                  # Files that will always be included in the context
  - "path/to/important/file.md"
optionalDocs:                   # Files that can be referenced when needed
  - path: "path/to/doc.md"
    description: "What this document contains"

# Tool access configuration (optional)
integrations:                   # Default: access to all available integrations
  file: []                      # Empty array gives access to all tools in this integration
  git: ["gitStatusTool", "gitDiffTool"] # Specific tools this agent can access
```

## Tool Integrations

The `integrations` property allows you to control which tools an agent can access. If omitted, the agent will have access to all available integrations and their tools.

Coday supports several built-in integrations:

- `ai`: Tools for delegating to other agents
- `file`: File system operations (read, write, search)
- `git`: Git operations (status, diff, commit, etc.)
- `gitlab`: GitLab API integration
- `jira`: Jira issue tracking integration
- `confluence`: Confluence documentation integration
- `memory`: Tools for storing and retrieving information across sessions
- `mcp`: MCP coordination (doesn't provide actual tools)
- `mcp_<serverId>`: Access to a specific MCP server (where `<serverId>` is the ID of a configured MCP server)

For each integration, you can specify:

1. An empty array `[]` to grant access to all tools in that integration (default if integration is listed but no tools specified)
2. A list of specific tool names to grant access to only those tools

For example:

```yaml
integrations:
  file: []                       # Access to all file tools
  git: ["gitStatusTool", "gitDiffTool"] # Only read-only Git tools
  jira: ["jiraSearchIssues"]     # Only search capability for Jira
  # MCP server-specific integrations:
  mcp_fetch: []                  # Access to all tools from the "fetch" MCP server
  mcp_file: ["mcp__file__readFile"] # Access only to the readFile tool from the "file" MCP server
```

Note for MCP integrations:
- Always use the server-specific integrations (`mcp_<serverId>`) to access MCP tools
- The `mcp` integration itself doesn't provide tools - it only coordinates server discovery
- MCP servers must be configured and enabled in user or project configuration

Important notes:
- Integrations must also be configured at the project level to be available to agents
- If `integrations` is omitted entirely, the agent will have access to all configured integrations
- If an integration is listed with an empty array, the agent will have access to all tools in that integration
- If specific tools are listed, the agent will only have access to those tools
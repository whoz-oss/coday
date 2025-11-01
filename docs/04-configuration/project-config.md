# Project Configuration

Project configuration is defined in `coday.yaml` at the root of your project. This configuration is shared with all team members and defines how Coday works with your specific project.

## Creating coday.yaml

Create a `coday.yaml` file in your project root:

```yaml
version: 1
name: my-project
path: /path/to/project

description: |
  Brief description of your project, its purpose, and key architectural decisions.
  This context helps agents understand your codebase.

ai:
  defaultProvider: anthropic
  defaultModel: claude-3-5-sonnet-20241022

agents:
  - name: custom-agent
    role: specialized developer
    systemInstructions: |
      You are a specialized agent for this project...
    
documentation:
  - path: docs/architecture.md
    description: System architecture overview
  - path: README.md
    description: Project introduction
```

## Configuration Structure

### Project Metadata

```yaml
version: 1                    # Configuration format version
name: my-project              # Project identifier
path: /absolute/path          # Project root path
description: |                # Project description for agents
  Detailed description...
```

The description is crucial—it provides context that agents use to understand your project.

### AI Configuration

Override default AI settings for the project:

```yaml
ai:
  defaultProvider: anthropic
  defaultModel: claude-3-5-sonnet-20241022
  providers:
    - name: openai
      models:
        - name: gpt-4o
          contextWindow: 128000
```

**Note**: API keys should NOT be in project config—they belong in user config.

### Custom Agents

Define project-specific agents (see [Agents](./agents.md) for details):

```yaml
agents:
  - name: backend-expert
    role: backend specialist
    provider: anthropic
    model: claude-3-5-sonnet-20241022
    systemInstructions: |
      You specialize in backend development for this project.
      Focus on API design, database interactions, and performance.
    tools:
      - read_file
      - write_file
      - search_files
```

### Documentation Links

Link relevant documentation files:

```yaml
documentation:
  - path: docs/api.md
    description: API documentation
  - path: ARCHITECTURE.md
    description: System architecture
  - path: docs/database-schema.md
    description: Database structure
```

Agents can access these files for context.

### Memory Configuration

Configure project memory settings:

```yaml
memory:
  enabled: true
  autoSave: true
  confirmBeforeSaving: true
```

### MCP Integrations

Configure Model Context Protocol servers (see [MCP Integrations](./mcp-integrations.md)):

```yaml
mcp:
  servers:
    - id: filesystem
      name: Filesystem Access
      command: npx
      args:
        - "@modelcontextprotocol/server-filesystem"
        - "/path/to/project"
      enabled: true
```

## Best Practices

### Commit to Version Control

The `coday.yaml` file should be committed to your repository. It's part of your project's AI workflow and should be shared with the team.

```bash
git add coday.yaml
git commit -m "Add Coday configuration"
```

### Document Your Configuration

Add comments explaining custom agents and non-obvious settings:

```yaml
agents:
  # Database expert agent - use @db for schema and query questions
  - name: db
    role: database specialist
    systemInstructions: |
      Specializes in PostgreSQL queries and schema design.
```

### Keep It Focused

Include only what's relevant to the team. Personal preferences belong in user config.

### Version Control Exclusions

Add to `.gitignore`:
```
# User-specific Coday configuration (contains API keys)
.coday/
user-config.json
```

But DO commit:
```yaml
# Project Coday configuration (shared)
coday.yaml
```

## Common Patterns

### Multi-Agent Project

```yaml
agents:
  - name: frontend
    role: frontend specialist
    systemInstructions: |
      Focus on React, TypeScript, and UI/UX.
  
  - name: backend
    role: backend specialist
    systemInstructions: |
      Focus on Node.js, databases, and APIs.
  
  - name: devops
    role: DevOps specialist
    systemInstructions: |
      Focus on deployment, CI/CD, and infrastructure.
```

### Documentation-Heavy Project

```yaml
documentation:
  - path: docs/
    description: Full documentation directory
  - path: API.md
    description: REST API reference
  - path: CONTRIBUTING.md
    description: Contribution guidelines
  - path: ARCHITECTURE.md
    description: System design
```

### Monorepo Configuration

```yaml
name: monorepo-project
path: /path/to/monorepo

# Document structure for agents
description: |
  Monorepo containing multiple packages:
  - packages/frontend: React application
  - packages/backend: Node.js API
  - packages/shared: Shared utilities
  
  Each package has its own README with specific details.
```

## Troubleshooting

### Configuration Not Loading

- Verify `coday.yaml` is at project root
- Check YAML syntax (indentation, colons, etc.)
- Ensure `version: 1` is present

### Agents Not Appearing

- Check agent name is unique
- Verify systemInstructions are provided
- Ensure YAML structure is correct

### Documentation Not Accessible

- Verify paths are relative to project root
- Check file permissions
- Ensure files exist

## Next Steps

- [Agents](./agents.md): Define custom agents in detail
- [MCP Integrations](./mcp-integrations.md): Extend with external tools
- [Configuration Levels](./configuration-levels.md): Understand config merging

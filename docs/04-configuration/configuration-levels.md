# Configuration Levels

Coday uses a three-level configuration hierarchy that allows flexibility while maintaining sensible defaults. Understanding these levels is key to effectively configuring Coday for your needs.

## The Three Levels

### 1. Coday Level (Read-Only)

**Location**: Built into Coday installation
**Purpose**: Global defaults and framework configuration
**Scope**: All users, all projects

This level provides:
- Default agents (Sway, Archay, PM, etc.)
- Built-in tools and their configurations
- Base system settings
- Default MCP server configurations

You cannot modify this level—it's part of Coday itself.

### 2. Project Level (Shared)

**Location**: `coday.yaml` in your project root
**Purpose**: Project-specific configuration shared by all team members
**Scope**: All users working on this project

This level defines:
- Project description and context
- Custom agents specific to the project
- Project-specific tool configurations
- AI provider preferences for the project
- MCP integrations relevant to the project
- Linked documentation and resources

This file is typically committed to version control and shared with the team.

### 3. User Level (Personal)

**Location**: `~/.config/coday/user-config.json` (or platform-specific config directory)
**Purpose**: Personal preferences and credentials
**Scope**: Only you, across all projects

This level stores:
- AI provider API keys and credentials
- Personal agent preferences
- Interface preferences (theme, voice, etc.)
- User-specific tool configurations
- Personal MCP server configurations

This file contains secrets and should NOT be committed to version control.

## Configuration Merging

When Coday runs, configurations are merged in this order:

```
Coday Level (defaults)
  ↓ (overridden by)
Project Level (coday.yaml)
  ↓ (overridden by)
User Level (user-config.json)
```

### Example: AI Provider Configuration

```
Coday Level:
  - Default model: gpt-4o-mini

Project Level (coday.yaml):
  - Preferred model: claude-3-5-sonnet-20241022

User Level:
  - API key: sk-...
  - Personal override: gpt-4o (for testing)

Result: Uses gpt-4o with your API key
```

## When to Configure at Each Level

### Configure at Coday Level
Never—this is managed by the framework itself.

### Configure at Project Level
When the setting should be:
- Shared with the team
- Project-specific
- Part of the project's AI workflow
- Safe to commit to version control

**Examples**: Project description, custom agents, preferred models, documentation links

### Configure at User Level
When the setting is:
- Personal preference
- Contains credentials or secrets
- User-specific overrides
- Not relevant to other team members

**Examples**: API keys, voice preferences, personal model overrides, local MCP servers

## Best Practices

1. **Default to Project Level**: If it helps the team, put it in `coday.yaml`
2. **Keep Secrets in User Level**: Never commit API keys or credentials
3. **Document Project Config**: Explain custom agents and configurations in comments
4. **Respect Team Choices**: User-level overrides should be exceptions, not the norm

## Next Steps

- [User Configuration](./user-config.md): Managing your personal settings
- [Project Configuration](./project-config.md): Setting up `coday.yaml`
- [Agent Configuration](./agents.md): Defining custom agents
- [MCP Integrations](./mcp-integrations.md): Extending Coday with external tools

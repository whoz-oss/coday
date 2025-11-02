# User Configuration

User-level configuration stores your personal preferences and credentials. This configuration is specific to you and applies across all projects.

## Configuration Location

User configuration is stored in:
- **Linux/macOS**: `~/.coday/users/{username}/user.yaml`
- **Windows**: `%USERPROFILE%\.coday\users\{username}\user.yaml`

Where `{username}` is your system username (sanitized: non-alphanumeric characters replaced with underscores).

This file should **never** be committed to version control as it contains API keys and personal settings.

## Managing User Configuration

### Via Web Interface

The easiest way to configure user settings:

1. Launch the web interface: `pnpm web`
2. Click the menu icon (hamburger menu, top-left)
3. Click "User Config" (⚙️ icon)
4. Edit the JSON configuration directly
5. Save changes

### Via Command Line

```bash
# Configure AI providers interactively
config ai add
config ai edit <provider-name>

# Configure MCP servers
config mcp add
config mcp edit <server-id>

# Edit user bio
config bio edit
```

### Via Direct File Edit

You can manually edit the configuration file with any text editor. The file is in YAML format.

## Configuration Structure

```yaml
version: 1

# Optional: User bio for agent context
bio: |
  I am a software engineer specializing in TypeScript and Node.js.
  I prefer functional programming patterns and clean code.

# AI provider configurations
ai:
  - name: openai
    apiKey: sk-...
    models:
      - name: gpt-4o
        contextWindow: 128000
        
  - name: anthropic
    apiKey: sk-ant-...
    models:
      - name: claude-3-5-sonnet-20241022
        contextWindow: 200000

# MCP (Model Context Protocol) server configurations
mcp:
  servers:
    - id: filesystem
      name: Filesystem Access
      command: npx
      args: ["@modelcontextprotocol/server-filesystem", "/workspace"]
      enabled: true

# Project-specific overrides
projects:
  my-project:
    # Override default agent for this project
    defaultAgent: specialized-agent
    
    # Project-specific bio
    bio: |
      For this project, I prefer concise responses.
    
    # Project-specific integrations (GitHub tokens, etc.)
    integration:
      jira:
        apiUrl: https://acme.atlassian.net/
        username: john.doe@email.com
        apiKey: sk_****
    
    # Project-specific MCP overrides
    mcp:
      servers:
        - id: project-specific-server
          name: Project Tools
          command: node
          args: ["./tools/mcp-server.js"]
          enabled: true
```

## Key Configuration Areas

### AI Providers

Configure API keys and model preferences:

```yaml
ai:
  - name: anthropic
    apiKey: sk-ant-...
    models:
      - name: claude-3-5-sonnet-20241022
        contextWindow: 200000
        
  - name: openai
    apiKey: sk-...
    # Uses default models if not specified
```

**Provider Configuration Options**:
- `name` (required): Provider identifier (`openai`, `anthropic`, `google`, or custom name)
- `type`: Provider type (`openai` or `anthropic`) - required for custom names
- `apiKey`: Your API key for this provider
- `url`: Custom API endpoint (for self-hosted or proxy)
- `secure`: Boolean indicating if provider is on secured infrastructure (non-cloud)
- `models`: Array of model definitions to add or override defaults

**Important**: API keys are sensitive. Ensure your user config file has appropriate permissions (readable only by you).

### User Bio

Provide context about yourself to agents:

```yaml
bio: |
  I am Vincent, a software engineer with 10 years of experience.
  I prefer TypeScript and functional programming.
  I value clean code and SOLID principles.
```

This bio is used by agents to better understand your preferences and communication style.

### MCP (Model Context Protocol) Servers

Configure tools that agents can use:

```yaml
mcp:
  servers:
    - id: filesystem
      name: Filesystem Access
      command: npx
      args: ["@modelcontextprotocol/server-filesystem", "/workspace"]
      enabled: true
      debug: false
      allowedTools: ["read_file", "write_file"]
      env:
        API_KEY: "..."
```

See [MCP Configuration](./mcp-config.md) for detailed documentation.

### Project-Specific Configuration

Override settings for specific projects:

```yaml
projects:
  my-project:
    # Override default agent
    defaultAgent: code-reviewer
    
    # Project-specific bio
    bio: |
      For this project, focus on performance optimization.
    
    # Project-specific integrations
    integration:
      github:
        token: ghp_...
        owner: my-org
        repo: my-repo
    
    # Project-specific MCP servers
    mcp:
      servers:
        - id: project-tools
          name: Project-Specific Tools
          command: node
          args: ["./tools/server.js"]
          enabled: true
```

**Project Configuration Options**:
- `defaultAgent`: Preferred agent name for this project
- `bio`: Project-specific bio that supplements your user bio
- `integration`: Project-specific integration configurations (GitHub, etc.)
- `mcp`: Project-specific MCP server configurations

## Common Tasks

### Adding an AI Provider

**Via CLI**:
```bash
config ai add --name=anthropic
# Follow the interactive prompts
```

**Via File**:
```yaml
ai:
  - name: anthropic
    apiKey: sk-ant-...
```

### Configuring GitHub Integration

```yaml
projects:
  my-project:
    integration:
      github:
        token: ghp_...
        owner: my-org
        repo: my-repo
```

### Adding MCP Servers

**Via CLI**:
```bash
config mcp add
# Follow the interactive prompts
```

**Via File**:
```yaml
mcp:
  servers:
    - id: my-server
      name: My Custom Server
      command: node
      args: ["./server.js"]
      enabled: true
```

## Configuration Hierarchy

User configuration works with project configuration through a three-level hierarchy:

1. **CODAY Level** (global defaults from coday.yaml)
2. **PROJECT Level** (project-specific in project config)
3. **USER Level** (your personal overrides)

Settings at higher levels override lower levels. This means:
- Your user-level AI provider configs supplement project-level configs
- Your user-level MCP servers merge with project-level servers
- Your user-level project-specific settings override project defaults

See [Configuration Levels](./configuration-levels.md) for detailed merge behavior.

## Security Best Practices

1. **Never commit** user config to version control
2. **Set restrictive permissions** on the config file (chmod 600 on Unix)
3. **Rotate API keys** regularly
4. **Use environment variables** for CI/CD instead of config files
5. **Review project-specific tokens** regularly

## Troubleshooting

### Configuration Not Loading

- Check file location matches your OS
- Verify YAML syntax is valid (use a YAML validator)
- Check file permissions
- Look for error messages in console output

### API Key Not Working

- Verify the key is correct (no extra spaces)
- Check provider name matches exactly (`openai`, `anthropic`, etc.)
- Ensure you have credits/quota with the provider
- Check if `url` is set correctly for custom endpoints

### MCP Server Not Starting

- Check command and args are correct
- Verify the MCP server executable is installed
- Look at debug logs if `debug: true` is set
- Check environment variables are properly set

## File Format Notes

- The configuration file uses **YAML format** (`.yml` extension)
- Multi-line strings use `|` for literal blocks
- Arrays use `-` prefix for items
- Comments start with `#`
- Indentation is significant (use spaces, not tabs)

## Next Steps

- [Project Configuration](./project-config.md): Setting up team-wide configuration
- [Configuration Levels](./configuration-levels.md): Understanding how configs merge
- [MCP Configuration](./mcp-config.md): Detailed MCP server setup

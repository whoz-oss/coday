# MCP Integrations

MCP (Model Context Protocol) allows Coday to integrate with external tools and services through a standardized interface.

## What is MCP?

MCP is an open protocol developed by Anthropic that enables AI agents to interact with external tools through a standardized interface. MCP servers expose tools that agents can discover and use.

## Configuring MCP Servers

MCP servers can be configured at two levels:

### Project Level (coday.yaml)

For team-shared tools without credentials:

```yaml
mcp:
  servers:
    - id: fetch
      name: Web Fetch
      command: uvx
      args:
        - mcp-server-fetch
        - --ignore-robots-txt
      enabled: true
```

### User Level (user config)

For tools requiring personal credentials:

```bash
# Via command line
config mcp add github

# Via web interface
Menu → User Config → Edit MCP section
```

Example user configuration:
```yaml
mcp:
  servers:
    - id: github
      name: GitHub Integration
      command: docker
      args:
        - run
        - -i
        - --rm
        - -e
        - GITHUB_PERSONAL_ACCESS_TOKEN
        - ghcr.io/github/github-mcp-server
      env:
        GITHUB_PERSONAL_ACCESS_TOKEN: "ghp_..."
      enabled: true
```

## MCP Server Properties

### Required Properties

- **id**: Unique identifier for the server
- **name**: Human-readable name
- **command**: Executable to run (e.g., `npx`, `uvx`, `docker`)
- **args**: Command arguments as array

### Optional Properties

- **enabled**: Whether server is active (default: true)
- **debug**: Enable debug logging (default: false)
- **env**: Environment variables for the server
- **cwd**: Working directory for the server

## How Agents Access MCP Tools

Once an MCP server is configured, its tools become available to agents:

1. Configure the MCP server (in user config or `coday.yaml`)
2. Start Coday - the MCP server loads automatically
3. Agents can now use the tools

**By default**, all configured MCP tools are available to all agents.

### Example

If you configure a filesystem MCP server:
```yaml
mcp:
  servers:
    - id: filesystem
      command: npx
      args: ["@modelcontextprotocol/server-filesystem", "/workspace"]
```

All agents can immediately use tools like `read_file`, `write_file`, `list_directory` without additional configuration.

## Examples from Coday Project

### Web Fetch

```yaml
- id: fetch
  name: FETCH
  command: uvx
  args:
    - mcp-server-fetch
    - --ignore-robots-txt
  enabled: true
```

Allows agents to fetch web content for research.

### GitHub Integration

```yaml
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
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: "ghp_..."
  enabled: true
```

Provides GitHub API access (issues, PRs, repositories).

### Browser Automation

```yaml
- id: playwright
  name: PLAYWRIGHT
  command: npx
  args:
    - '@playwright/mcp@latest'
  enabled: true
```

Enables browser automation for testing and web interaction.

## Managing MCP Servers

### Via Command Line

```bash
# List configured servers at all levels
config mcp list

# Add a new server (interactive) - defaults to user level
config mcp add server-id

# Add at project level
config mcp add server-id --project

# Edit a server
config mcp edit server-id

# Delete a server
config mcp delete server-id
```

### Via Web Interface

1. Open menu (hamburger icon)
2. Select "User Config" (for personal MCP servers) or "Project Config" (for team-shared)
3. Edit the `mcp` section
4. Save changes

**Tip**: Use User Config for MCP servers with your personal credentials (GitHub token, etc.). Use Project Config (in `coday.yaml`) for team-shared tools without secrets.

## Available MCP Servers

### Official Anthropic Servers

- **@modelcontextprotocol/server-filesystem**: File system operations
- **@modelcontextprotocol/server-github**: GitHub API integration (via Docker)
- **mcp-server-fetch**: Web content fetching
- **@playwright/mcp**: Browser automation

### Finding More Servers

Check the [MCP documentation](https://modelcontextprotocol.io) for additional servers and how to create your own.

## Troubleshooting

### Server Not Starting

- Check command is valid and executable
- Verify all arguments are correct
- Check environment variables are set
- Enable debug mode for detailed logs: `debug: true`

### Tools Not Available

- Verify server is enabled: `enabled: true`
- Ensure server started successfully (check console logs)
- Restart Coday to reload MCP servers

### Permission Errors

- Check environment variables are accessible
- Ensure credentials are valid
- For Docker-based servers, verify Docker is running

## Next Steps

- [User Configuration](./user-config.md): Configure user-level MCP servers
- [Project Configuration](./project-config.md): Configure project-level MCP servers
- [Agent Design](../05-working-effectively/agent-design.md): Design agents that use MCP tools

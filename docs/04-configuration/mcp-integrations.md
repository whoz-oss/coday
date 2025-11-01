# MCP Integrations

The Model Context Protocol (MCP) allows Coday to integrate with external tools and services, extending agent capabilities beyond built-in functionality.

## What is MCP?

MCP is an open protocol developed by Anthropic that enables AI agents to interact with external tools and data sources through a standardized interface. MCP servers expose tools that agents can discover and use.

## Why Use MCP?

- **Extend capabilities**: Add functionality not built into Coday
- **Integrate services**: Connect to databases, APIs, cloud services
- **Custom tools**: Create project-specific tools
- **Reusable**: MCP servers work across different AI applications

## Configuring MCP Servers

### User-Level MCP Configuration

For personal tools or services with credentials:

```bash
# Via command line
config mcp add my-server

# Via web interface
Menu → User Config → Edit MCP section
```

User-level MCP configuration is stored in your user config file and not shared with the team.

### Project-Level MCP Configuration

For team-shared tools:

Add to `coday.yaml`:

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
      allowedTools:
        - read_file
        - list_directory
```

## MCP Server Properties

### Required Properties

- **id**: Unique identifier for the server
- **name**: Human-readable name
- **command**: Executable to run (e.g., `npx`, `python`, `/usr/local/bin/custom-tool`)
- **args**: Command arguments as array

### Optional Properties

- **enabled**: Whether server is active (default: true)
- **debug**: Enable debug logging (default: false)
- **allowedTools**: Restrict which tools agents can use (default: all)
- **env**: Environment variables for the server
- **cwd**: Working directory for the server

## Example Configurations

### Filesystem Access

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

### Database Access

```yaml
mcp:
  servers:
    - id: postgres
      name: PostgreSQL Database
      command: npx
      args:
        - "@modelcontextprotocol/server-postgres"
      env:
        DATABASE_URL: "postgresql://user:pass@localhost:5432/dbname"
      enabled: true
      allowedTools:
        - query
        - list_tables
```

### GitHub Integration

```yaml
mcp:
  servers:
    - id: github
      name: GitHub API
      command: npx
      args:
        - "@modelcontextprotocol/server-github"
      env:
        GITHUB_TOKEN: "${GITHUB_TOKEN}"  # Use environment variable
      enabled: true
```

### Custom Tool

```yaml
mcp:
  servers:
    - id: custom-deploy
      name: Deployment Tool
      command: /usr/local/bin/deploy-mcp-server
      args:
        - "--config"
        - "./deploy-config.json"
      cwd: "/path/to/project"
      enabled: true
```

## Available MCP Servers

### Official Anthropic Servers

- **@modelcontextprotocol/server-filesystem**: File system operations
- **@modelcontextprotocol/server-postgres**: PostgreSQL database access
- **@modelcontextprotocol/server-github**: GitHub API integration
- **@modelcontextprotocol/server-slack**: Slack integration
- **@modelcontextprotocol/server-google-drive**: Google Drive access

### Community Servers

Many community-developed MCP servers are available. Check the MCP registry or GitHub for options.

### Custom Servers

You can create your own MCP servers following the protocol specification. See [MCP documentation](https://modelcontextprotocol.io) for details.

## Managing MCP Servers

### Via Command Line

```bash
# List configured servers
config mcp list

# Add a new server (interactive)
config mcp add server-id

# Edit a server
config mcp edit server-id

# Delete a server
config mcp delete server-id

# Test a server
config mcp test server-id
```

### Via Web Interface

1. Open menu (hamburger icon)
2. Select "User Config" or "Project Config"
3. Edit the `mcp` section
4. Save changes

## Security Considerations

### Credentials

- **User config**: Store credentials in user-level MCP configuration
- **Environment variables**: Use `${VAR_NAME}` syntax to reference environment variables
- **Never commit secrets**: Keep credentials out of `coday.yaml`

### Tool Restrictions

Use `allowedTools` to limit what agents can do:

```yaml
mcp:
  servers:
    - id: database
      name: Database Access
      command: npx
      args:
        - "@modelcontextprotocol/server-postgres"
      allowedTools:
        - query          # Allow
        - list_tables    # Allow
        # Drop table is NOT allowed
```

### Audit and Monitor

- Enable `debug: true` to log all tool calls
- Review agent tool usage in conversation history
- Restrict MCP servers to necessary functionality only

## Troubleshooting

### Server Not Starting

- Check command is valid and executable
- Verify all arguments are correct
- Check environment variables are set
- Enable debug mode for detailed logs

### Tools Not Available

- Verify server is enabled
- Check `allowedTools` configuration
- Ensure server started successfully (check logs)

### Permission Errors

- Check file/directory permissions
- Verify environment variables are accessible
- Ensure credentials are valid

## Best Practices

1. **Start minimal**: Add MCP servers as needed, not preemptively
2. **Document purpose**: Explain why each server is configured
3. **Restrict tools**: Only allow necessary tools
4. **Use environment variables**: For credentials and sensitive data
5. **Test thoroughly**: Verify MCP servers work before relying on them
6. **Monitor usage**: Keep an eye on what tools agents are using

## Next Steps

- [Configuration Levels](./configuration-levels.md): Understand where to configure MCP
- [Agent Configuration](./agents.md): Assign MCP tools to specific agents
- [Working Effectively](../05-working-effectively/prompting-strategies.md): Use MCP tools effectively

# 📦 Model Context Protocol (MCP) Integration

The Model Context Protocol (MCP) integration allows Coday to extend AI capabilities through external servers that provide specialized tools and resources. This document explains how to configure and use MCP with Coday.

## 🔍 Overview

MCP (Model Context Protocol) is a standardized protocol that enables AI systems to interact with external tools and resources. In Coday, MCP integration allows agents to:

- Access specialized tools provided by external servers
- Fetch and process content from various sources
- Extend agent capabilities beyond built-in functions

These integrations work through server configurations that can be defined at both user and project levels.

## ⚙️ Configuration

MCP servers are configured using Coday's configuration commands. You can set up MCP servers at either the user level (applies to your account only) or the project level (applies to all users of the project).

### Managing MCP Servers

Commands for managing MCP servers follow this pattern:

```bash
config mcp <command> [--project]  # Add --project flag for project-level operations
```

Available commands:

| Command | Description |
|---------|-------------|
| `config mcp list` | List all configured MCP servers (shows both user and project level) |
| `config mcp add` | Add a new MCP server at user level |
| `config mcp edit` | Edit an existing MCP server at user level |
| `config mcp delete` | Delete an MCP server at user level |

Add the `--project` or `-p` flag to the `add`, `edit`, or `delete` commands to operate at the project level instead of user level:

```bash
config mcp add --project    # Add a new MCP server at project level
config mcp edit --project   # Edit an existing MCP server at project level
config mcp delete --project # Delete an MCP server at project level
```

### Basic Configuration Example

To add a new MCP server configuration:

```bash
# Add a user-level MCP server
config mcp add

# Add a project-level MCP server
config mcp add --project
```

You'll be prompted to provide the necessary configuration details:

```
Adding a new user-level MCP server configuration:
ID of the MCP server: mcp-fetch
Name of the MCP server: Web Fetch Service
Select transport type: command
Command to execute the MCP server: uvx
Arguments for the command (space-separated): mcp-server-fetch --ignore-robots-txt
Environment variables (format: KEY1=VALUE1 KEY2=VALUE2): 
Working directory (leave empty for default): 
Authentication token (leave empty if not needed): 
Enable this server?: true
Allowed tools (comma-separated list, empty for all tools): 
```

### Configuration Parameters

| Parameter | Description | Required |
|-----------|-------------|----------|
| `id` | Unique identifier for the MCP server | Yes |
| `name` | Human-readable name for the MCP server | Yes |
| `transport type` | Type of connection (currently only `command` is supported) | Yes |
| `command` | Command to execute the MCP server | Yes (for command transport) |
| `args` | Space-separated list of arguments for the command | No |
| `env` | Environment variables in KEY=VALUE format | No |
| `cwd` | Working directory for the command | No |
| `authToken` | Authentication token for the MCP server | No |
| `enabled` | Whether the server is enabled | Yes |
| `allowedTools` | Comma-separated list of allowed tools (empty for all) | No |

## 🚀 Usage

Once configured, MCP servers provide tools that are available to AI agents during conversations. The server's **name** (not its ID) is critical as it defines the integration that can be used by agents.

### Agent Integration Declaration

Importantly, each agent must explicitly declare which integrations it can use. In the agent's configuration:

- If an agent has a list of integrations, you must add the MCP server's **name** to that list
- If an agent has no integrations list specified, it will have access to all available integrations

This ensures that agents only have access to appropriate tools for their function.

### Types of MCP Tools

MCP servers can provide two types of tools:

1. **Function Tools**: Execute specific functions on the MCP server
2. **Resource Tools**: Fetch resources from specified URIs

### Example Usage

When an MCP server is configured and its name is added to an agent's integrations list, its tools become available to that agent during conversations. For example:

```
User: Can you get information about the current weather in Paris?

Agent: Let me check that for you.
[Uses weather-service getWeather tool with parameters {"location": "Paris"}]

The current temperature in Paris is 22°C with partly cloudy skies...
```

### Tool Naming and Access

Tools are accessed using the MCP server's name as the integration identifier. The actual tool names appear as defined by the MCP server, without any special prefixes in the agent's view.

## 🔄 Integration with Other Features

MCP servers integrate with other Coday features:

- **Tool Management**: MCP tools are automatically loaded alongside built-in tools
- **Context Enhancement**: MCP resources can be used to enhance agent context
- **Multi-level Configuration**: Supports both user and project-level settings

## ⚠️ Limitations and Considerations

- **Transport Types**: Currently only command-based (stdio) transport is supported; HTTP/HTTPS transport is planned for future releases
- **Security**: MCP servers run locally with the same permissions as Coday
- **Performance**: Complex or slow MCP tools may impact conversation responsiveness

## 🧩 Advanced Usage

### Server Environments

You can configure environment variables for MCP servers, which is useful for:

- Setting API keys for external services
- Configuring server behavior
- Setting proxy configurations

## 🛠️ Troubleshooting

Common issues and their solutions:

1. **Server not responding**: Check if the command is installed and executable
2. **Tools not appearing**: Verify the server is enabled and properly configured
3. **Permission errors**: Check file system permissions for the command
4. **Command failures**: Check server logs for detailed error messages

To diagnose issues, try listing your configured servers:

```bash
config mcp list
```

This will show the status of each server and its configuration.

### Console Logs

For more detailed troubleshooting, check the console logs. The console output is much more verbose than the web interface and includes valuable debugging information about MCP server connections, initialization, and tool calls.

- **Terminal**: When running Coday in a terminal, logs are displayed directly in the console
- **Web Interface**: is much less verbose, the same level of logs will be found however on the console running the backend.

Look for messages containing "MCP" to find relevant information about server startup, connection issues, and tool execution details. The logs will show:

- MCP server startup attempts
- Connection success or failure messages
- Tool registration information
- Detailed error messages from the MCP server
- Tool invocation and response data
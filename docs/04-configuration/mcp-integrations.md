# MCP Integrations

MCP (Model Context Protocol) allows Coday to integrate with external tools and services through a standardized interface.

## What is MCP?

MCP is an open protocol developed by Anthropic that enables AI agents to interact with external tools through a standardized interface. MCP servers expose tools that agents can discover and use.

Coday supports two transport types:

- **stdio** — a local process is spawned and communicated with over stdin/stdout. Ideal for self-hosted or CLI-based tools.
- **Remote (HTTP/SSE)** — Coday connects over the network to an already-running server. Ideal for official hosted services (e.g. Atlassian Jira MCP).

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
- **command** *(stdio only)*: Executable to run (e.g., `npx`, `uvx`, `docker`) — use either `command` or `url`, not both
- **args** *(stdio only)*: Command arguments as array
- **url** *(remote only)*: URL of the remote MCP server — use either `url` or `command`, not both

### Optional Properties (all servers)

- **enabled**: Whether server is active (default: true)
- **debug**: Enable debug logging (default: false)

### Optional Properties (stdio servers)

- **env**: Environment variables for the server process
- **cwd**: Working directory for the server process

### Optional Properties (remote servers)

- **authToken**: Static Bearer token sent with every request. Use this for services that issue API tokens rather than OAuth.
- **oauth2**: Set to `true` to enable full OAuth 2.1 authentication. Coday will guide the user through the consent flow on first use.
- **oauthClientId**: *(optional)* Pre-registered OAuth client ID. When provided, Coday skips dynamic client registration and uses this identity directly.
- **oauthClientSecret**: *(optional)* OAuth client secret. Used together with `oauthClientId`.
- **oauthScope**: *(optional)* Override the OAuth scope requested during authorization. Set to an empty string `""` to request no scope at all.
- **oauthRedirectUri**: *(optional)* Override the OAuth redirect URI. When not set, Coday derives it from its own base URL as `<coday-base-url>/oauth/callback`. Must match a redirect URI registered with your OAuth provider.
- **noShare**: Set to `true` to prevent sharing this MCP server instance across threads. This is required for OAuth-authenticated servers (Coday sets it automatically when `oauth2: true`).

## Remote MCP Servers

Remote MCP servers run independently of Coday and are accessed over the network. They use either **Streamable HTTP** or the legacy **SSE** transport. Coday tries Streamable HTTP first and falls back to SSE automatically, so you don't need to specify the transport type.

### When to use remote vs stdio

| Situation | Recommended transport |
|---|---|
| CLI tool or Docker image available | stdio |
| Official hosted service (e.g. Atlassian Jira MCP) | remote |
| You control the server deployment | either |
| Credentials are per-user (OAuth) | remote |

### Unauthenticated remote server

```yaml
mcp:
  servers:
    - id: my-remote-mcp
      name: My Remote MCP
      url: https://api.example.com/mcp
      enabled: true
```

### Static token authentication

For services that provide a long-lived API token:

```yaml
mcp:
  servers:
    - id: my-api-mcp
      name: My API MCP
      url: https://api.example.com/mcp
      authToken: "your-bearer-token-here"
      enabled: true
```

The token is sent as an `Authorization: Bearer <token>` header on every request. Store this in your **user config** rather than the project config to avoid committing credentials.

### OAuth 2.1 — dynamic client registration (e.g. Atlassian Jira MCP)

For services that support OAuth 2.1 with dynamic client registration, you only need to set `oauth2: true`. Coday handles registration automatically:

```yaml
mcp:
  servers:
    - id: jira-remote
      name: Atlassian Jira
      url: https://mcp.atlassian.com/v1/sse
      oauth2: true
      enabled: true
```

### OAuth 2.1 — pre-registered client (project-level config)

If you have already registered an OAuth application with the provider, supply the client credentials directly. This is useful for team deployments where everyone shares the same registered app:

```yaml
mcp:
  servers:
    - id: jira-remote
      name: Atlassian Jira
      url: https://mcp.atlassian.com/v1/sse
      oauth2: true
      oauthClientId: "your-client-id"
      oauthClientSecret: "your-client-secret"
      oauthScope: "read:jira-work"
      enabled: true
```

> **Tip**: Place `oauthClientId` / `oauthClientSecret` in the project config (`coday.yaml`) so the whole team shares the registered app, while individual access tokens remain in each user's own config.

## OAuth 2.1 Flow

When an agent first tries to use a tool from an OAuth-protected MCP server, Coday walks the user through authorization:

1. **Detection** — Coday detects that the server requires OAuth (either because `oauth2: true` is set, or because the server returns a 401 with an OAuth challenge).
2. **Authorization panel** — An authorization panel appears in the UI with a button to open the OAuth consent page in a browser popup.
3. **User consent** — The user logs in and grants the requested permissions in the popup.
4. **Token storage** — Access and refresh tokens are stored per-user in the user config under `projects.[project].integration.[mcpId].mcpOAuth`. They are never written to the project config.
5. **Subsequent uses** — Token refresh is handled automatically. The user only needs to re-authorize if the refresh token expires or is revoked.

## How Agents Access MCP Tools

Once an MCP server is configured, its tools become available to agents:

1. Configure the MCP server (in user config or `coday.yaml`)
2. Start Coday — the MCP server loads automatically
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

**Tip**: Use User Config for MCP servers with your personal credentials (GitHub token, OAuth tokens, etc.). Use Project Config (in `coday.yaml`) for team-shared tools and OAuth app registrations without per-user secrets.

## Available MCP Servers

### Official Anthropic Servers

- **@modelcontextprotocol/server-filesystem**: File system operations
- **@modelcontextprotocol/server-github**: GitHub API integration (via Docker)
- **mcp-server-fetch**: Web content fetching
- **@playwright/mcp**: Browser automation

### Hosted Remote Servers

- **Atlassian Jira MCP** (`https://mcp.atlassian.com/v1/sse`): Jira issue management via OAuth 2.1

### Finding More Servers

Check the [MCP documentation](https://modelcontextprotocol.io) for additional servers and how to create your own.

## Troubleshooting

### Server Not Starting (stdio)

- Check command is valid and executable
- Verify all arguments are correct
- Check environment variables are set
- Enable debug mode for detailed logs: `debug: true`

### Tools Not Available

- Verify server is enabled: `enabled: true`
- Ensure server started successfully (check console logs)
- Restart Coday to reload MCP servers

### Permission Errors (stdio)

- Check environment variables are accessible
- Ensure credentials are valid
- For Docker-based servers, verify Docker is running

### Remote Server Not Connecting

- Verify the `url` field is correct and the server is reachable from your machine
- Confirm the server supports Streamable HTTP or SSE transport (other transports are not supported)
- Check firewall or proxy settings if the request never reaches the server

### OAuth Not Working

- Make sure the redirect URI (your Coday deployment URL + `/oauth/callback`, or the explicit `oauthRedirectUri` value if set) is registered as an allowed redirect URI with the OAuth provider
- If using pre-registered client credentials, double-check `oauthClientId` and `oauthClientSecret` are correct
- Some providers require specific scopes — use `oauthScope` to match what the provider expects

### OAuth Token Expired or Invalid

Tokens are refreshed automatically when they expire. If the refresh itself fails (e.g. the refresh token was revoked), you can clear the stored token and re-authorize:

1. Open your user config (Menu → User Config, or `~/.coday/users/<username>/user.yml`)
2. Find the entry under `projects.[projectName].integration.[mcpId].mcpOAuth`
3. Delete that block and save
4. The next tool call will restart the authorization flow

## Next Steps

- [User Configuration](./user-config.md): Configure user-level MCP servers
- [Project Configuration](./project-config.md): Configure project-level MCP servers
- [Agent Design](../05-working-effectively/agent-design.md): Design agents that use MCP tools

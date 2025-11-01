# Project Configuration

Project configuration is stored in Coday's configuration directory and defines how Coday works with your specific project. Unlike `coday.yaml` which is project documentation, the project configuration contains runtime settings and integration details.

## Configuration Location

Project configuration is stored in:
- **Linux/macOS**: `~/.coday/projects/{project-name}/project.yaml`
- **Windows**: `%USERPROFILE%\.coday\projects\{project-name}\project.yaml`

Where `{project-name}` is either:
- A manually created project name
- An auto-generated volatile project ID (format: `{basename}_{hash}`)

## Project Types

### Regular Projects
Created explicitly via the web interface:

1. Launch the web interface: `pnpm web`
2. On the project selection screen, click **"+ Create New Project"**
3. Enter:
   - **Project Name**: A descriptive name (e.g., `my-project`)
   - **Project Path**: Absolute path to the project directory
4. Click **"Create Project"**

See screenshots: `project_selection.png` and `project_creation.png` in docs/images/

### Volatile Projects
Auto-generated when Coday starts in `--local` mode in a directory without a project configuration:
- **ID Format**: `{basename}_{hash}` (e.g., `coday_a1b2c3d4`)
- **Marked**: `volatile: true` and `createdAt: <timestamp>`
- **Purpose**: Quick start without manual setup
- **Lifecycle**: Persisted permanently (not cleaned up by Coday)

## Configuration Structure

```yaml
version: 1                    # Configuration format version (required)
path: /absolute/path          # Project root path (required)

# AI provider configurations (optional)
ai:
  - name: openai
    apiKey: sk-...           # Can be set here but better in user config
    models:
      - name: gpt-4o
        contextWindow: 128000

# Integration configurations (required, can be empty)
integration:
  github:
    token: ghp_...
    owner: my-org
    repo: my-repo

# Custom agents (optional)
agents:
  - name: backend-expert
    role: backend specialist
    provider: anthropic
    model: claude-3-5-sonnet-20241022
    systemInstructions: |
      You specialize in backend development for this project.

# MCP server configurations (optional)
mcp:
  servers:
    - id: filesystem
      name: Filesystem Access
      command: npx
      args:
        - "@modelcontextprotocol/server-filesystem"
        - "/path/to/project"
      enabled: true

# Volatile project metadata (auto-generated for volatile projects)
volatile: true               # Indicates auto-generated project
createdAt: 1234567890        # Timestamp of creation
```

## Managing Project Configuration

### Via Web Interface

The easiest way to configure project settings:

1. Launch the web interface: `pnpm web`
2. Select a project
3. Click the menu icon (hamburger menu, top-left)
4. Click "Project Config" (📁 icon)
5. Edit the JSON configuration directly
6. Save changes

### Via Direct File Edit

You can manually edit the configuration file with any text editor. The file is in YAML format.

## Key Configuration Areas

### Required Fields

```yaml
version: 1                    # Always required
path: /absolute/path          # Project root path
integration: {}               # Can be empty but must exist
```

### AI Providers (Optional)

Configure AI providers at project level:

```yaml
ai:
  - name: anthropic
    apiKey: sk-ant-...        # Better in user config
    models:
      - name: claude-3-5-sonnet-20241022
        contextWindow: 200000
```

**Note**: API keys in project config apply to all users. For personal API keys, use [user configuration](./user-config.md).

### Integrations

Configure project-specific integrations:

```yaml
integration:
  github:
    token: ghp_...            # Project-level token
    owner: my-org
    repo: my-repo
  
  gitlab:
    token: glpat-...
    projectId: "12345"
```

### Custom Agents

Define project-specific agents:

```yaml
agents:
  - name: code-reviewer
    role: code review specialist
    provider: anthropic
    model: claude-3-5-sonnet-20241022
    systemInstructions: |
      You are a code reviewer for this project.
      Focus on code quality, best practices, and potential bugs.
    tools:
      - read_file
      - search_files
```

See [Agents](./agents.md) for detailed agent configuration.

### MCP Servers

Configure Model Context Protocol servers:

```yaml
mcp:
  servers:
    - id: project-tools
      name: Project-Specific Tools
      command: node
      args: ["./scripts/mcp-server.js"]
      enabled: true
      env:
        PROJECT_ROOT: "/path/to/project"
```

See [MCP Configuration](./mcp-config.md) for detailed MCP setup.

## Configuration Hierarchy

Project configuration works with user configuration through a three-level hierarchy:

1. **CODAY Level** (global defaults from coday.yaml in Coday installation)
2. **PROJECT Level** (this project configuration)
3. **USER Level** (user-specific overrides in user config)

Settings at higher levels override lower levels:
- User-level AI providers supplement project-level providers
- User-level MCP servers merge with project-level servers
- User-level integrations override project-level integrations

See [Configuration Levels](./configuration-levels.md) for detailed merge behavior.

## Project Modes

Coday can run in different project modes:

### Default Mode (--local)
- Operates on current directory
- Creates volatile project if no config exists
- Single project focus

### Multi-Project Mode (--multi)
- Access to all configured projects
- Switch between projects
- Web interface shows project selector

### Forced Mode (--local with existing project)
- Restricts access to single project
- Prevents switching to other projects
- Secure for production deployments

## Project vs User vs Coday.yaml

### coday.yaml (in project root)
- **Purpose**: Project documentation for AI agents
- **Contains**: Description, documentation links, memories
- **Committed**: Yes, to version control
- **Location**: Project root directory

### project.yaml (in Coday config)
- **Purpose**: Runtime configuration and integrations
- **Contains**: Integrations, agents, AI providers, MCP servers
- **Committed**: No, stays local
- **Location**: `~/.coday/projects/{name}/project.yaml`

### user.yaml (in Coday config)
- **Purpose**: Personal credentials and preferences
- **Contains**: API keys, user bio, personal overrides
- **Committed**: Never
- **Location**: `~/.coday/users/{username}/user.yaml`

## Troubleshooting

### Configuration Not Loading

- Check file location: `~/.coday/projects/{project-name}/project.yaml`
- Verify YAML syntax is valid (use a YAML validator)
- Ensure `version: 1` and `path` are present
- Check file permissions

### Project Not Appearing

- Verify project directory exists
- Check `project.yaml` file exists in the project directory
- In `--local` mode, only current/default project appears
- Use `--multi` to see all projects

### Volatile Project Issues

- Volatile projects persist indefinitely (not auto-cleaned)
- To organize projects, create regular projects via web UI
- Check `volatile: true` flag to identify auto-generated projects

## File Format Notes

- The configuration file uses **YAML format** (`.yaml` extension)
- Multi-line strings use `|` for literal blocks
- Arrays use `-` prefix for items
- Comments start with `#`
- Indentation is significant (use spaces, not tabs)

## Next Steps

- [User Configuration](./user-config.md): Personal settings and credentials
- [Configuration Levels](./configuration-levels.md): Understanding config merging
- [Agents](./agents.md): Define custom agents
- [MCP Configuration](./mcp-config.md): Configure external tools

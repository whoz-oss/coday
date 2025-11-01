# Launching Coday

Coday provides a modern web interface for interacting with AI agents. This is the primary way to use Coday.

## Standard Launch

From any directory:

```bash
npx --yes @whoz-oss/coday-web
```

This will:
1. Start the Coday server on an available port (typically 3000-3010)
2. Display the URL in your terminal
3. Use the current directory as the default project

Open your browser and navigate to the displayed URL (usually `http://localhost:3000`).

## Command Line Options

```bash
npx --yes @whoz-oss/coday-web [options]
```

### Project Selection Options

- `--coday_project <name>` - Restrict to a specific project by name (takes precedence over all other modes)
- `--local` - Use current directory as the only project (restricted mode, no project switching)
- `--multi` - Multi-project mode: show project list without default selection

**Default behavior** (no flag): Uses current directory as default project, but allows switching to other projects.

### Server Options

- `--port <number>` - Specify server port (default: 3000, auto-increments if occupied)
- `--auth` - Enable authentication (requires auth proxy with x-forwarded-email header)
- `--debug` - Enable debug logging for troubleshooting

### Execution Options

- `--prompt <text>` - Execute prompt(s) immediately (can be used multiple times)
- `--oneshot` - Run in one-shot mode (non-interactive, exits after execution)
- `--fileReadOnly` - Read-only mode (no write/delete file operations)

### Configuration Options

- `--configDir <path>` - Custom path to .coday config directory (default: `~/.coday`)
- `--agentFolders <paths>` or `-af <paths>` - Additional folders for agent definitions (can be used multiple times)
- `--log-folder <path>` - Custom folder for log files (default: `~/.coday/logs`)
- `--no-log` - Disable logging entirely

### Examples

```bash
# Launch with specific project
npx --yes @whoz-oss/coday-web --coday_project my-project

# Local mode (current directory only)
npx --yes @whoz-oss/coday-web --local

# Multi-project mode
npx --yes @whoz-oss/coday-web --multi

# Debug mode with custom port
npx --yes @whoz-oss/coday-web --debug --port 3005

# Execute a prompt immediately
npx --yes @whoz-oss/coday-web --prompt "Explain this project"

# Read-only mode with authentication
npx --yes @whoz-oss/coday-web --fileReadOnly --auth

# Custom config and agent folders
npx --yes @whoz-oss/coday-web --configDir ~/.my-coday -af ./custom-agents
```

## Next Steps

Now that you know how to launch Coday, learn about [your first conversation](./first-conversation.md).

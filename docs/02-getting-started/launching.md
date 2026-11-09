# Launching Coday

Coday provides a modern web interface for interacting with AI agents. This is the primary way to use Coday.

## Standard Launch

From any directory:

```bash
  npx --yes @whoz-oss/coday-web
```

This will:
1. Start the Coday server, usually on `http://localhost:3000`. If a port is already taken by another program, Coday moves to a free address by itself and prints it: nothing needs to be stopped
2. Display the URL in your terminal
3. Use the current directory as the default project

Open your browser and navigate to the displayed URL (usually `http://localhost:3000`).

## Preflight

Before starting, Coday checks the few things it needs and prints a short report:

```
Coday preflight
  ok  node     v24.4.0
  ok  java     openjdk 25 2025-09-16
  ok  config   /home/me/.coday
  !!  ripgrep  not found
               Agents search files with it. Install it with: brew install ripgrep
  ok  ports    block 3020, open http://localhost:3020/
               Port 3000 is in use (by node, pid 42), Coday starts on block 3020: http://localhost:3020/
```

- `ok` nothing to do.
- `!!` a warning: Coday starts anyway. The `ports` line is worth reading — when another program (often a Coday you
  already have running) holds the usual address, this is where the address to open is announced.
- `XX` a blocker: Coday stops there and says what to install or fix. Node and Java are the usual ones.

Run it on its own at any time with `npx --yes @whoz-oss/coday-web doctor`, and skip it with `--skip-doctor` (or
`CODAY_SKIP_DOCTOR=1`). The desktop applications do not run it: their installer handles the prerequisites and their
first-run wizard reports the same facts.

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

- `--port <number>` - Choose the address port yourself (default: Coday picks 3000, or the next free address). When you choose it, it is strict: if it is taken, Coday stops and names the program holding it. `PORT=<number>` does the same
- `--auth` - Enable authentication. Supports two identity sources (tried in order):
  1. **Cloudflare Access** — decodes the `Cf-Access-Jwt-Assertion` header JWT and uses its `email` claim.
  2. **Reverse-proxy header** — falls back to the `x-forwarded-email` header set by an upstream auth proxy.
- `--debug` - Enable debug logging for troubleshooting
- `--skip-doctor` - Skip the preflight checks (see above). `CODAY_SKIP_DOCTOR=1` does the same

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
npx --yes @whoz-oss/coday-web --debug --port 3100

# Execute a prompt immediately
npx --yes @whoz-oss/coday-web --prompt "Explain this project"

# Read-only mode with authentication
npx --yes @whoz-oss/coday-web --fileReadOnly --auth

# Custom config and agent folders
npx --yes @whoz-oss/coday-web --configDir ~/.my-coday -af ./custom-agents
```

## Next Steps

Now that you know how to launch Coday, learn about [your first conversation](./first-conversation.md).

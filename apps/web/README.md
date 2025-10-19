# Coday Web

A lightweight runtime orchestrator for the Coday web interface.

## Architecture

This package is a **thin wrapper** that coordinates the client and server packages at runtime. It does not bundle or build anything - it simply resolves the installed packages and starts the server with the correct client path.

### How It Works

1. **Runtime Resolution**: When executed, `index.js` resolves the location of `@whoz-oss/coday-client` in node_modules
2. **Environment Configuration**: Sets `CODAY_CLIENT_PATH` environment variable pointing to the client's browser build
3. **Server Delegation**: Imports and executes `@whoz-oss/coday-server` which serves the client files

### Dependencies

- `@whoz-oss/coday-client` - Angular application (static files)
- `@whoz-oss/coday-server` - Express server with API endpoints

## Usage

### Via NPX (Recommended)

```bash
npx @whoz-oss/coday-web --no_auth
```

### Local Development

From the monorepo root:

```bash
pnpm web:local
```

### Installed Globally

```bash
npm install -g @whoz-oss/coday-web
coday-web --no_auth
```

## Command-Line Arguments

All arguments are passed through to the server:

- `--no_auth` - Disable authentication (use local username)
- `--local` - Use local configuration
- `--port=<number>` - Specify port (default: 3000)
- `--config_dir=<path>` - Custom config directory

## Development Notes

### No Build Step

Unlike traditional applications, this package requires **no build step**. The `index.js` file is the entry point and runs directly with Node.js.

### Package Structure

```
apps/web/
├── index.js          # Runtime launcher (executable)
├── package.json      # Package metadata and dependencies
├── project.json      # NX project configuration (minimal)
└── README.md         # This file
```

### Testing Locally

1. Ensure client and server are built:
   ```bash
   pnpm nx run client:build
   pnpm nx run server:build
   ```

2. Run the web launcher:
   ```bash
   node apps/web/index.js --no_auth
   ```

3. The launcher will:
   - Find the client build in `apps/client/dist/browser`
   - Set the environment variable
   - Start the server from `apps/server/dist/server.js`

### Publishing

The package is published as-is (no dist directory):

```bash
pnpm nx run web:nx-release-publish
```

This publishes the source directory containing:
- `index.js` (marked as executable via package.json bin field)
- `package.json` (with dependencies on client and server)

## Troubleshooting

### "Could not resolve @whoz-oss/coday-client package"

Ensure dependencies are installed:
```bash
pnpm install
```

### "Client package found but browser directory is missing"

The client package must be built and include a `browser/` directory with `index.html`. Check the client build output.

### Server fails to start

Check that:
1. The server package is installed
2. Port 3000 (or specified port) is available
3. You have necessary permissions

## Architecture Benefits

✅ **Simple**: No build complexity, just runtime coordination  
✅ **Maintainable**: Single source of truth for client and server  
✅ **Flexible**: Works with workspace protocol (dev) or published packages (prod)  
✅ **Debuggable**: Clear separation of concerns, easy to trace issues  
✅ **Standard**: Uses normal npm package resolution

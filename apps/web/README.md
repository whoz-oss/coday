# Coday Web Package

This package provides the web interface launcher for Coday, bundling both the client (Angular app) and server components into a single executable package.

## Architecture

The web package acts as a thin orchestration layer:

```
┌─────────────────────────────────────────┐
│         coday-web (index.js)            │
│  ┌───────────────────────────────────┐  │
│  │ 1. Resolve client package path   │  │
│  │ 2. Set CODAY_CLIENT_PATH env var │  │
│  │ 3. Import and run server         │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
┌──────────────┐        ┌──────────────┐
│ coday-server │        │ coday-client │
│  (Express)   │───────▶│  (Angular)   │
└──────────────┘        └──────────────┘
```

## Usage Modes

### Production Mode (Published Package)

Use the published npm package via npx:

```bash
# Using the published package
pnpm web

# Or directly with npx
npx @whoz-oss/coday-web --no_auth
```

In production mode:
- The web launcher resolves the client package from node_modules
- Server serves pre-built static files from the client package
- Single process handles both server and client

### Development Mode (Local Sources)

For development with live reload and local sources:

```bash
# Start both client and server in development mode
pnpm web:dev
```

This command:
- Starts the Angular dev server on port 4200 (with HMR)
- Starts the Express server on port 4100 (with tsx watch)
- Server proxies non-API requests to Angular dev server
- Changes to client or server code trigger automatic reloads

**How it works in dev mode:**
1. Client runs with Angular CLI dev server (localhost:4200)
2. Server runs with tsx watch (localhost:4100)
3. Server detects `BUILD_ENV=development` and proxies to Angular
4. API routes (e.g., `/api/*`) are handled by the server
5. All other routes are proxied to Angular for client-side routing

## Development Workflow

### Running Individual Services

You can also run services separately for debugging:

```bash
# Run only the client (Angular dev server)
pnpm client

# Run only the server (Express with tsx watch)
pnpm server
```

### Building for Production

The web package depends on built client and server packages:

```bash
# Build all packages
pnpm nx run-many --target=build --projects=client,server,web

# Or build everything
pnpm nx run-many --target=build --all
```

### Testing Changes

When making changes to the web launcher:

1. Modify `apps/web/index.js`
2. Test with local server: `node apps/web/index.js --no_auth`
3. Verify client resolution works correctly
4. Check that server starts and serves the client

## Environment Variables

- `CODAY_CLIENT_PATH`: Set by the web launcher to tell server where client files are
- `BUILD_ENV`: Set to `development` by server's serve target for dev mode
- `PORT`: Override default server port (default: 3000 production, 4100 dev)

## Package Dependencies

```json
{
  "@whoz-oss/coday-client": "workspace:*",
  "@whoz-oss/coday-server": "workspace:*"
}
```

These use workspace protocol in development and resolve to specific versions when published.

## Troubleshooting

### "Could not resolve @whoz-oss/coday-client package"

This means the client package isn't built or installed:

```bash
# In development
pnpm install
pnpm nx run client:build

# Or use the web:dev command which doesn't require builds
pnpm web:dev
```

### "Failed to start server"

Check that all dependencies are installed:

```bash
pnpm install
```

### Development mode not proxying correctly

Ensure both services are running:
1. Angular dev server should be on port 4200
2. Express server should be on port 4100
3. Access the app via http://localhost:4100

## Quick Reference

| Command | Mode | Ports | Use Case |
|---------|------|-------|----------|
| `pnpm web` | Production | 3000 | Testing published package |
| `pnpm web:dev` | Development | 4100→4200 | Active development |
| `pnpm client` | Development | 4200 | Client-only work |
| `pnpm server` | Development | 4100 | Server-only work |

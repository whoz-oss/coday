# Web Application

This application serves as the **server-only** component of the Coday web interface.

## Architecture

- **Server**: Express.js server (`./server/`) - handles API, WebSocket, file uploads
- **Client**: Angular application (from `../web-ng/`) - copied during build process

## Dependencies

This application **depends on** `web-ng` for the client-side code:
- The `copy:client` build step copies the compiled Angular app
- The server serves the Angular app as static files

## Build Process

```bash
# Full build (server + client)
nx run web:build

# Development with watch mode
nx run web:dev

# Production serve
nx run web:serve
```

## Build Steps

1. **`build:server`**: Compiles the Express.js server with esbuild
2. **`copy:client`**: Copies the built Angular app from `web-ng`
3. **`build`**: Finalizes the package structure

## Directory Structure

```
dist/web/
├── client/          # Angular app (from web-ng)
├── server/          # Express.js server
└── package.json     # Server dependencies
```

## Development

For development, you typically want to run both applications:

```bash
# Terminal 1: Angular dev server
nx serve web-ng

# Terminal 2: Express server with watch
nx run web:dev
```

Or use the combined build for production-like testing:

```bash
nx run web:build
nx run web:serve
```
# Coday Desktop

Electron-based desktop application for Coday that bundles the `@whoz-oss/coday-web` package.

## Overview

Coday Desktop provides a native desktop experience by:
- Bundling the Coday web interface in an Electron app
- Running the Coday server as a child process
- Providing a standalone executable for all platforms

## Development

### Prerequisites

- Node.js and pnpm installed
- Electron dependencies installed

### Running Locally

```bash
# From the project root
pnpm nx serve desktop
```

This will:
1. Build the Electron main and preload scripts
2. Start the application with Electron

### Building

```bash
# Build the application
pnpm nx build-all desktop

# Package for current platform (unpacked)
pnpm nx package desktop

# Package for distribution (installers)
pnpm nx package-dist desktop
```

## Architecture

The desktop app consists of:

1. **Main Process** (`src/main.ts`):
   - Manages the Electron application lifecycle
   - Finds an available port dynamically (from IANA dynamic port range 49152-65535)
   - Starts the Coday web server as a child process on the selected port
   - Creates and manages the browser window
   - Handles cleanup on application exit

2. **Preload Script** (`src/preload.ts`):
   - Runs in privileged context before web content loads
   - Can expose safe APIs to the renderer via contextBridge
   - Currently minimal but extensible for future needs

3. **Dependencies**:
   - `@whoz-oss/coday-web`: The bundled web interface and server
   - `electron`: Desktop application framework
   - `electron-builder`: Build and packaging tool

## Packaging

The app uses `electron-builder` for creating distributable packages:

- **macOS**: DMG and ZIP archives
- **Windows**: NSIS installer and portable executable
- **Linux**: AppImage and Debian package

Configuration is in `package.json` under the `build` key.

## Port Management

The desktop app automatically finds an available port to avoid conflicts:
- Uses IANA dynamic/private port range (49152-65535)
- Randomly selects a base port in this range
- If the port is in use, automatically tries the next port
- Both server and client are configured to use the same port

## Environment Variables

- `NODE_ENV=development`: Enables DevTools
- `PORT`: Server port (set automatically by the desktop app)

## Future Enhancements

Potential features to add:
- Menu bar integration
- System tray support
- Auto-update functionality
- Native notifications
- Custom protocol handlers
- Window state persistence

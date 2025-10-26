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
   - Comprehensive logging to file for troubleshooting
   - Intercepts reload attempts to prevent navigation errors

2. **Preload Script** (`src/preload.ts`):
   - Runs in privileged context before web content loads
   - Exposes safe APIs to the renderer via contextBridge
   - Provides storage API for persistent preferences
   - Provides logs API for accessing log files

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

## Logging

The desktop app includes comprehensive logging to help troubleshoot issues:

- **Log Location**:
  - macOS: `~/Library/Application Support/Coday/coday-desktop.log`
  - Linux: `~/.config/Coday/coday-desktop.log`
  - Windows: `%APPDATA%\Coday\coday-desktop.log`

- **What's Logged**: Startup, server output, navigation events, errors, and shutdown

See [LOGGING.md](./LOGGING.md) for detailed information.

## Window Lifecycle (macOS)

The desktop app follows standard macOS behavior for window management:
- **Closing the window** (Cmd+W or red X) hides it instead of destroying it
- **Clicking the dock icon** shows the hidden window instantly
- **Quitting the app** (Cmd+Q) properly stops the server and exits
- **Server health checks** ensure the server is running when recreating windows

This provides a native macOS experience and prevents the "blank page" issue when reopening.

## Reload Behavior

The desktop app handles page reloads (Cmd+R / F5) specially:
- Intercepts reload attempts to always reload from the root URL
- Prevents "Something went wrong" errors from appearing
- Automatically recovers from failed page loads
- Blocks navigation away from the Coday server

This ensures a smooth experience even when reloading from deep routes.

## Environment Variables

- `NODE_ENV=development`: Enables DevTools
- `PORT`: Server port (set automatically by the desktop app)

## Troubleshooting

### App Won't Start

1. Check the log file for error messages
2. Ensure Node.js is installed and accessible
3. Verify npx is available: `npx --version`

### "Something Went Wrong" Error

This should no longer occur with the reload fix. If you see this:
1. Check the logs for details
2. Try quitting and restarting the app

### Server Issues

The logs will show:
- Node.js and npx paths being used
- Server startup output
- Any server errors

Use `tail -f ~/Library/Application\ Support/Coday/coday-desktop.log` to easily access and monitor logs.

## Future Enhancements

Potential features to add:
- Menu bar integration
- System tray support
- Auto-update functionality
- Native notifications
- Custom protocol handlers
- Window state persistence

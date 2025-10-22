# Desktop App Setup

This document describes the new Coday Desktop application that was created.

## What Was Created

A new Electron-based desktop application that bundles `@whoz-oss/coday-web` and provides a native desktop experience.

### File Structure

```
apps/desktop/
├── src/
│   ├── main.ts          # Main Electron process
│   └── preload.ts       # Preload script for security context
├── assets/              # App icons and resources
├── project.json         # NX project configuration
├── package.json         # Package metadata and electron-builder config
├── tsconfig.app.json    # TypeScript configuration
├── tsconfig.json        # TypeScript root config
├── eslint.config.js     # ESLint configuration
├── README.md            # User documentation
└── SETUP.md             # This file

```

## Key Features

1. **Bundled Web Server**: Automatically starts `@whoz-oss/coday-web` as a child process
2. **Native Window**: Electron window that loads the local web interface
3. **Cross-Platform**: Supports macOS, Windows, and Linux
4. **Security**: Uses context isolation and preload scripts for security
5. **Auto-Cleanup**: Properly shuts down the server when the app closes

## Configuration Changes

### Updated Files

1. **nx.json**: Added `desktop` to the release projects list
2. **package.json**: 
   - Added `pnpm desktop` script
   - Added `electron` and `electron-builder` as dev dependencies
3. **README.md**: Updated to mention the desktop app

## Running the Desktop App

### Development

```bash
# From the project root
pnpm desktop
```

This will:
1. Build the Electron main and preload scripts using esbuild
2. Launch the app with Electron

### Building for Distribution

```bash
# Build only (no package)
pnpm nx build-all desktop

# Package for current platform (unpacked)
pnpm nx package desktop

# Create installers/packages for distribution
pnpm nx package-dist desktop
```

## Technical Details

### Main Process (main.ts)

- Resolves the `@whoz-oss/coday-web` package location
- Spawns the web server as a child process with `--no_auth` flag
- Creates an Electron window pointing to `http://localhost:3000`
- Handles cleanup when the app is closed

### Preload Script (preload.ts)

- Runs in a privileged context before web content loads
- Exposes safe APIs via `contextBridge`
- Currently minimal but extensible

### Build Configuration

- Uses `@nx/esbuild` for building TypeScript to CommonJS
- Separate build targets for main and preload scripts
- `electron-builder` for packaging and distribution

## Dependencies

The desktop app depends on:
- `@whoz-oss/coday-web`: The bundled web interface
- `electron`: Desktop application framework
- `electron-builder`: Build and packaging tool

## Next Steps

To enhance the desktop app, consider:

1. **App Icon**: Add proper icons to `apps/desktop/assets/`
2. **Menu Bar**: Add custom application menu
3. **Auto-Update**: Implement auto-update functionality
4. **System Tray**: Add system tray integration
5. **Preferences**: Native preferences window
6. **Window State**: Persist window size and position
7. **Native Notifications**: Use Electron's notification API

## Testing

Before distribution:

1. Test on all target platforms (macOS, Windows, Linux)
2. Verify the server starts correctly
3. Check that cleanup works properly on app exit
4. Test the packaged application, not just development mode

## Distribution

The app can be distributed via:
- Direct download (DMG, EXE, AppImage)
- App stores (Mac App Store, Microsoft Store, Snap Store)
- Auto-update server (using electron-updater)

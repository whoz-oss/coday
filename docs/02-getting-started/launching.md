# Launching Coday

Coday provides a modern web interface for interacting with AI agents. This is the primary way to use Coday.

## Standard Launch

From any directory:

```bash
npx --yes @whoz-oss/coday-web
```

This will:
1. Start the Coday server
2. Open your browser at `http://localhost:3000`
3. Show the project selection screen (or setup wizard on first run)

### Command Line Options

```bash
npx --yes @whoz-oss/coday-web [options]

Options:
  --debug              Enable debug logging
  --auth               Enable authentication (requires auth proxy with x-forwarded-email header)
  --port <number>      Server port (default: 3000)
```

## Web Interface

The web interface is the default and recommended way to use Coday.

### Web Interface Features

- Rich text formatting and markdown rendering
- Image upload and display
- Voice input and speech synthesis
- Theme selection (light/dark/system)
- Persistent preferences

## Desktop Application

For a native desktop experience, you can use the Electron-based desktop app:

```bash
# Requires cloning the repository first
git clone https://github.com/whoz-oss/coday.git
cd coday
pnpm install
pnpm desktop
```

The desktop app provides the same web interface in a native window.

## Selecting a Project

When launching Coday, you'll see the project selection screen where you can:

- **Select an existing project**: Click on a project from the list
- **Create a new project**: Click "New Project" and provide the project path and name

Once a project is selected, you'll enter the conversation interface where you can start interacting with agents.

## Development Mode

For contributors working on Coday itself:

```bash
# Clone repository
git clone https://github.com/whoz-oss/coday.git
cd coday
pnpm install

# Run with live reload
pnpm web:dev
```

This starts both the Angular dev server and Express backend with automatic reloading.

## Next Steps

Now that you know how to launch Coday, learn about [your first conversation](./first-conversation.md).

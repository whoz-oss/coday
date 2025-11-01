# Installation

This guide walks you through installing Coday and setting up your environment.

## Prerequisites

- **Node.js**: Version 22 or higher
- **npm**: Comes with Node.js (no additional installation needed)

## Installation

### Standard Installation (Recommended)

No installation needed! Simply run:

```bash
npx --yes @whoz-oss/coday-web
```

This will:
1. Download the latest version (if needed)
2. Start the web server
3. Open your browser automatically

The first time you run it, you'll need to:

### 1. Configure AI Provider

You'll need an API key from at least one AI provider (OpenAI, Anthropic, etc.). Configuration can be done through:
- The web interface (recommended for first-time setup)
- Configuration commands (see [User Configuration](../04-configuration/user-config.md))

The setup wizard will guide you through this on first launch.

### 2. Select or Create a Project

Coday works with existing code projects. You'll need to:
- Point Coday to an existing project directory, or
- Create a new project directory

The setup wizard will guide you through this process.

## Alternative: Development Installation

For contributors or those who want to run from source:

```bash
# Clone the repository
git clone https://github.com/whoz-oss/coday.git
cd coday

# Install dependencies
pnpm install

# Run in development mode
pnpm web:dev
```

## Verify Installation

To verify everything is working:

```bash
npx --yes @whoz-oss/coday-web
```

If your browser opens with the Coday interface, you're ready to proceed to [Launching Coday](./launching.md).

## Troubleshooting

If you encounter issues during installation, see the [Troubleshooting](../troubleshooting.md) guide.

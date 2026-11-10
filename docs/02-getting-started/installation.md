# Installation

This guide walks you through installing and launching Coday for the first time.

## Prerequisites

- **Node.js**: Version 22 or higher
- **npm**: Comes with Node.js (no additional installation needed)
- **ripgrep**: Fast text search utility (`brew install ripgrep` on macOS)
- **AI Provider API Key**: At least one of:
  - `ANTHROPIC_API_KEY` environment variable
  - `OPENAI_API_KEY` environment variable

## Setting Up API Keys

The recommended approach is to set environment variables before launching Coday:

```bash
  # For Anthropic (Claude)
  export ANTHROPIC_API_KEY="your-api-key-here"

  # For OpenAI (GPT)
  export OPENAI_API_KEY="your-api-key-here"
```

To make these permanent, add them to your shell configuration file (`~/.zshrc`, `~/.bashrc`, etc.):

```bash
  echo 'export ANTHROPIC_API_KEY="your-api-key-here"' >> ~/.zshrc
  source ~/.zshrc
```

**Alternative**: You can also configure API keys through the Coday web interface after launch (see [User Configuration](../04-configuration/user-config.md)).

## Launching Coday

No installation needed! Simply run from any directory:

```bash
  npx --yes @whoz-oss/coday-web
```

This command will:
1. Download the latest version (if needed)
2. Start the web server on port 3000 (the PROD port block 3000-3009: Express on 3000, AgentOS 3005, Factory 3006, embedded Neo4j 3007)
3. Display the URL in your terminal

**Open your browser** and navigate to the URL shown in the terminal (usually `http://localhost:3000`). If a port of the block is taken, Coday refuses to start and names the process holding it: stop it and start again. The ports are always the same on purpose (the redirect URIs registered at OAuth providers are tied to them); to use other ports anyway, set `PORT=<n>` or `CODAY_PORT_BASE=<n>` (then the redirect URIs of your providers must be registered for them).

You can have an alias too in your shell configuration

```bash
  # in ~/.zshrc or other
  alias coday='npx --yes @whoz-oss/coday-web'
```

## First Launch

When you first open Coday in your browser, the interface will be ready to use. Coday automatically creates a project based on the directory where you ran the command.

You can optionally:
- Create additional projects from the interface
- Select different project directories
- Configure custom agents and settings

See [First Conversation](./first-conversation.md) to start using Coday.

## Alternative: Development Installation

For contributors or those who want to run from source:

```bash
  # Clone the repository
  git clone https://github.com/whoz-oss/coday.git
  cd coday
  
  # Install dependencies
  pnpm install
  
  # Run in development mode (open Express on http://localhost:4000; Angular client 4001 and AgentOS 4005 are internal)
  pnpm web:dev
```

## Next Steps

- **[First Conversation](./first-conversation.md)** - Start your first conversation with an agent
- **[Interface Basics](../03-using-coday/interface-basics.md)** - Learn the web interface
- **[Configuration](../04-configuration/configuration-levels.md)** - Customize Coday for your needs

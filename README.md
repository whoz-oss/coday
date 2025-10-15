<div align="center">

<img src="apps/client/public/CODAY-Logo.png" alt="Coday Logo" width="100"/>

</div>

# Coday

This project is an agentic framework leveraging existing LLMs to let you define your agents on your projects.

## Features

- **Multi-Agent Support**: Define and configure custom AI agents for your projects
- **Multiple LLM Providers**: Support for OpenAI, Anthropic, and other AI providers
- **Web & Terminal Interfaces**: Choose between browser-based or command-line interaction
- **Project-Based Configuration**: Scope agents and settings to specific projects
- **Webhook Integration**: Trigger AI agent interactions programmatically from external systems
- **Memory Management**: Persistent conversation history and context
- **Tool Integration**: Extensible tool system for file operations, code analysis, and more

There are several ways to use Coday:

- as a dev-dep of a code project: uses the published npm package to run locally
- as a contributor through a clone of this repo
- as a non-dev through a deployed instance (details coming soon...⏳)

## Use as dev-dep

When in the context of a code project, use the npm package to run locally through the web interface.

1. Check dependencies
    - node.js (v14.x or later)
    - npm (v6.x or later)
    - tsx (v4)
    - [ripgrep](https://github.com/BurntSushi/ripgrep) (important for file search)

2. Add npm package

    ```sh
    pnpm add --dev @whoz-oss/coday-web@latest
    ```

    ```sh
    npm install @whoz-oss/coday-web@latest --save-dev
    ```

2. Add start script in `package.json`

    ```json
    {
      "scripts": {
        "coday": "node ./node_modules/@whoz-oss/coday-web/server/server.js --no_auth"
      }
    }
    ```
3. Add the run config in your IDE (up to you)
4. Run the script, open `http://localhost:300x` (link in terminal) and setup the first Coday project

## Use through repo clone

1. Check dependencies
   - Node.js (v14.x or later)
   - npm (v6.x or later)
   - [ripgrep](https://github.com/BurntSushi/ripgrep)
   - git
   - pnpm
2. Clone the repo
   ```sh
   # through ssh
   git clone git@github.com:whoz-oss/coday.git

   # through http
   git clone https://github.com/whoz-oss/coday.git
    ```
3. Install packages

    ```sh
    pnpm install --frozen-lockfile
    ```
4. Run one of the targets:
   - in the terminal:
   ```sh
   pnpm run nx reset && pnpm start
   ```
   - in the browser:
   ```sh
   pnpm run nx reset && pnpm web --no_auth
   ```
5. Setup the first coday project


## Coday project setup

Coday is project-agnostic, when starting it, you have to select an existing project or create one.

When starting for the first time, you will be asked to define the first project with :
   - the name of the project
   - the complete path to the directory containing the project

Then you have to define the first AI provider:

   - `config ai`: shows available AI configuration commands
   - `config ai list`: list the current AI providers and configs
   - `config ai add`: add a new AI provider (user level by default)
   - `reset` to reload cleanly the Coday config

[More on AI configuration](doc/AI_CONFIGURATION.md)

From there, you can select the project and ask `Hello` to get an answer.

## 🛟 HELP ? 🛑 EXIT ?

To get the list of available commands, type nothing or `h` or `help`. Usually, complex commands repeat the pattern, ex: `config` shows the help text for the config command.

To exit in the terminal, type ... `exit`. Or `Ctrl + C`. `:q` does not work 😝.

On the browser, just close the tab.

## Setting integrations

Configure integrations via `config integration`.

[More on integrations](doc/INTEGRATIONS.md)

## Configuration

Directly edit `coday.yaml` for all project-specific configurations.

See Coday's own `coday.yaml` file for reference, examples and documentation.

## Documentation

- [AI Configuration](doc/AI_CONFIGURATION.md) - Configure AI providers and models
- [Integrations](doc/INTEGRATIONS.md) - Set up external service integrations
- [Webhooks](doc/WEBHOOKS.md) - Programmatic API for triggering AI agent interactions
- [Project Configuration](doc/PROJECT_CONFIGURATION.md) - Project-specific settings and options
- [Architecture](doc/ARCHITECTURE.md) - System architecture and request flow
- [Handler Design](doc/HANDLER_DESIGN.md) - Command handler patterns and implementation

## Development

### Contributing

See [Development Workflow](doc/DEV_WORKFLOW.md) for contribution guidelines.

### Release Process

Releases are fully automated via GitHub Actions. Every push to the master branch triggers:

1. Version bumping based on conventional commits
2. Changelog generation
3. npm package publishing
4. GitHub release creation

For details, see [Automated Release Process](doc/AUTOMATED_RELEASE.md).

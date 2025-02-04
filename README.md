# Coday

This project is an AI LLM (default being Openai/ChatGPT) wrapper to allow natural language interactions with a
code-related project, and (semi-)autonomous task handling.

## Installation

Basics steps:

1. Clone the repository.
2. Run `yarn install` to install dependencies.
3. Execute `start-in-terminal.sh` to start.

[More on installation here](doc/INSTALLATION.md)

## Usage

Run the application using:

```sh
yarn start
```

...and define your first project following on-screen instructions.

[More on usage here](doc/USAGE.md)

## Electron Application

### Running the Electron App
To run the Electron app in development mode:

```bash
# First, build the web application
yarn web

# Then, run the Electron app
yarn electron:dev
```

### Building Electron Packages
To create distributable packages for different platforms:

```bash
# Build the web application first
yarn web

# Build Electron packages
yarn electron:build
```

The packaged applications will be available in `dist/electron` directory.

## Setting integrations

Configure integrations via `config edit-integration`.

[More on integrations](doc/INTEGRATIONS.md)

## Configuration

Directly edit `coday.yaml` for project-specific configurations.

[Detailed configuration documentation can be found here](doc/PROJECT_CONFIGURATION.md)
# Coday

This project is an AI LLM (default being Openai/ChatGPT) wrapper to allow natural language interactions with a code-related project, and (semi-)autonomous task handling.

## Installation

Basically: git clone, yarn install, start-in-terminal.sh.

[More on installation here](./INSTALLATION.md)


## Usage

Just run 
```sh
yarn start
```
...and define your first project.

[More on usage here](./USAGE.md)


## Setting integrations

Add/edit integrations with `config edit-integration`.

[More on integrations](./INTEGRATIONS.md)


## Configuration

- `~/.coday/config.json` should not be edited directly, [see usage](./USAGE.md) and `config` command.
- `coday.yaml` must be edited directly at will !!! 

[More on project configuration here](./PROJECT_CONFIGURATION.md)

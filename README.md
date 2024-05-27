# Coday

## Description

This project is an AI LLM (default being Openai/ChatGPT) wrapper to allow natural and semi-autonomous task handling by:
- exposing a project's files (read, write, search, list)
- interaction along a thread, keeping context between requests
- higher level instructions by combining several low-level ones
- [future] automatically split a work assignment into tasks and sub-tasks
- [future] expose a selection of APIs: Jira, Gitlab, git

The core principle is to exhaust a FILO queue of commands, the first one being the request typed by the user. Each command being consumed by the first accepting handler found. Handlers can push several commands in the queue, decomposing high-level instructions into lower-level ones.

The final aim is to let Coday read a user story and do the heavy work of splitting it, branching, writing code in between workflow steps (compile, run tests, etc...).

## Roadmap

A roadmap is a joke as of writing 🤣, but the following points need to be addressed:
- heuristic to decide when to split a task (number of impacted files ?) and how to proceed (identify tasks that are deploy-ready vs sub-tasks that just compile?) ?

## Installation
Ensure you have the following installed:

- Node.js (v14.x or later)
- npm (v6.x or later)
- git
- yarn

1. Clone the repository

    ```sh
   git clone git@gitlab.com:vincent.audibert/coday.git
   ```

2. Update/install dependencies
   ```sh
    yarn install
    ```
## Usage

For auto-updates and smooth-as-possible start (might require `chmod +x ./start-in-terminal.sh`). This will check the repo and the dependencies before starting Coday.
   ```
      ./start-in-terminal.sh 
   ```

Otherwise directly run the start point
   ```sh
      npx ts-node index.ts
   ```

## Configuration

### Coday

Under `~/.coday/config.json` is the list of the known projects Coday is configured on, as well as the last one visited (for automatic selection at start up).

To change the selected project `config select-projet` or `reset` (more brutal), to add one `config add-project`.

### by project

For each project, Coday expects (or writes a dummy) `./coday.json` to exist, where are:

- `description` of the project: dump all that is useful to Coday, architecture, tech stack, preferences, domain, **all that is specific and necessary**. This is added at each thread creation.
- `scripts`: collection of project-related commands **Coday can run autonomously** eg: build, lint, test. No arguments possible for now, make sure they work and are safe. Each entry should be `[unique name of the script]: { description: "...", command: "..."}`



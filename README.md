# Coday

## Description

This project is an AI LLM (default being Openai/ChatGPT) wrapper to allow natural and semi-autonomous task handling by:
- exposing a project's files (read, write, search, list)
- higher level instructions by combining several low-level ones
- [future] automatically split a work assignment into tasks and sub-tasks
- [future] expose a selection of APIs: Jira, Gitlab, git

The final aim is to let Coday read a user story and do the heavy work of splitting it, branching, writing code in between workflow steps (compile, run tests, etc...).

## Roadmap

A roadmap is a joke as of writing 🤣, but the following points need to be addressed:
- heuristic to decide when to split a task (number of impacted files ?) and how to proceed (identify tasks that are deploy-ready vs sub-tasks that just compile?) ?
- project discovery: how the LLM can understand a new project ? It needs to dig in source code and get an understanding
- use project tools: how to compile a project ? how to update its dependencies ? Should the LLM guess the commandlines to run (dangerous ?) or should the project be "Coday-ready" by exposing directly a `build-frontend.sh` script ?

## Installation
Ensure you have the following installed:

- Node.js (v14.x or later)
- npm (v6.x or later)
- git
- yarn

1. Clone the repository
̏̏    
    ```TODO once shared 🤡```

2. Update/install dependencies
   ```sh
    yarn install
    ```
## Usage

Run the start point
   ```sh
      npx ts-node index.ts
   ```

## Contact

For further information, you can reach out to us at support@whoz.com.

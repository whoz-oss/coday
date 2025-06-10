<div align="center">

<img src="apps/web/client/static/CODAY-Logo.png" alt="Coday Logo" width="100"/>

</div>

# Coday

This project is an agentic framework leveraging existing LLMs to let you define your agents on your projects.

There are several ways to use Coday:

- as a dev-dep of a code project: uses the published npm package to run locally
- as a contributor through a clone of this repo
- as a non-dev through a deployed instance (details coming soon...⏳)

## Use as dev-dep

When in the context of a code project, use the npm package to run locally through the web interface.

1. Check dependencies
    - node.js (v14.x or later)
    - npm (v6.x or later) 
    - [ripgrep](https://github.com/BurntSushi/ripgrep) (important for file search)

2. Add npm package

    ```sh
    yarn add --dev @biznet.io/coday-web@latest
    ```
    
    ```sh
    npm install @biznet.io/coday-web@latest --save-dev
    ```

2. Add start script in `package.json`

    ```json
    {
      "scripts": {
        "coday": "node ./node_modules/@biznet.io/coday-web/server/server.js --no_auth"
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
   - yarn 
2. Clone the repo
   ```sh
   # through ssh 
   git clone git@gitlab.com:biznet.io/coday.git
   
   # through http
   git clone https://gitlab.com/biznet.io/coday.git
    ```
3. Install packages

    ```sh
    yarn install
    ```
4. Run one of the targets:
   - in the terminal: 
   ```sh
   yarn run nx reset && yarn start
   ``` 
   - in the browser: 
   ```sh
   yarn run nx reset && yarn web --no_auth
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

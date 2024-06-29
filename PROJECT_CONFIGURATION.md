# PROJECT CONFIGURATION

The project configuration data is split between:


## Local `.coday/config.json` 

Located by default in the user home directory: lists projects, paths and integration sensitive data (apikeys). 

🚨 This file should **not be shared nor commited nor exposed** as it can contain sensitive data. 🚨


## Project-wide `coday.yaml`

This file is **70% of the value Coday** can bring to the project as it structures what an LLM should know of the project to contribute efficiently to it 🚀. It should be near-instantly transferable to any other framework about agentic workflows should you drop Coday 😓.

As its structure is evolving because it is so core, [see Coday's yaml file](./coday.yaml) as an example and documentation.

It can be located anywhere under the project's root directory (but not outside through symlinks!). 

If you fill it well, it should basically serve as documentation for newcomers to the project as containing a detailed description and rules, useful project-specific commands, workflows and intervening roles. 



## 👟 START

The `package.json` defines the standard start script that will run in terminal:
```sh
yarn start
```

This command can accept several parameters (by default without = start interactive in terminal):

- `--project=[project name]` to directly select a project instead of relying on the last visited one.
- `--oneshot=[true|false]`: in oneshot, Coday will answer the prompt(s) and not tolerate any interaction in request completion. Use with `--project` argument or risk working on the wrong project.
- `--prompt="[your request or command here]"` starts Coday with this request instead of the interactive input. Arguments can be chained: `--prompt="do foo" --prompt="then bar" --prompt="finally baz"` and will be completed sequentially.


## 🐣 FIRST RUN

On first run, Coday will create its `.coday/config.json` file in the user home directory, to store in it the projects path and specific configuration tied to the user.

Then it will ask to define a project by its name and its absolute path. This is the equivalent of running command `config add-project` and will select the newly created project.


## 🛟 HELP ? 🛑 EXIT ?

To get the list of available commands, type nothing or `h` or `help`. Usually, complex commands repeat the pattern, ex: `config` shows the help text for the config command.

To exit, type ... `exit`. Or `Ctrl + C`.

`:q` does not work 😝.

## 💼 ON A PROJECT

When Coday runs on a project (and it always does), it will:

- look for `coday.yaml` from the project root. This config file can be nested somewhere or at the project root.
- create `coday.yaml` at project root if not found
- load it, leading to:
  - path restrictions: only files under the project root are visible, searchable and editable
  - only the integrations declared on the project are available (defined by and per user in `.coday/config.json`)
  - only the assistants declared on the project can "see" each other. Other assistants can be called directly but are not known to Coday and the assistant "team".
  - scripts of the project exposed to the assistants

## 🔀 CHANGE PROJECT

To change from a project to another, use `config select-project`, that will list the available projects for selection (or `new` to add one).
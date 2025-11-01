## 👟 START OPTIONS

The `package.json` defines the standard start script that will run in terminal:
  ```sh
  pnpm start
  ```
or for web local interface through the browser
  ```sh
  pnpm web --no_auth
  ```

This command can accept several parameters (by default without = start interactive in terminal):

- `--project=[project name]` to directly select a project instead of relying on the last visited one.
- `--local` selects as Coday project name the current folder name. Useful when having various projects configured in Coday and need to start an instance from one IDE.
- `--no_auth` specifically for local use. If ommitted, will expect a specific header to be applied by an auth proxy.
- `--oneshot=[true|false]`: in oneshot, Coday will answer the prompt(s) and not tolerate any interaction in request completion. Use with `--project` argument or risk working on the wrong project.
- `--prompt="[your request or command here]"` starts Coday with this request instead of the interactive input. Arguments can be chained: `--prompt="do foo" --prompt="then bar" --prompt="finally baz"` and will be completed sequentially.


## 💼 ON A PROJECT

When Coday runs on a project (and it always does), it will:

- look for `coday.yaml` from the project root. This config file can be nested somewhere or at the project root.
- create `coday.yaml` at project root if not found
- load it, leading to:
  - path restrictions: only files under the project root are visible, searchable and editable
  - only the integrations declared on the project are available
  - only the assistants declared on the project can "see" each other.
  - scripts of the project exposed to the assistants

## 🔀 CHANGE PROJECT

To change from a project to another, use `config select-project`, that will list the available projects for selection (or `new` to add one).

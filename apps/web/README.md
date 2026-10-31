# Coday Web Package

`@whoz-oss/coday-web` is the launcher of the Coday web interface: it serves the Angular client, the Factory cockpit and
the Express server, and runs AgentOS and the Factory next to them.

## First run

```bash
npx @whoz-oss/coday-web
```

The first time, the browser opens on **`/welcome`** (`http://localhost:3000/welcome`), a four-step wizard:

1. **Your email.** It becomes your user ID and the AgentOS administrator. AgentOS and Factory JARs start downloading in
   the background as soon as the page is open.
2. **Providers.** Anthropic, OpenAI and/or Requesty, each with an API key. With several, drag the cards (or use the
   arrows) to rank them: rank 1 is preferred. At least one is required.
3. **Files folder.** Pick the folder your agent reads and writes (default `~/Coday`, created when missing).
4. **Checklist.** Download %, Java, AgentOS, providers, models, your personal namespace, the Coday agent, then
   **Start with Coday**.

**Java 25 or newer is required** (AgentOS and Factory run on the JVM). When it is missing the wizard says so and shows the
install command for your system, then continues by itself.

Once done, `<configDir>/web-setup.json` holds `{ identity, setupDone }` (never an API key: the keys go to AgentOS only).
To run the wizard again, stop Coday and delete `web-setup.json` **and** `<configDir>/agentos/data` (AgentOS fixes its
administrator at its first boot).

## How it runs

```
  interactive terminal                          no TTY / --managed / CODAY_NO_NX=1
  ────────────────────                          ───────────────────────────────────
  coday-web (index.js)                          coday-web (index.js)
    └─ writes <configDir>/runtime/{nx,project}.json
    └─ nx run-many -t server agentos factory    └─ one process: Express + AgentOS + Factory
         provision ─┬─ server    (pane 1)           (what the desktop apps use; prints
                    ├─ agentos   (pane 2)            `Server is running on http://localhost:PORT`)
                    └─ factory   (pane 3)
```

- **Nx mode** (default in a terminal). The launcher generates a tiny Nx workspace under `<configDir>/runtime/`
  (no daemon, no cloud, no cache) and runs the bundled `nx`. `provision` is a quick task (profile, secrets, Java check,
  ports → `runtime/state.json`); `server`, `agentos` and `factory` are continuous tasks, each with its own pane. `agentos`
  prints *Waiting for first-run setup → http://localhost:3000/welcome* until you gave your email; `factory` waits for
  AgentOS to be healthy.
- **Managed mode** (no TTY, `--managed`, or `CODAY_NO_NX=1`). Same code, one process, no Nx. The desktop apps spawn
  `coday-web` without a terminal and keep working unchanged.

All the logic lives in the server package (`apps/server/src/lib/runtime`, `lib/setup`); the Nx workspace is only a
manifest calling `coday-server provision|agentos|factory|server`.

### Logs

Each process writes to its own file, in addition to its pane / the console:

```
<configDir>/logs/server.log
<configDir>/logs/agentos.log
<configDir>/logs/factory.log
```

`configDir` defaults to `~/.coday` (`--configDir <dir>` to change it).

### Security in laptop mode

Auth mode trusts identity headers, so every listener is bound to loopback: Express `127.0.0.1`, AgentOS
`--server.address=127.0.0.1`, Factory `FACTORY_BIND_HOST=127.0.0.1`. AgentOS and Factory run with
`*_SECURITY_MODE=auth`. Secrets (`FACTORY_FAKE_IDP_SECRET`, AgentOS and Factory encryption key/salt) are generated once into
`<configDir>/runtime/secrets.json` (mode `0600`). An install that already holds unencrypted data keeps `NONE` for that
service and logs a warning (encrypting existing data is a manual migration).

The first-run setup API (`/api/setup/*`: folder browser, apply) only answers loopback callers and is disabled once the
setup is done. Deployments (`--auth`), an external AgentOS (`AGENTOS_URL`), an imposed `AGENTOS_EXTERNAL_USERID` and
existing installs without a wizard identity keep their previous behaviour: no wizard, no redirect.

## Environment variables and options

| Name | Effect |
|---|---|
| `--configDir <dir>` | Config directory (default `~/.coday`) |
| `--host <addr>` / `CODAY_HOST` | Interface Express listens on (default `127.0.0.1`; `0.0.0.0` with `--auth`) |
| `--managed` / `CODAY_NO_NX=1` | Force the single-process mode (no Nx) |
| `PORT` | Express port (strict when set; default 3000, 4100 in dev) |
| `AGENTOS_URL`, `FACTORY_URL` | Use an external AgentOS / Factory instead of managed ones |
| `AGENTOS_EXTERNAL_USERID` | Impose the identity forwarded to AgentOS (disables the wizard) |
| `CODAY_AGENTOS_VERSION`, `CODAY_FACTORY_VERSION` | JAR version to run (`dev` = JARs placed by hand in `<configDir>/{agentos,factory}`) |
| `CODAY_DEFAULT_FILES_DIR` | Folder proposed by the wizard instead of `~/Coday` |
| `CODAY_CLIENT_PATH`, `CODAY_FACTORY_COCKPIT_PATH` | Set by the launcher: where the static apps are |
| `BUILD_ENV=development` | Dev mode (proxy to the Angular dev servers) |

## Testing the first run before shipping: `web:first-run`

```bash
pnpm nx run web:first-run                 # Nx TUI, as an npx user sees it
pnpm nx run web:first-run -- --managed    # single process, plain console
pnpm nx run web:first-run -- --port 3100  # pick the port
```

The target builds the client, factory cockpit, server and web packages plus the AgentOS and Factory boot JARs, then
`scripts/first-run.ts`:

1. `pnpm pack`s the four npm packages (client, factory-cockpit, server, web),
2. `npm install`s the tarballs into a **fresh temp directory** (what `npx` does, minus the registry),
3. places the locally built JARs in a **fresh throwaway `--configDir`** using the `dev` version layout
   (`<configDir>/agentos/agentos-service-dev.jar`, plugins in `plugins/`; same for `factory`),
4. runs the installed `coday-web --configDir <tmp>` with `CODAY_AGENTOS_VERSION=dev CODAY_FACTORY_VERSION=dev`.

It prints every path (temp dir, install dir, configDir, logs, files folder). Your `~/.coday` and `~/Coday` are never
touched, and nothing is published. `--prepare-only` stops before starting the server and prints the paths as JSON;
`--clean` deletes the temp directory when the server stops.

Automated version: `pnpm nx run client-e2e:first-run-e2e` drives the wizard with Playwright against a real AgentOS
(dummy keys, no LLM call) and checks the providers, models, personal namespace, Coday agent and shared folder through
the AgentOS REST API. It skips itself, with a reason, when Java 25 or the built JARs are missing.

## Development (monorepo)

```bash
pnpm web:serve:dev      # nx run web:serve:dev: client, factory-cockpit, server, AgentOS and Factory, one pane each
pnpm web:dev            # lighter: client + server only (no AgentOS/Factory; set AGENTOS_URL to use your own)
pnpm client             # Angular dev server only (4200)
pnpm server             # Express with tsx watch only (4100)
```

`web:serve:dev` is a set of continuous Nx tasks: `client:serve` (4200), `factory-cockpit:serve` (4300),
`web:dev-server` (Express, 4100), `web:dev-agentos` (`agentos-service:bootRun`, 8124) and `web:dev-factory`
(`factory-service:bootRun`, 8141). The dev server uses the external-service mode (`AGENTOS_URL`/`FACTORY_URL`), so the
wizard is not shown: use `web:first-run` to test it.

### Building and testing

```bash
pnpm nx run-many -t build -p client,factory-cockpit,server,web
pnpm nx run-many -t lint test -p server,web,client,agentos-ui,scripts
```

## Package dependencies

```json
{
  "@whoz-oss/coday-client": "workspace:*",
  "@whoz-oss/coday-factory-cockpit": "workspace:*",
  "@whoz-oss/coday-server": "workspace:*",
  "nx": "catalog:dev"
}
```

`nx` is a runtime dependency here (it is the process manager of the interactive mode), which makes the package larger
than the former thin launcher. Versions of the `platform:npm` packages move in lockstep: never edit them by hand.

## Troubleshooting

- **"Could not resolve @whoz-oss/coday-client package"**: the client is not built or installed. `pnpm install` and
  `pnpm nx run client:build`, or use `pnpm web:serve:dev`.
- **The Nx panes do not show / garbled output**: use `--managed` (or `CODAY_NO_NX=1`) for plain console output.
- **"Waiting for first-run setup" never ends**: open the URL printed in the pane and answer the wizard.
- **Java missing or too old**: install Java 25 (the wizard shows the command), the page continues by itself.
- **Port already in use**: `PORT=3100 npx @whoz-oss/coday-web`.

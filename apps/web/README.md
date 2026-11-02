# Coday Web Package

`@whoz-oss/coday-web` is the launcher of the Coday web interface: it serves the Angular client, the Factory cockpit and
the Express server, and runs AgentOS and the Factory next to them.

## First run

```bash
npx @whoz-oss/coday-web
```

In a terminal (Nx mode, see below) the browser opens by itself. On a **first run** it opens the wizard immediately,
on **`/welcome`** (`http://localhost:3000/welcome`): the wizard only talks to Coday's own server, so it does not wait
for AgentOS. Otherwise (setup done) it opens `/` **once AgentOS is healthy**, because the app calls AgentOS straight
away and would show a blank page until then; if AgentOS never becomes healthy (about 3 minutes: Java missing,
download failed...) nothing is opened and the `server` pane prints the URL to open yourself. Pass `--no-open` or set
`CODAY_NO_OPEN=1` to keep it closed. Without a terminal (`--managed`, desktop apps) nothing is opened: open the URL
printed as `Server is running on http://localhost:PORT` yourself. The wizard is a four-step flow:

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
To run the wizard again on a real install, stop Coday and delete `web-setup.json` **and** `<configDir>/agentos/data`
(AgentOS fixes its administrator at its first boot). For the local stack of the monorepo, use `web:local -- --fresh`
instead (see below).

The command is always the same, `npx @whoz-oss/coday-web`: the wizard appears only while the setup is not done,
otherwise the app opens directly.

## How it runs

```
  interactive terminal                          no TTY / --managed / CODAY_NO_NX=1
  ────────────────────                          ───────────────────────────────────
  coday-web (index.js)                          coday-web (index.js)
    └─ writes <configDir>/runtime/{nx,project}.json
    └─ nx run coday:status                     └─ one process: Express + AgentOS + Factory
         provision ─┬─ status   (entry task)        (what the desktop apps use; prints
                    │    ├─ server   (pinned next    `Server is running on http://localhost:PORT`)
                    │    │            to status,
                    │    │            opens the browser)
                    │    ├─ agentos  (own pane)
                    │    └─ factory  (own pane)
```

- **Nx mode** (default in a terminal). The launcher generates a tiny Nx workspace under `<configDir>/runtime/`
  (no daemon, no cloud, no cache) and runs the bundled `nx`. `provision` is a quick task (profile, secrets, Java check,
  ports → `runtime/state.json`); `status`, `server`, `agentos` and `factory` are continuous tasks, each with its own
  pane. The launcher runs ONE target, `nx run coday:status`, and `status` depends on the three services, so Nx starts
  them for it. `agentos` prints *Waiting for first-run setup → http://localhost:3000/welcome* until you gave your email;
  `factory` waits for AgentOS to be healthy. Once Express listens, the `server` task opens the browser (see
  *First run* for when); it never does when `--auth`, a non-loopback `--host`, `CI`, `--no-open` or `CODAY_NO_OPEN` say
  so. The wait for AgentOS runs beside the server and never delays it or its shutdown. For an external AgentOS
  (`AGENTOS_URL`) it probes that URL's `/management/health`, which is what the proxy targets.
- **The `status` pane.** The Nx TUI has no status bar, so a small dedicated task reads `runtime/state.json` and probes
  the health URLs every second. It prints one line per meaningful change only: `agentos download 42%` (one line per
  10%), `java ok` or the install hint, `agentos healthy :8124`, `factory healthy :8141`, `agentos external <origin>`
  for an `AGENTOS_URL`/`FACTORY_URL`, and `Open -> http://localhost:3000/welcome` on a first run (`/` once the setup is
  done; then the line says `(once agentos is healthy)` and is repeated without that note when it is: same rule as
  the browser). In the
  TUI, select a pane and pin it next to another one to keep the download and the URL on screen while you read the
  logs of the others; press `?` for the key help of the Nx TUI. At startup `status` is pinned next to `server`: Nx
  pins the initiating task and its first continuous dependency, but only for a single-target run (`nx run`), which is
  why the launcher does not use `run-many`. That behaviour was derived from reading the Nx 23 sources (the native
  TUI rendering of pinned panes could not be inspected): confirm it visually.
  Because `status` depends on the services, none of them may exit while it runs (Nx would report an exited
  dependency as crashed, and a failed one would skip `status`): a service that cannot start (no Java, download
  failed, port taken) or that is external (`AGENTOS_URL`/`FACTORY_URL`) prints why and stays open until Ctrl+C.
- **Managed mode** (no TTY, `--managed`, or `CODAY_NO_NX=1`). Same code, one process, no Nx. The desktop apps spawn
  `coday-web` without a terminal and keep working unchanged.

All the logic lives in the server package (`apps/server/src/lib/runtime`, `lib/setup`); the Nx workspace is only a
manifest calling `coday-server provision|agentos|factory|server|status`.

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
| `--no-open` / `CODAY_NO_OPEN=1` | Do not open the browser on start (only Nx mode opens it; never with `--auth`, a non-loopback host or `CI`) |
| `PORT` | Express port (strict when set; default 3000, 4100 in dev) |
| `AGENTOS_URL`, `FACTORY_URL` | Use an external AgentOS / Factory instead of managed ones |
| `AGENTOS_EXTERNAL_USERID` | Impose the identity forwarded to AgentOS (disables the wizard) |
| `CODAY_AGENTOS_VERSION`, `CODAY_FACTORY_VERSION` | JAR version to run (`dev` = JARs placed by hand in `<configDir>/{agentos,factory}`) |
| `CODAY_DEFAULT_FILES_DIR` | Folder proposed by the wizard instead of `~/Coday` |
| `CODAY_CLIENT_PATH`, `CODAY_FACTORY_COCKPIT_PATH` | Set by the launcher: where the static apps are |
| `BUILD_ENV=development` | Dev mode (proxy to the Angular dev servers) |

## Running the local code like an npx user: `web:local`

End users always run `npx @whoz-oss/coday-web`. In the monorepo the equivalent, one command whatever your setup state,
is:

```bash
pnpm nx run web:local                     # everyday: build local code, run it like an npx user, keeps your setup
pnpm nx run web:local -- --fresh          # test the first-run wizard again (deletes the local config first)
pnpm nx run web:local -- --managed        # single process, plain console (instead of the Nx TUI)
pnpm nx run web:local -- --no-open        # do not open the browser (every restart would otherwise open a tab)
pnpm nx run web:local -- --port 3100      # pick the port
```

The first time you get the wizard; afterwards the app opens directly on the instance you configured, so you can stop,
rebuild and restart as often as you like. The target builds the client, factory cockpit, server and web packages plus
the AgentOS and Factory boot JARs, then `scripts/web-local.ts` works in a **persistent root, `~/.coday-local`**:

```
~/.coday-local/
  packs/        pnpm pack tarballs                  (emptied on every run)
  install/      the tarballs installed with npm     (recreated on every run, what npx does minus the registry)
  config/       the --configDir                     (KEPT: web-setup.json, agentos/data, factory data, secrets)
  files/Coday   folder proposed by the wizard instead of ~/Coday
```

Every run re-packs and re-installs (the code may have changed), overwrites the `dev`-layout JARs in
`config/{agentos,factory}` (`agentos-service-dev.jar`, plugins in `plugins/`; the data directories are left alone) and
runs the installed `coday-web --configDir ~/.coday-local/config` with `CODAY_AGENTOS_VERSION=dev
CODAY_FACTORY_VERSION=dev`. It prints every path and whether this is a **first run** (no `setupDone` in
`web-setup.json`) or a **resume**. Your `~/.coday` and `~/Coday` are never touched (the script refuses a root that is, or
lies in, them, your home or the repository), and nothing is published.

| Option / env | Effect |
|---|---|
| `--fresh` | Delete `config/` and `files/` (printed) before starting: a brand-new first run |
| `--managed`, `--port <n>` | Single-process mode, Express port |
| `--no-open` | Do not open the browser (also `CODAY_NO_OPEN=1`) |
| `--prepare-only` | Prepare everything, print the paths as JSON on the last line, no server |
| `--clean` | Delete the **whole** root when the server stops (setup and data included) |
| `CODAY_LOCAL_DIR=<dir>` | Use another root than `~/.coday-local` (`CODAY_FIRST_RUN_DIR` is a deprecated alias) |

`web:first-run` is kept as an alias of `web:local -- --fresh`.

Automated version: `pnpm nx run client-e2e:first-run-e2e` drives the wizard with Playwright against a real AgentOS
(dummy keys, no LLM call) and checks the providers, models, personal namespace, Coday agent and shared folder through
the AgentOS REST API. It runs `web:local --fresh` on its own throwaway root (`FIRST_RUN_E2E_DIR` to choose it, it never
reads `CODAY_LOCAL_DIR`) and skips itself, with a reason, when Java 25 or the built JARs are missing.

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
wizard is not shown: use `web:local -- --fresh` to test it.

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
- **"Waiting for first-run setup" never ends**: open the URL printed in the pane (or the `Open ->` line of the `status`
  pane) and answer the wizard.
- **The browser did not open**: headless Linux or no `xdg-open`, or AgentOS is not healthy yet (the app is only opened
  once it is); the `server` pane logs one line and the `status` pane shows the URL.
- **A blank page on `/`**: AgentOS is not up yet (the app waits up to 90 s for it). Look at the `agentos` pane / log,
  Java 25 is the usual cause.
- **Java missing or too old**: install Java 25 (the wizard shows the command), the page continues by itself.
- **Port already in use**: `PORT=3100 npx @whoz-oss/coday-web`.

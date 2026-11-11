# Coday Web Package

`@whoz-oss/coday-web` is the launcher of the Coday web interface: it serves the Angular client, the Factory cockpit and
the Express server, and runs AgentOS and the Factory next to them.

## First run

```bash
npx @whoz-oss/coday-web
```

In a terminal (Nx mode, see below) the browser opens by itself, always on **`/welcome`**
(`http://localhost:3000/welcome`), as soon as the Express server listens and whatever the install: that page only talks
to Coday's own server, so it does not wait for AgentOS. On a **first run** it is the wizard. Otherwise (setup done, or an
install without a wizard: `--auth`, an external AgentOS, an existing install without a wizard identity) it is a plain
loading page (download, Java, AgentOS, Factory: nothing that reads as a setup) that follows the startup and, as soon as
AgentOS is up, leads into **the personal namespace of the user** (`user:<email>`) by itself. `/` redirects to `/welcome`.
With an external `AGENTOS_URL` / `FACTORY_URL` the page has nothing to
download or start for that service and only waits for it to answer. Pass `--no-open` or set `CODAY_NO_OPEN=1` to keep
the browser closed (it is also never opened with `--auth`, a non-loopback host or `CI`). Without a terminal
(`--managed`, desktop apps) nothing is opened: open the URL printed as `Server is running on http://localhost:PORT`
yourself. The wizard is a four-step flow:

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

The command is always the same, `npx @whoz-oss/coday-web`, and it always opens `/welcome`: the wizard while the setup is
not done, the loading page and then the app afterwards. The page appears once the launcher has generated the Nx
workspace, run `provision` and started the `server` task, which takes a few seconds (the Nx startup is most of it); it
does not wait for the downloads, Java or the JVMs.

### AgentOS that already has data

The wizard's email is made the AgentOS administrator only when AgentOS boots on an **empty** database. Pointed at a
database that already holds users, that email is a plain user: it still gets its personal namespace (AgentOS creates it on
its first request), but only an administrator can create the platform providers and set the files folder, so the apply stops
on AgentOS's refusal with a message saying so. Use the administrator's email, or an empty `<configDir>/agentos/data`.

### Model prices

The four models of each provider are created with their price (USD per million tokens: input, output, cache read, and
cache write for Anthropic), so AgentOS can estimate costs. Re-running the setup adds the prices to models created
before. They come from the providers' price lists and live in `apps/server/src/lib/setup/model-catalogue.ts`; Claude
Haiku 5.5 is priced at its base tier (prompts up to 100k tokens).

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
  `factory` waits for AgentOS to be healthy. As soon as Express listens, the `server` task opens the browser on
  `/welcome` (see *First run*); it never does when `--auth`, a non-loopback `--host`, `CI`, `--no-open` or
  `CODAY_NO_OPEN` say so. Nothing waits for AgentOS before opening: the page shows the progress itself. For an external
  AgentOS (`AGENTOS_URL`) / Factory (`FACTORY_URL`) the setup API probes those URLs, which is what the proxy targets.
- **The `status` pane.** The Nx TUI has no status bar, so a small dedicated task reads `runtime/state.json` and probes
  the health URLs every second. It prints one line per meaningful change only: `agentos download 42%` (one line per
  10%), `java ok` or the install hint, `agentos healthy :3005`, `factory healthy :3006`, `agentos external <origin>`
  for an `AGENTOS_URL`/`FACTORY_URL`, and `Open -> http://localhost:3000/welcome` (the same line whatever the profile and
  the setup state, same URL as the browser). In the
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

### Ports

#### Which address do I open?

| You started Coday with | Open |
|---|---|
| `npx @whoz-oss/coday-web` or `coday-web` | **http://localhost:3000** |
| `pnpm nx run web:local` (monorepo) | **http://localhost:3010** |
| `pnpm web:serve:dev`, `pnpm web:dev`, `pnpm server`, `scripts/web-dev-tmux.sh` (monorepo) | **http://localhost:4000** |
| The desktop app | nothing: the app opens its own window |

That is the only address you ever need. The Factory cockpit is on the same address, under `/factory/` (for example
http://localhost:3000/factory/). Every other port is internal: Coday uses it, you never type it.

If the terminal says a port is already in use, another program holds it: Coday refuses to start and names it. See *When a
port is taken* below.

Every start prints the addresses, for example:

```
Coday     http://localhost:3010/
Cockpit   http://localhost:3010/factory/
Internal  AgentOS 3015 · Factory 3016 · Neo4j Bolt 3017
```

#### How the numbers are built

Each way of running Coday (a **profile**) owns a **block** of ten ports starting at its **base**. Inside a block, every
service always has the same position (offset):

| Offset | Service | Who uses it |
|---|---|---|
| +0 | Express | **you**: the address you open |
| +1 | Angular client dev server | dev only, internal (packaged Coday serves the client from Express at `/`) |
| +2 | Factory cockpit dev server | dev only, internal (packaged Coday serves it from Express at `/factory/`) |
| +3, +4 | spare | |
| +5 | AgentOS | internal |
| +6 | Factory | internal |
| +7 | AgentOS embedded Neo4j (Bolt) | internal |
| +8, +9 | spare | |

So the full table is:

| Profile | Base | Express (open this) | Client | Cockpit | AgentOS | Factory | Bolt |
|---|---|---|---|---|---|---|---|
| **prod**: npx, `coday-web`, desktop | 3000 | **3000** | 3000 `/` | 3000 `/factory/` | 3005 | 3006 | 3007 |
| **local**: `web:local` | 3010 | **3010** | 3010 `/` | 3010 `/factory/` | 3015 | 3016 | 3017 |
| **dev**: monorepo dev commands | 4000 | **4000** | 4001 | 4002 | 4005 | 4006 | 4007 |

Easy to remember: **3xxx is packaged Coday, 4xxx is development.** In dev, 4001 and 4002 are the Angular dev servers
(with hot reload). Express on 4000 forwards to them, so open 4000, not 4001.

#### When a port is taken

The ports are **always the same** for a given way of running Coday: the redirect URIs registered at OAuth providers
(allowlists) are tied to them, so Coday never moves to other ports by itself.

- **npx, `coday-web`, `web:local` and `pnpm web:serve:dev`**: if any port the stack uses (Express, AgentOS, Neo4j Bolt,
  Factory) is taken, Coday **refuses to start** and prints one line naming the port and, when it can find it, the
  program holding it (on macOS, 5000 and 7000 come with an AirPlay Receiver hint):
  `Port 3000 is already in use (by node, pid 4242). Stop what uses it or choose another port (PORT=<n>, or CODAY_PORT_BASE=<n> for a whole block).`
  Stop that program and start again.
- If you really need other ports, choose them explicitly (see *Choosing the ports yourself*), knowing that the redirect
  URIs registered at your OAuth providers are tied to the default ports and will have to be registered again.
- **`scripts/web-dev-tmux.sh`**: a helper that picks the first free block among 4000, 4010, 4020 … for itself, so
  several worktrees run side by side; it then starts the stack on that block explicitly (`CODAY_PORT_BASE`).
- **`pnpm web:serve:dev`**: its ports are written in Nx and Angular config files, which cannot follow a move. Same
  rule: it stops at once with a message naming the port and the program holding it. Stop that program, or use the
  tmux script.

`runtime/state.json` records the block of the last start (`portBlock`) for information only: it never influences the
choice.

#### Choosing the ports yourself

An explicit choice is just as strict: if the port is taken, Coday stops with the same message.

| To | Use |
|---|---|
| choose the address you open | `--port <n>` or `PORT=<n>` |
| choose a whole block | `CODAY_PORT_BASE=<n>` (for example a third checkout on 3050) |
| choose one internal service | `CODAY_AGENTOS_PORT`, `CODAY_FACTORY_PORT`, `CODAY_AGENTOS_BOLT_PORT` |

The desktop app always uses port 3049 (a spare port of the prod block 3040), on purpose: the app keeps its saved
preferences per address, so that address must not change.

#### Why not 5000 or 7000?

On macOS the AirPlay Receiver listens on ports 5000 and 7000 by default. Coday avoids them so it works on every Mac
without changing any system setting. If another tool reports one of them as taken, AirPlay is the usual reason (System
Settings > General > AirDrop & Handoff > AirPlay Receiver).

A Java debugger (JDWP) listens on 5005 by default: no Coday port uses it.

#### For contributors: where the numbers live

All the numbers (block size, offsets, bases) are in **one file**: `apps/server/src/lib/runtime/port-scheme.json`.
Change them there, nowhere else.

- The server runtime (`port-scheme.ts`), `scripts/web-local.ts`, the Playwright config, the Angular dev proxy
  (`apps/client/proxy.conf.mjs`) and `scripts/web-dev-tmux.sh` read that file.
- A few files cannot read it and repeat the dev values: the `port` of `apps/client/project.json` and
  `apps/factory-cockpit/project.json`, the dev env values of `apps/web/project.json`, and `.idea/runConfigurations`.
  `port-scheme-consistency.spec.ts` checks them against the JSON and fails, naming the file and the expected value, if
  they drift. It also checks that the files that read the JSON contain no port number of their own.
- Every Coday launcher passes the embedded Neo4j Bolt port explicitly (`AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT` and
  `SPRING_NEO4J_URI`): the `7688` default of `application-embedded-neo4j.yml` is not used.

### Logs

Each process writes to its own file, in addition to its pane / the console:

```
<configDir>/logs/server.log
<configDir>/logs/agentos.log
<configDir>/logs/factory.log
```

`configDir` defaults to `~/.coday` (`--configDir <dir>` to change it).

### Security in unmanaged mode

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
| `PORT` | Express port instead of the one of the block (default: the first block of the profile, always the same, see *Ports*). Taken: Coday refuses to start |
| `CODAY_PORT_BASE` | Use this port block instead of the default one of the profile. Taken: Coday refuses to start (no block fallback). Redirect URIs registered at OAuth providers are tied to the default ports |
| `CODAY_PORT_PROFILE=prod\|local\|dev` | Profile whose default block is used (set to `local` by `web:local`); derived from `BUILD_ENV` otherwise |
| `CODAY_AGENTOS_PORT`, `CODAY_FACTORY_PORT`, `CODAY_AGENTOS_BOLT_PORT` | Port of the managed AgentOS, Factory and AgentOS Bolt instead of the block's (taken: Coday refuses to start) |
| `AGENTOS_URL`, `FACTORY_URL` | Use an external AgentOS / Factory instead of managed ones. A service that is external has no pane in the Nx run (nothing to run); the `status` pane still reports it |
| `CODAY_NO_FACTORY=1` | Leave the managed Factory out: no `factory` pane in the Nx run, it is neither downloaded nor started, `/api/factory` is not proxied, and the wizard and the loading page do not mention it. An external `FACTORY_URL` is still used |
| `--profile unmanaged\|managed` / `CODAY_PROFILE` | Force the profile instead of deriving it (the flag wins over the env var). E.g. a dev server (`AGENTOS_URL`) started on the configDir of a running `coday-web` with `--profile unmanaged` keeps forwarding the wizard identity to that AgentOS. An external AgentOS on an already provisioned configDir never rewrites `runtime/state.json` (ports, progress) in the single-process mode, except for this profile |
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
pnpm nx run web:local -- --port 3100      # choose the Express port (taken: the run fails)
```

The first time you get the wizard; afterwards the loading page and then the app, on the instance you configured, so you can stop,
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
| `--managed`, `--port <n>` | Single-process mode, Express port (taken: the run fails). Without `--port` the stack uses the LOCAL block 3010 (prod has 3000: never the same block) and fails, naming the process, when one of its ports is taken |
| `--no-open` | Do not open the browser (also `CODAY_NO_OPEN=1`) |
| `--prepare-only` | Prepare everything, print the paths as JSON on the last line, no server |
| `--clean` | Delete the **whole** root when the server stops (setup and data included) |
| `CODAY_LOCAL_DIR=<dir>` | Use another root than `~/.coday-local` (`CODAY_FIRST_RUN_DIR` is a deprecated alias) |
| `CODAY_PORT_BASE=<n>` | Use this port block instead of 3010 (taken: the run fails; e.g. a second checkout side by side, on a block of the 3xxx range your prod and local do not use) |

`web:first-run` is kept as an alias of `web:local -- --fresh`.

Automated version: `pnpm nx run client-e2e:first-run-e2e` drives the wizard with Playwright against a real AgentOS
(dummy keys, no LLM call) and checks the providers, models, personal namespace, Coday agent and shared folder through
the AgentOS REST API. It runs `web:local --fresh` on its own throwaway root (`FIRST_RUN_E2E_DIR` to choose it, it never
reads `CODAY_LOCAL_DIR`) and skips itself, with a reason, when Java 25 or the built JARs are missing.

## Development (monorepo)

```bash
pnpm web:serve:dev      # nx run web:serve:dev: client, factory-cockpit, server, AgentOS and Factory, one pane each
pnpm web:dev            # lighter: client + server only (no AgentOS/Factory; set AGENTOS_URL to use your own)
pnpm client             # Angular dev server only (internal dev port, see Ports)
pnpm server             # Express with tsx watch only (dev base, open this one)
```

`web:serve:dev` is a set of continuous Nx tasks: `client:serve`, `factory-cockpit:serve`, `web:dev-server` (Express),
`web:dev-agentos` (`agentos-service:bootRun`, with its embedded Neo4j Bolt) and `web:dev-factory`
(`factory-service:bootRun`): the DEV row of the *Ports* table (open Express, 4000). They use fixed dev ports and fail
fast, with a message naming the port, when one is taken. The dev server uses the
external-service mode (`AGENTOS_URL`/`FACTORY_URL`), so the wizard is not shown: use `web:local -- --fresh` to test it.

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
- **The browser did not open**: headless Linux or no `xdg-open`, `--no-open`, `CI`, `--auth` or a non-loopback host; the
  `server` pane logs one line (`Not opening the browser: <why>`) and the `status` pane shows the URL.
- **"AgentOS refused: ... is not an administrator"** at the end of the wizard: see *AgentOS that already has data*.
- **The loading page does not leave**: AgentOS is not up yet. Look at the `agentos` pane / log, Java 25 is the usual
  cause. With an external `AGENTOS_URL`, check that URL answers on `/management/health`. (Opening `/` yourself
  meanwhile shows a blank page for up to 90 s, the app waits for AgentOS.)
- **Java missing or too old**: install Java 25 (the wizard shows the command), the page continues by itself.
- **Port already in use**: Coday keeps the same ports on purpose (OAuth redirect URI allowlists are tied to them), so it
  refuses to start and names the program holding the port. Stop it, or choose other ports explicitly
  (`PORT=3100 npx @whoz-oss/coday-web`, or `CODAY_PORT_BASE=<n>` for a whole block; see *Ports*).

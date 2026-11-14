# Running the Client

## Recommended: `pnpm web:dev`

```bash
pnpm web:dev
```

Starts **both** the Angular dev server and the Express backend in parallel watch mode via Nx. This is the standard development command — changes to client or server code trigger automatic rebuilds.

- Express backend: **open this one**, `http://localhost:4000` (it proxies the Angular dev server transparently)
- Angular dev server: port 4001, an internal dev server reached through Express (do not open it directly)

The ports are the DEV block of the Coday port scheme (single source of truth: `apps/server/src/lib/runtime/port-scheme.json`; dev 4000, packaged `coday-web` 3000, `web:local` 3010): +0 Express, +1 client, +2 Factory cockpit, +5 AgentOS, +6 Factory, +7 AgentOS embedded Neo4j Bolt. Full table in `apps/web/README.md` (*Ports*).

## AgentOS Backend (when needed)

Required for the client (served at `/`), which is backed by the AgentOS Kotlin service:

```bash
cd agentos && ./gradlew bootRun
```

Runs on port 4005 in the dev scheme when started by `pnpm web:serve:dev` (`SERVER_PORT=4005`, embedded Neo4j Bolt `AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT=4007`). A bare `./gradlew bootRun` uses the `application.yml` defaults (8124, Bolt 7688): pass the same variables to match the scheme. The Angular proxy forwards `/api/agentos/*` to 4005 automatically.

## Daemonay Sessions

Conventional tmux session names used by the Daemonay agent:

| Session | Command |
|---|---|
| `coday-web-dev` | `pnpm web:dev` |
| `agentos` | AgentOS Kotlin backend |

## Debug Mode

```bash
pnpm server:debug
```

Starts the Express server with Node inspector on port 9229. Run the Angular dev server separately with `pnpm client`.

## Production Build

```bash
pnpm nx build client
```

Output in `apps/client/dist/`. The Express server serves these static files in production.

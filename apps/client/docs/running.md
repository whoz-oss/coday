# Running the Client

## Recommended: `pnpm web:dev`

```bash
pnpm web:dev
```

Starts **both** the Angular dev server and the Express backend in parallel watch mode via Nx. This is the standard development command — changes to client or server code trigger automatic rebuilds.

- Angular dev server: `http://localhost:4200`
- Express backend: port 3000 (proxied transparently by the Angular dev server)

## AgentOS Backend (when needed)

Required only for the `/agentos` route or features backed by the AgentOS Kotlin service:

```bash
cd agentos && ./gradlew bootRun
```

Runs on port 8123. The Angular proxy forwards `/api/agentos/*` there automatically.

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

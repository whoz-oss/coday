# Nx Tasks

## Client App Targets

Defined in `apps/client/project.json`.

| Target | Command | Notes |
|---|---|---|
| `build` | `pnpm nx build client` | Runs `build-angular` then copies `package.json` to `dist/` |
| `build-angular` | (called by `build`) | `@angular/build:application`, output to `apps/client/dist/` |
| `serve` | `pnpm nx serve client` | Dev server on port 4200, proxies `/api/agentos` to port 8123 |
| `test` | `pnpm nx test client` | Jest via `@nx/jest:jest` |
| `lint` | `pnpm nx lint client` | ESLint |

Default build configuration is **production**. Pass `--configuration=development` for source maps and no optimisation.

## Lib Targets

Publishable libs (`design-system`, `agentos-ui`, `agentos-api-client`) use `@nx/angular:package` (ng-packagr). Output goes to `libs/<name>/dist/`.

| Lib | Build command |
|---|---|
| `design-system` | `pnpm nx build design-system` |
| `agentos-ui` | `pnpm nx build agentos-ui` (depends on `design-system:build`) |
| `agentos-api-client` | `pnpm nx build agentos-api-client` |

`agentos-ui` has `dependsOn: ["^build"]` in its `project.json` — building it automatically builds `design-system` first.

## Build Everything

```bash
pnpm nx run-many --target=build --all
```

Run this before raising a PR to verify the full compilation chain.

## Code Generation — AgentOS API Client

```bash
pnpm nx run agentos-api-client:generate-client
```

Reads `libs/agentos-api-client/openapitools.json`, spec at `agentos/openapi/agentos-openapi.yaml`, writes generated code to `libs/agentos-api-client/src/lib/`. Runs prettier as post-process.

To regenerate spec + client together: `pnpm nx run agentos-service:regenerate`.

## Lint Scope

```bash
pnpm nx lint client          # app only
pnpm nx lint design-system   # lib
pnpm nx lint agentos-ui      # lib
pnpm lint                    # entire workspace
```

## Affected-Only Runs (CI)

```bash
pnpm nx affected --target=build
pnpm nx affected --target=lint
pnpm nx affected --target=test
```

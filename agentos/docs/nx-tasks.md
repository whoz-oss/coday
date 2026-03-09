# Nx Tasks

All commands run from the repository root.

## agentos-sdk

| Task | Command | Description |
|---|---|---|
| `build` | `nx build agentos-sdk` | Compiles the SDK JAR |
| `test` | `nx test agentos-sdk` | Runs SDK tests |
| `publish` | `nx publish agentos-sdk` | Publishes to GitHub Packages |

`build` must run before `agentos-service:build` (enforced by Nx `dependsOn`).

## agentos-service

| Task | Command | Description |
|---|---|---|
| `build` | `nx build agentos-service` | Compiles the service (depends on SDK build) |
| `test` | `nx test agentos-service` | Runs all tests |
| `bootRun` | `nx bootRun agentos-service` | Starts the Spring Boot server on port 8080 |
| `bootJar` | `nx run agentos-service:bootJar` | Produces the fat JAR in `build/libs/` |
| `generate-openapi-spec` | `nx run agentos-service:generate-openapi-spec` | Generates `openapi/agentos-openapi.yaml` |
| `check-openapi-spec` | `nx run agentos-service:check-openapi-spec` | Fails if committed spec differs from generated |
| `regenerate` | `nx run agentos-service:regenerate` | Generates spec then regenerates the TypeScript client |

## Build All

```bash
nx run-many -t build --all
```

## Affected Only (CI)

```bash
nx affected -t build --base=origin/master
nx affected -t test  --base=origin/master
```

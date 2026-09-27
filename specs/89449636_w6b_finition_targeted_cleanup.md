# W6b Finish Spec — Targeted Cleanup & Port 3141 -> 8141 Repointing

## Overview

This specification details the final polish step (W6b finishing) for removing the remaining legacy Node references and repointing all Factory API consumers from port `3141` to `8141`. The bulk of Node deletion has already occurred on this branch. This plan covers strictly the targeted file edits across 13 source/config files plus OpenAPI spec and `factory/README.md`, with zero directory deletions and zero unlisted file modifications.

---

## File Modification Plan

### 1. `factory-service/src/main/resources/application.yml`
- Locate lines ~51–53 in section `factory.runs`:
  ```yaml
  factory:
    runs:
      dir: ${FACTORY_RUNS_DIR:factory/runs}
      entry: ${FACTORY_RUN_ENTRY:factory/run.mjs}
  ```
- **Action**: Delete line `entry: ${FACTORY_RUN_ENTRY:factory/run.mjs}`.
- Do NOT delete the parent `runs:` key since `dir:` and other properties under `runs:` (`agentos-url`, `jira-*`) are still active.
- Do not touch any other section or property in `application.yml`.

### 2. `factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/config/ForgeProperties.kt`
- **Action 1**: Delete field declaration `val runEntry: String = "factory/run.mjs",` (around line 20).
- **Action 2**: Delete assignment line `runEntry = read("factory.forge.run-entry", "FACTORY_RUN_ENTRY", env) ?: "factory/run.mjs",` (around line 46).
- **Verification**: No other code in `factory-forge-plugin` references `runEntry`.
- **Constraint**: Do NOT touch line ~90 of `ForgeFrontOracleResolution.kt` (`ProcessBuilder("pnpm", "nx", ...)`).

### 3. Port 3141 → 8141 Repointing and Default/Doc Adjustments
Replace `3141` with `8141` where it represents the Factory API origin/port in the following files:

- **`apps/client/proxy.conf.json`**
  - Line ~15: Replace `"target": "http://localhost:3141"` with `"target": "http://localhost:8141"`.

- **`libs/integration/src/lib/factory.tools.test.ts`**
  - Replace occurrences of `http://127.0.0.1:3141/` and `http://localhost:3141` with `http://127.0.0.1:8141/` and `http://localhost:8141`.

- **`libs/integration/src/lib/factory.tools.node-test.ts`**
  - Replace occurrences of `http://127.0.0.1:3141/` with `http://127.0.0.1:8141/`.

- **`libs/model/src/lib/project-description.ts`**
  - Line ~11 doc comment: Replace `http://127.0.0.1:3141` with `http://127.0.0.1:8141`.

- **`agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryBridgeConfig.kt`**
  - Line ~15 doc comment & Line ~36: Change default base URL to `http://localhost:8141`.
  - Also update `FactoryTestFixtures.kt`, `FactoryPublishProjectionToolSpec.kt`, and `FactorySubmitStepResultToolSpec.kt` in the same plugin tests where `:3141` is hardcoded to `:8141`.

- **`forge_bmad/coday/integrations/PROJECT_SCRIPTS.yaml`**
  - Replace default port references `:3141` with `:8141`.
  - Update hint text "node factory/dashboard/server.mjs on Coday" to refer to `factory-service` (`:8141`).

- **`forge_bmad/coday/scripts/forge-factory-launch.ts`**
  - Replace default port `3141` / `http://localhost:3141` with `8141` / `http://localhost:8141`.
  - Replace hint `'Vérifiez que node factory/dashboard/server.mjs tourne sur le repo Coday.'` with reference to `factory-service` on port 8141.

- **`forge_bmad/coday/scripts/forge-workflow-sync.ts`**
  - Replace `http://localhost:3141` defaults in comment & code with `http://localhost:8141`.

- **`forge_bmad/coday/scripts/forge-gate2-record.ts`**
  - Replace default port `3141` / `http://localhost:3141` with `8141` / `http://localhost:8141`.
  - Replace hint `'Vérifiez que node factory/dashboard/server.mjs tourne sur le repo Coday.'` with reference to `factory-service` on port 8141.

- **`forge_bmad/coday/scripts/forge-gate-run.ts`**
  - Replace default `http://localhost:3141` with `http://localhost:8141`.

### 4. `factory-service/openapi/factory-openapi.yaml`
Delete all path blocks and operations corresponding to legacy runs (`operationId` containing `LegacyRun`):
1. Delete `/api/runs` path block (contains `listLegacyRun`, `launchLegacyRun`).
2. Delete `/api/review-gate/reply` path block (contains `deprecatedGateReplyLegacyRun`).
3. Delete `/api/factory/runs` path block (contains `factoryListLegacyRun`, `factoryLaunchLegacyRun`).
4. Delete `/api/factory/runs/{id}/stop` path block (contains `stopLegacyRun`).
5. Delete `/api/factory/runs/{id}/review-gate/reply` path block (contains `reviewGateReplyLegacyRun`).
6. Delete `/api/runs/{id}` path block (contains `detailLegacyRun`).
7. Delete `/api/review-gate` path block (contains `deprecatedGateLegacyRun`).
8. Delete `/api/factory/runs/{id}` path block (contains `factoryDetailLegacyRun`).
9. Delete `/api/factory/runs/{id}/review-gate` path block (contains `reviewGateLegacyRun`).

Ensure valid OpenAPI YAML syntax remains after deletions.

### 5. `factory/README.md`
Rewrite briefly to reflect current state:
- `factory/` contains only the static cockpit UI assets (served same-origin by `factory-service` at `:8141`/`/cockpit`).
- `factory-service` (Kotlin/Spring Boot) is the sole control plane AND execution runtime (DAG sequencer).
- No Node runtime, no legacy dashboard server (`server.mjs`), no instrument script (`run.mjs`).

---

## Verification & Validation Commands

### 1. Active Code Grep Checks
Run the following check to verify no residual occurrences remain in active code (excluding `build/`, `.gradle/`, `.git/`, `node_modules/`, `dist/`, `specs/`, `docs/`, `app_docs/`, `.nx/`, `CHANGELOG.md`):
```bash
grep -rnE "(run\.mjs|LegacyRun|factory/dashboard/server\.mjs|\b3141\b)" . \
  --exclude-dir={build,.gradle,.git,node_modules,dist,specs,docs,app_docs,.nx} \
  --exclude="CHANGELOG.md"
```
*Expected output*: 0 matching lines.

### 2. Compilation Check
Run Kotlin compilation for factory-service:
```bash
cd factory-service && ./gradlew compileKotlin compileTestKotlin --rerun-tasks
```
*Expected result*: `BUILD SUCCESSFUL`.

### 3. Affected Nx Tests Check
Run project test suite:
```bash
pnpm nx test factory-service
```
*Expected result*: All tests pass.

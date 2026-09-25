# Phase 5 Migration Factory Plan: Composition Root, TrustContext & Standardized HTTP API

## Executive Summary

Refactor `factory/dashboard/server.mjs` into a thin bootstrap entrypoint and extract a dedicated Composition Root (`factory/dashboard/composition-root.mjs`) managing explicit lifecycle steps.
Establish centralized `TrustContext` extraction at HTTP boundaries, standardizing HTTP responses (error JSON shapes, correlation IDs, revision consistency) while preserving route delegate boundaries.
Generate OpenAPI 3.0 specification (`factory/dashboard/openapi.json`) for all endpoints including active & legacy endpoints with deprecation schedules.
Maintain 100% test suite compatibility without altering domain logic in `factory/lib/` or touching `agentos/**` / `factory/src/adapters/agentos/**`.

---

## Architecture & Lifecycle Principles

### 1. Thin Server Bootstrap (`factory/dashboard/server.mjs`)
`server.mjs` becomes a thin bootstrap entrypoint responsible for:
- Environment variable reading and CLI invocation detection.
- Delegating initialization to the Composition Root (`compositionRoot.init()`).
- Listening on HTTP server port with `FACTORY_BIND_POLICY`.
- Re-exporting legacy imports (`parseJsonl`, `reconstructPhases`, `resolveFactoryBindPolicy`) required by existing tests (`test-factory-api.mjs`, `test-dashboard-chronology.mjs`, `test-factory-bind-policy.mjs`).

### 2. Composition Root Lifecycle (`factory/dashboard/composition-root.mjs`)
The Composition Root strictly manages the initialization pipeline:
1. `loadConfig(env)`: Validates and loads bind policies (`resolveFactoryBindPolicy`), directory roots (`FACTORY_DATA_ROOT`, `FACTORY_REPO_ROOT`, `FACTORY_WORKTREES_ROOT`, `RUNS_DIR`, etc.), integration URLs, Jira credentials, and feature flags.
2. `createStores(config)`: Instantiates singletons for all stores:
   - `WorkflowProjectionStore`
   - `WorkflowEvidenceStore`
   - `WorkflowHumanInteractionStore`
   - `WorkflowResumeDispatchStore`
   - `AgentStepResultStore`
   - `WorkUnitEnvironmentStore`
   - `DeliveryStore`
   - `DeliveryEvidenceStore`
3. `createAdapters(config, stores)`: Instantiates adapters and helpers:
   - `AgentOsProxy`
   - `WorkflowDefinitionRegistry`
   - `OracleDefinitionRegistry`
   - `GitWorktreeProvisioner`
   - `DeliveryGitControlPlane`
   - `DeliveryPullRequestAdapter`
   - `DeliveryTargetRegistry`
   - `WorkflowProjectionSseHub`
   - `FactoryFrontendRunner`
4. `createApplication(config, stores, adapters)`: Instantiates domain controllers and services:
   - `WorkUnitEnvironmentController`
   - `DeliveryController`
   - `DeliveryOperationController`
   - `FactoryOperationalMetricsService`
   - `RunRouter`
5. `createHttpServer(application, config)`: Returns standard `http.Server` with request routing, TrustContext middleware, correlation ID injection, and standard HTTP error serialization.
6. `initialize()`: Runs store/registry initializations (`await store.initialize()`).

Direct `new *Store()` outside of the Composition Root or unit test files is strictly prohibited.

---

## 3. Boundary & Context Decoupling

### TrustContext Boundary
Extracted via HTTP headers / connection metadata:
- `x-factory-namespace-id` -> `namespaceId`
- `x-factory-case-id` -> `caseId`
- `x-factory-actor-id` -> `actorId`
- `x-factory-runtime-id` -> `runtimeId`
- `x-factory-agent-id` -> `agentId`
- `x-factory-thread-id` -> `threadId`
- Host / Remote Address -> `trustMode` (`loopback-only` vs `unsafe-remote-unauthenticated`)

Passed to route handlers via a unified `trustContext` or `identity` resolver function.

### Standardized Response Contract & Middleware (`http-utils.mjs`)
HTTP utilities will be expanded to enforce:
- **Error JSON Format**: `{ "error": { "code": string, "message": string, "details": any } }`
- **Correlation Tracking**: Check `x-correlation-id` header on requests; if absent, generate a UUID or timestamp-based correlation ID (`coday-corr-<random>`), and attach `x-correlation-id` to every HTTP response header.
- **Revision Handling / Optimistic Locking**: Ensure 409 Conflict status with clear error codes (`REVISION_CONFLICT`, `IDEMPOTENCY_KEY_COLLISION`) across projection, interaction, and delivery endpoints.
- **Pagination / Collection Headers**: Support `x-total-count` where applicable.

---

## 4. Work Breakdown & Implementation Steps

### Step 1: Core HTTP Utilities & Error Normalization
- Update `factory/dashboard/http-utils.mjs`:
  - Enforce `x-correlation-id` header injection on all responses.
  - Implement helper `sendError(res, status, code, message, details = null)`.
  - Update `send(res, status, body, ct, extraHeaders)` to support custom headers cleanly.

### Step 2: Extract Composition Root
- Create `factory/dashboard/composition-root.mjs`:
  - Implement `loadConfig(env)`
  - Implement `createStores(config)`
  - Implement `createAdapters(config, stores)`
  - Implement `createApplication(config, stores, adapters)`
  - Implement `createHttpServer(application, config)`
  - Implement `createCompositionRoot(env)` returning `{ config, stores, adapters, application, server, initialize }`.
- Update `factory/dashboard/server.mjs`:
  - Delegate setup to `createCompositionRoot()`.
  - Export re-exports required by existing tests (`parseJsonl`, `reconstructPhases`, `resolveFactoryBindPolicy`).

### Step 3: Route Delegation & Response Standardisation
Review and refine response formats across route handlers to adhere to `{ "error": { "code", "message", "details" } }`:
- `run-routes.mjs`
- `forge-routes.mjs`
- `active-run-routes.mjs`
- `workstream-routes.mjs`
- `workflow-projection-routes.mjs`
- `workflow-transition-routes.mjs`
- `workflow-code-transition-routes.mjs`
- `workflow-oracle-routes.mjs`
- `workflow-human-interaction-routes.mjs`
- `workflow-evidence-routes.mjs`
- `workflow-definition-routes.mjs`
- `agent-step-result-routes.mjs`
- `delivery-operation-routes.mjs`

### Step 4: OpenAPI 3.0 Specification (`factory/dashboard/openapi.json`)
Create `factory/dashboard/openapi.json` documenting:
- Info & Version (3.0.3)
- Server configurations (`http://localhost:3141`)
- Headers (`X-Factory-Namespace-Id`, `X-Factory-Case-Id`, `X-Factory-Actor-Id`, `X-Correlation-Id`)
- Core Active Endpoints:
  - Workflows (`/api/factory/workflows/*`)
  - Deliveries & Operations (`/api/factory/deliveries/*`)
  - Work Units (`/api/factory/work-unit-environments/*`)
  - Frontend Runs (`/api/factory/frontend-runs/*`)
- Legacy & Historical Endpoints (Tagged `deprecated: true` with scheduled retirement notes):
  - Historical JSONL runs (`/api/runs`, `/api/runs/:id`, `/api/factory/runs`)
  - Review gates (`/api/runs/:id/gates/*`, `/api/gates/*`)
  - Active run proxies (`/api/active-run`)
  - Workstream proxies (`/api/workstream`)

---

## 5. Verification Plan

### Test Suite Execution
Run tests using Node test runner (per factory conventions):
```bash
# Core API & Factory tests
node --test factory/tests/test-factory-api.mjs
node --test factory/tests/test-factory-bind-policy.mjs
node --test factory/tests/test-dashboard-chronology.mjs
node --test factory/tests/test-workflow-*.mjs
node --test factory/tests/test-delivery-*.mjs
```

### OpenAPI Spec Validation
Validate `factory/dashboard/openapi.json` for syntactical JSON validity and structural schema compliance.

---

## Constraints Verification Checklist
- [x] No modifications to `agentos/**` or `factory/src/adapters/agentos/**`.
- [x] No reorganization of domain code in `factory/lib/`.
- [x] All factory tests pass cleanly.

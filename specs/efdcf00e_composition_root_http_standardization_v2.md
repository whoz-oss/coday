# Phase 5 Plan: Composition Root, HTTP/Application Decoupling, TrustContext, Standardized API Contracts & OpenAPI Spec

## Summary
Refactor `factory/dashboard/server.mjs` into a thin bootstrap file that invokes a newly created Composition Root (`factory/dashboard/composition-root.mjs`). Standardize HTTP handling with transport utilities (correlation ID propagation/generation, standardized `{ "error": { "code", "message", "details" } }` formatting, `TrustContext` extraction), single-instantiation of stores, clean delegation from HTTP routes to domain services/controllers, and generate a complete OpenAPI 3.0 specification (`factory/dashboard/openapi.json`) with deprecation tags and planned retirement dates for legacy endpoints.

---

## 1. Objectives & Architectural Requirements

1. **Pure Thin Bootstrap (`factory/dashboard/server.mjs`)**:
   - `server.mjs` will contain only minimal process entry code: calling `createCompositionRoot(process.env)`, starting the HTTP server, listening on the configured port/host, and logging startup messages.
   - It will preserve backward-compatibility exports (`parseJsonl`, `reconstructPhases`, `resolveFactoryBindPolicy`, `isAllowedStoryEditRequestBody`) needed by existing test suites.

2. **Composition Root (`factory/dashboard/composition-root.mjs`)**:
   - Structure lifecycle functions clearly:
     - `loadConfig(env)`
     - `createStores(config)`
     - `createAdapters(config, stores)`
     - `createApplication(config, stores, adapters)`
     - `createHttpServer(application, config)`
   - Single instantiation for all stores (`WorkflowProjectionStore`, `WorkflowEvidenceStore`, `AgentStepResultStore`, `WorkflowHumanInteractionStore`, `WorkflowResumeDispatchStore`, `WorkUnitEnvironmentStore`, `DeliveryStore`, `DeliveryEvidenceStore`). No route module, handler, or external file may call `new Store()`.

3. **TrustContext Extraction**:
   - Extract identity and security context at the HTTP boundary in `composition-root.mjs` / `http-utils.mjs`:
     - Header extraction: `x-factory-namespace-id`, `x-factory-case-id`, `x-factory-actor-id`, `x-factory-authority-id`, `x-factory-runtime-id`, `x-factory-agent-id`, `x-factory-thread-id`.
     - Policy evaluation: Check `FACTORY_BIND_HOST` and `FACTORY_UNSAFE_ALLOW_REMOTE_BIND` (`loopback-only` vs `unsafe-remote-unauthenticated`).
     - Pass structured `TrustContext` object into application handlers/controllers.

4. **HTTP Transport Standardization & Header Utilities (`factory/dashboard/http-utils.mjs`)**:
   - Correlation IDs: Inspect `x-correlation-id` header on requests; if absent, generate a UUID / trace ID. Always set `X-Correlation-Id` on HTTP responses.
   - Standard Error Format: All route modules and error handlers must respond with `{ "error": { "code": string, "message": string, "details": any } }`.
   - Optimistic Locking / Revisions: Enforce `REVISION_CONFLICT` handling consistently where optimistic locking applies.
   - Idempotency & Pagination support.

5. **Route Module & Controller Decoupling**:
   - Routes remain purely transport-focused (parsing HTTP request, calling controller/service method with `TrustContext`, formatting response with `send`).
   - Domain logic and orchestration reside in application controllers/services in `factory/lib/`.

6. **OpenAPI 3.0 Specification (`factory/dashboard/openapi.json`)**:
   - Document all active and legacy Factory API endpoints.
   - Tag legacy endpoints (`/api/runs`, `/api/factory/runs`, review gate aliases) with `deprecated: true` and specify a planned retirement schedule (e.g. Phase 7 / Q4 2026) in descriptions.

---

## 2. Target Directory & File Structure

```
factory/dashboard/
├── server.mjs                           # Thin bootstrap (< 50 lines)
├── composition-root.mjs                 # NEW: Composition Root (loadConfig, createStores, createAdapters, createApplication, createHttpServer)
├── http-utils.mjs                       # Enhanced send, readBody, TrustContext, Correlation ID helpers
├── openapi.json                         # NEW: OpenAPI 3.0 specification for all active & legacy endpoints
├── active-run-routes.mjs
├── agent-step-result-routes.mjs
├── agentos-proxy.mjs
├── delivery-operation-routes.mjs
├── factory-frontend-run-routes.mjs
├── forge-routes.mjs
├── forge-workflow-projection-routes.mjs
├── run-routes.mjs                        # Legacy JSONL runs & review gates
├── workflow-code-transition-routes.mjs
├── workflow-definition-routes.mjs
├── workflow-evidence-routes.mjs
├── workflow-human-interaction-routes.mjs
├── workflow-operational-metrics-routes.mjs
├── workflow-oracle-routes.mjs
├── workflow-projection-routes.mjs
├── workflow-projection-sse.mjs
├── workflow-transition-routes.mjs
└── workstream-routes.mjs
```

---

## 3. Step-by-Step Implementation Steps

### Step 1: Enhance `factory/dashboard/http-utils.mjs`
- Update `send(res, status, body, headers = {})`:
  - Support passing request correlation ID or automatic fallback.
  - Set `X-Correlation-Id` header on responses.
  - Standardize error responses to match `{ "error": { "code": string, "message": string, "details": any } }`. Convert plain string errors `{ error: "message" }` to `{ error: { code: "BAD_REQUEST" | "NOT_FOUND" | "INTERNAL_ERROR", message: "message", details: null } }` while keeping compatibility for legacy fields if required by tests.
- Export `extractTrustContext(req, bindPolicy)` helper:
  - Extracts `namespaceId`, `caseId`, `actorId`, `authorityId`, `runtimeId`, `agentId`, `threadId`, `correlationId`.
  - Determines trust mode (`loopback-only` vs `unsafe-remote-unauthenticated`).

### Step 2: Create `factory/dashboard/composition-root.mjs`
- Implement lifecycle functions:
  1. `loadConfig(env)`: Reads `PORT`, `AGENTOS_URL`, `FACTORY_DATA_ROOT`, `FACTORY_REPO_ROOT`, `FACTORY_WORKTREES_ROOT`, Jira credentials, etc. Resolves bind policy via `resolveFactoryBindPolicy(env)`.
  2. `createStores(config)`: Instantiate single instances of all 8 stores (`WorkflowProjectionStore`, `WorkflowEvidenceStore`, etc.).
  3. `createAdapters(config, stores)`: Instantiate `GitWorktreeProvisioner`, `DeliveryGitControlPlane`, `DeliveryPullRequestAdapter`, `DeliveryTargetRegistry`, `WorkflowProjectionSseHub`, `WorkflowDefinitionRegistry`, `OracleDefinitionRegistry`, `createAgentOsProxy`, etc.
  4. `createApplication(config, stores, adapters)`: Instantiate controllers (`WorkUnitEnvironmentController`, `DeliveryController`, `DeliveryOperationController`, `FactoryOperationalMetricsService`, `factoryFrontendRunner`, `runRouter`, etc.). Initialize all stores/registries via `initialize()`.
  5. `createHttpServer(application, config)`: Create `http.createServer` dispatching request matching sequentially to route handlers, injecting `TrustContext` and `correlationId`.

### Step 3: Refactor `factory/dashboard/server.mjs`
- Reduce `server.mjs` to a thin execution entry point.
- Re-export required functions for test compatibility:
  - `export { resolveFactoryBindPolicy } from './composition-root.mjs'` (or from `server.mjs` forwarding to composition-root)
  - `export { parseJsonl, reconstructPhases } from './run-routes.mjs'`
  - `export { isAllowedStoryEditRequestBody } from './http-utils.mjs'`
- Implement top-level execution when run via `node factory/dashboard/server.mjs`:
  ```js
  import { createCompositionRoot } from './composition-root.mjs'
  if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const root = await createCompositionRoot(process.env)
    await root.start()
  }
  ```

### Step 4: Audit Route Modules for Store Instantiations & Error Standards
- Ensure no route file calls `new *Store()`.
- Standardize error responses across all route modules:
  - `workflow-projection-routes.mjs`
  - `workflow-human-interaction-routes.mjs`
  - `delivery-operation-routes.mjs`
  - `forge-routes.mjs`
  - `active-run-routes.mjs`
  - `agent-step-result-routes.mjs`
  - `run-routes.mjs`
- Ensure error format `{ "error": { "code": string, "message": string, "details": any } }` is returned consistently.

### Step 5: Generate `factory/dashboard/openapi.json`
- Create an OpenAPI 3.0.3 specification documenting all 25+ endpoints:
  - **Active Endpoints**:
    - `/api/config`
    - `/api/agents`
    - `/api/cases/{caseId}/events`
    - `/api/jira/{ticketId}`
    - `/api/active-run`
    - `/api/workstreams`
    - `/api/factory/frontend-runs`
    - `/api/factory/agent-step-results`
    - `/api/factory/workflows/...` (projections, transitions, definitions, evidence, human interactions, metrics, code transitions, oracles)
    - `/api/factory/delivery/...`
    - `/api/factory/forge/...`
  - **Legacy Endpoints (Tagged Deprecated with Retirement Schedule)**:
    - `GET /api/runs`, `GET /api/runs/{id}`, `POST /api/runs`, `GET /api/runs/{id}/stream`
    - `GET /api/factory/runs`, `GET /api/factory/runs/{id}`, `POST /api/factory/runs`, `POST /api/factory/runs/{id}/stop`, `GET /api/factory/runs/{id}/stream`
    - `GET /api/factory/runs/{id}/review-gate`, `POST /api/factory/runs/{id}/review-gate/reply`
    - Deprecation schedule tag in OpenAPI description: `"deprecated": true`, `"description": "Legacy JSONL/Review-Gate run endpoint. Deprecated as of Phase 5; planned retirement in Phase 7 (Q4 2026)."`

---

## 4. Verification & Testing

Verify that all existing tests pass without regressions:
1. `node factory/tests/test-factory-api.mjs`
2. `node factory/tests/test-dashboard-chronology.mjs`
3. `node factory/tests/test-forge-story-edit-contract.mjs`
4. `node factory/tests/test-factory-bind-policy.mjs`
5. `node factory/tests/test-forge-story-analysis-edge-cases.mjs`
6. `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`

Validate OpenAPI spec:
- Check JSON syntax and OpenAPI 3.0 schema validity for `factory/dashboard/openapi.json`.

---

## 5. Constraints Checklist
- [x] DO NOT touch `agentos/**` or `factory/src/adapters/agentos/**`.
- [x] DO NOT reorganize or move domain code in `factory/lib/`.
- [x] DO NOT modify or delete files under `runs/`.
- [x] Maintain full compatibility with all `factory/tests/...` files.
- [x] Pass all unit and affected Nx tests.

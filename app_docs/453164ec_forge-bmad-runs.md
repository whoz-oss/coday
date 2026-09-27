# Aggregate A8: Forge/BMAD and runs Kotlin port

## What changed

Aggregate A8 is now implemented in `factory-service` under `io.whozoss.factory.forge`, with legacy run compatibility under `io.whozoss.factory.runs`. The implementation ports the Forge/BMAD domain and dashboard-facing APIs without changing the Node control plane or database migrations.

The durable Forge state remains append-only JSONL on the filesystem. `ForgeLedger` parses and validates JSONL and replays it into the same run projection shape, including Epic/Story status, G1/G2/G2-US gates, analysis executions, edits, oracle campaigns, and evidence hashes. `FileForgeLedgerStore` owns file creation, append, parsing, projection listing, and EpicRun creation. Run-store roots and containment policies are represented by `ForgeRoots` and resolved by `ForgeRootsResolver`.

The Forge domain includes:

- Epic frontmatter parsing, schema validation, scope/oracle validation, and SHA-256 hashing (`ForgeSpec`), plus Story-spec inheritance checks (`ForgeStorySpec`).
- G1 human-decision vocabulary, canonical serialization, and evidence-set hashing (`ForgeHumanDecision`).
- G2 and G2-US gate evaluation, idempotency/conflict handling, and ledger recording (`ForgeGateService`).
- Story analysis, plan extraction/validation, scope checks, edit diff validation, oracle execution, front-oracle Nx host resolution, and workflow synchronization (`ForgeStoryOperations`, `StoryOperationService`, `ForgeFrontOracleResolution`, `ForgeWorkflowAdapter`, `WorkflowSync`).

## HTTP surfaces

`ForgeRunController` exposes Forge projections and gate/story operations below `/api/forge/...` and the factory aliases where present, including:

- Forge projection listing at `/api/forge/runs` and `/api/factory/forge/runs`.
- EpicRun creation at `POST /api/factory/forge/runs/create`.
- G1 and G2 reads/evaluation and G1 human decisions.
- Story execution, edit, and oracle list/execute operations.

`LegacyRunController` ports the JSONL workflow run API: list, launch, detail, stop, review-gate read/reply, and deprecated global review-gate routes. Both `/api/runs` and `/api/factory/runs` variants are represented where required. `LegacyRunSseController` adds `/api/runs/{id}/stream` and `/api/factory/runs/{id}/stream` using `SseEmitter`; the controller is tagged `sse`, marks the operations hidden from OpenAPI, and sets the SSE response headers. `RawSseEvent` and the legacy run service provide the raw `tag: sse` framing and live process/output handling.

All Forge, legacy-run, workstream, proxy, and Jira controllers resolve the caller through `TrustContext` and the tenant scope provider. Missing identity fails closed with the existing `TRUST_CONTEXT_UNAVAILABLE` behavior. Forge HTTP helpers centralize namespace requirements and storage failures, while the existing exception handling path is reused so stream-opening errors can be rendered as JSON rather than an SSE frame.

Additional adapters expose:

- `WorkstreamController` and `JdbcWorkstreamRepository`, using `NamedParameterJdbcTemplate` against the already-existing `workstreams` table with tenant scoping.
- `AgentOsProxyController` and `HttpAgentOsProxyClient` for `fetchAgents`, namespace lookup, case events, repository-root resolution, and run-store-root resolution.
- `JiraProxyController` and `HttpJiraClient` behind the `JiraClient` interface. `ForgeProperties` binds AgentOS, run directory/entrypoint, and Jira environment settings. Missing Jira credentials are detected and reported explicitly with the Node-compatible `JIRA_NOT_CONFIGURED` response path.

## Configuration and generated contract

`ForgeConfiguration` wires the AgentOS proxy, Jira client, and legacy run service. `application.yml` adds `factory.forge` bindings for `AGENTOS_URL`, `FACTORY_RUNS_DIR`, `FACTORY_RUN_ENTRY`, `JIRA_BASE_URL`, `JIRA_EMAIL`, and `JIRA_API_TOKEN`, with local defaults for AgentOS and the legacy run launcher.

`factory-service/openapi/factory-openapi.yaml` was regenerated to describe the new Forge, runs, workstreams, AgentOS-proxy, and Jira routes. SSE operations are intentionally not included as generated client operations because they are marked hidden in the controller.

## Verification

The changed test suite covers the main compatibility boundaries, and every new integration test class extends `DomainIntegrationTest`:

- `ForgeLedgerIntegrationTest`: JSONL parsing, file append/projection behavior.
- `ForgeGatesIntegrationTest`: G1 decisions/evidence and G2 evaluation.
- `ForgeStoryOperationsTest` and `ForgeDomainUnitTest`: story phases, plans, scopes, specs, roots, and domain helpers.
- `WorkstreamJdbcRepositoryTest`: tenant-scoped JDBC workstreams.
- `AgentOsProxyMockTest` and `JiraProxyHttpTest`: HTTP adapter/proxy behavior and Jira configuration failures.
- `LegacyRunAndSseHttpTest`: legacy endpoints, SSE framing, and JSON error responses.

Run the factory-service verification from `factory-service` with:

```bash
./gradlew clean test
./gradlew generateOpenApi
```

No migration file is part of this change; the implementation consumes the existing `workstreams` schema and keeps ledger/run state out of PostgreSQL tables.

## Main files

- Domain: `factory-service/src/main/kotlin/io/whozoss/factory/forge/domain/ForgeLedger.kt`, `ForgeSpec.kt`, `ForgeStorySpec.kt`, `ForgeHumanDecision.kt`, `ForgePlan.kt`, `ForgeStoryOperations.kt`, `ForgeFrontOracleResolution.kt`, `ForgeWorkflowAdapter.kt`, `WorkflowSync.kt`, `ForgeRoots.kt`, and `ForgeSupport.kt`.
- Ports/adapters: `forge/port/*`, `forge/infrastructure/FileForgeLedgerStore.kt`, `ForgeRootsResolver.kt`, `ForgeSpecReader.kt`, `HttpAgentOsProxyClient.kt`, `HttpJiraClient.kt`, and `JdbcWorkstreamRepository.kt`.
- Services: `ForgeGateService.kt`, `ForgeRunService.kt`, `StoryOperationService.kt`, and `WorkstreamService.kt`.
- Web: `forge/web/ForgeRunController.kt`, `WorkstreamController.kt`, `AgentOsProxyController.kt`, `JiraProxyController.kt`, `ForgeHttp.kt`, plus `runs/service/LegacyRunService.kt`, `runs/web/LegacyRunController.kt`, `LegacyRunSseController.kt`, and `web/RawSseEvent.kt`.
- Contract/tests: `factory-service/openapi/factory-openapi.yaml`, the changed Kotlin test files, `factory-service/src/main/resources/application.yml`, and `specs/453164ec_forge_bmad_runs_kotlin_port.md`.

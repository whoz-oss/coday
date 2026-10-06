# Aggregate A6 workflow + SSE port

## What changed

Aggregate A6 is now implemented in `factory-service` as a Kotlin/Spring Boot workflow package. The port covers workflow definitions, governed instances, declarative projections, transitions, evidence, human interactions, lifecycle operations, operational metrics, and a namespace-scoped SSE notification hub.

The domain layer in `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/` contains the wire models, validation, lifecycle/status policy, instance materialization, stable error codes, and projection v1/v2 validation. `CanonicalHash.kt` recursively sorts object keys, preserves `null`, keeps array order, emits compact JSON, and computes lowercase SHA-256 values. It exposes definition, start-command, projection, and transition hash helpers matching the Node contract. Projection commands treat `expectedRevision` as a compare-and-swap precondition rather than part of the stored/hash payload.

`WorkflowDefinition.kt` validates and normalizes definitions, including schema/version, safe IDs, responsibilities, dependencies, duplicate IDs, missing dependencies, self-dependencies, and cycles. `WorkflowProjection.kt` validates v1/v2 projections; v2 requires step responsibility metadata. `WorkflowTransitionPolicy.kt` enforces the governed state machine, revision checks, dependency completion, evidence scope, execution attribution, human checkpoints, retries, and responsibility-specific evidence rules.

Persistence is implemented in `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt` and `JdbcWorkflowRepository.kt` using tenant-scoped JDBC access. The repository handles definitions, instances, projections, transitions, code-transition history, evidence, human interactions/events, and timing/retry/aggregate data. `V9__workflow.sql` adds the workflow projection and code-transition persistence surface without changing V1–V8, uses `IF NOT EXISTS`, composite tenant-scoped keys/foreign keys, lifecycle and revision checks, and append-only code-transition storage.

`WorkflowService.kt` coordinates the aggregate operations. Projection publication supports idempotent writes and optimistic revision conflicts. Start resolves a registered definition, materializes the governed instance/projection, and records the canonical start hash. Human interaction replies perform the interaction update/event, evidence append, and workflow transition as one transaction; the integration coverage also verifies rollback on a stale revision. Projection lifecycle changes publish SSE notifications.

## HTTP and SSE surface

`WorkflowController.kt` exposes the workflow collection/detail and projection routes plus start, run, continue, transitions, code transitions, evidence, interactions/replies, restore, purge, retries, timing, and metrics operations. `WorkflowDefinitionController.kt` exposes definition list, lookup, and registration. Success values use `{ "data": ... }`; workflow failures use the shared factory exception envelope and stable workflow error codes. `WorkflowHttp.kt` centralizes request parsing, caller resolution, envelopes, execution validation, and response handling. Both controllers resolve tenant identity through `TrustContext`; absent/unusable trust context is mapped to `401 TRUST_CONTEXT_UNAVAILABLE`.

`WorkflowSseController.kt` serves `GET /api/factory/workflows/stream` and hides the operation from generated OpenAPI with `@Operation(hidden = true)`. `WorkflowSseHub.kt` keeps in-memory, namespace-scoped subscribers and emits the named events `workflow-projection-updated`, `workflow-projection-removed`, `workflow-projection-restored`, and `workflow-projection-purged`. It writes raw frames in Node-compatible form (`event: ...\ndata: ...\n\n`) and schedules `: heartbeat\n\n` every 30 seconds. Response headers disable caching and intermediary buffering.

`factory-service/openapi/factory-openapi.yaml` was expanded with the workflow definition and workflow aggregate operations and envelope schemas; the SSE stream is intentionally not represented as an OpenAPI operation.

## Verification added

All new integration tests extend `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt`:

- `workflow/domain/CanonicalHashTest.kt` locks recursive sorting, null/array behavior, and Node hash vectors.
- `workflow/WorkflowServiceIntegrationTest.kt` covers projection `expectedRevision`, idempotent publication, append-only evidence replay, atomic interaction reply, and transaction rollback.
- `workflow/WorkflowControllerHttpTest.kt` covers trust-context failure, data/error envelopes, definition-backed start/detail, and request validation.
- `workflow/WorkflowSseHttpTest.kt` exercises the real servlet stream and response headers.
- `workflow/sse/WorkflowSseHubTest.kt` verifies exact named-event frames, lifecycle events, namespace filtering, and heartbeat framing.

To verify the implementation, run `cd factory-service && ./gradlew clean test`. The changed files add the tests and implementation, but the captured change does not include a test-run result, so successful execution should be confirmed separately.

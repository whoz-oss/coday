# Kotlin delivery aggregate port

## What changed

Aggregate A5 **delivery** is now implemented in `factory-service` as a Kotlin/Spring Boot control plane, without changes to the Node implementation or migrations V1–V7. The port adds:

- `V8__delivery.sql`, defining tenant-scoped `deliveries` snapshots and an append-only `delivery_journal`, composite keys/foreign key cascade, lookup indexes, revision checks, and the existing `set_updated_at()` trigger on snapshots.
- Pure domain rules for canonical JSON/SHA-256 hashing, delivery-definition validation, the five ordered stages, evidence-gated promotion, scope/semantic hashes, operation request normalization and identity derivation, operation state transitions, rollback constraints, and stable machine-readable error codes.
- A transactional `SqlDeliveryRepository` using `NamedParameterJdbcTemplate`. It persists JSONB snapshots, appends journal records, applies dot-notation patches, uses optimistic revision checks, projects operations/rollback requests, and handles idempotent replays versus `IDEMPOTENCY_KEY_COLLISION`.
- Application services for delivery resolution, status, checkpoint, push, pull-request, evidence, promotion, deployment, verification, reconciliation, rollback requests, approvals, execution, and rollback verification. Delivery resolution binds the snapshot to the trusted work environment, workflow, namespace, case, runtime, and reconciled Git head.
- Ports and default adapters for evidence, Git checkpoint/push, pull requests, and trusted deployment targets. The default PR adapter returns `PULL_REQUEST_NOT_CONFIGURED`; Git and target implementations are deterministic/configurable stubs for the control-plane boundary.
- REST endpoints under `/api/factory/workflows/{workflowId}/delivery`. Controllers require the resolved trust context and namespace/case identity, return `401 TRUST_CONTEXT_UNAVAILABLE` when unavailable, use `{ "data": ... }` success envelopes, and rely on the shared exception handler for `{ "error": { "code": ... } }` failures.
- Generated OpenAPI entries for delivery and delivery-operation routes, including checkpoint, push, pull request, promotion, evidence, deployment, verification, reconciliation, rollback lifecycle, and status.

## Main files

Implementation is under `factory-service/src/main/kotlin/io/whozoss/factory/delivery/`:

- `domain/CanonicalHash.kt`, `DeliveryDefinition.kt`, `DeliveryPolicy.kt`, `DeliveryOperationDefinition.kt`, `DeliveryOperationPolicy.kt`, and `DeliveryExceptions.kt` contain the rules and error vocabulary.
- `persistence/DeliveryRepository.kt`, `DeliverySnapshots.kt`, and `SqlDeliveryRepository.kt` define the persistence port, snapshot checks, and SQL adapter.
- `port/DeliveryEvidenceStore.kt`, `DeliveryGitControlPlane.kt`, `DeliveryPullRequestAdapter.kt`, and `DeliveryTargetRegistry.kt` define evidence, Git, PR, and target boundaries.
- `service/DeliveryService.kt` and `DeliveryOperationService.kt` orchestrate the aggregate and operation control plane.
- `web/DeliveryController.kt` and `DeliveryOperationController.kt` expose the HTTP API; `config/DeliveryProperties.kt` holds trusted PR configuration.

The schema is in `factory-service/src/main/resources/db/migration/V8__delivery.sql`. `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt` now clears delivery tables and in-memory delivery registries as part of the shared test reset.

## Verification and usage

The integration tests all extend `DomainIntegrationTest` and cover SQL snapshot/journal behavior, idempotency and optimistic locking, dot-notation updates, promotion policy, operation/rollback flows, target lookup, PR-not-configured behavior, trust-context failures, HTTP envelopes, evidence-gated promotion, and Git checkpointing. Relevant suites are:

- `DeliveryRepositoryIntegrationTest.kt`
- `DeliveryOperationIntegrationTest.kt`
- `DeliveryControllerHttpTest.kt`
- domain unit tests under `factory-service/src/test/kotlin/io/whozoss/factory/delivery/domain/`

From `factory-service/`, run the full verification requested by the change:

```bash
./gradlew clean test
./gradlew generateOpenApiDocs --no-configuration-cache
./check-openapi-spec.sh
```

The checked-in generated contract is `factory-service/openapi/factory-openapi.yaml`.

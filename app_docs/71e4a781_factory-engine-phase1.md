# Factory engine-first Phase 1

## What changed

Phase 1 now covers dynamic workflow revisions, capability-bound agent results, and asynchronous outbox continuation in `factory-service`.

- `SessionRunService` reads the active instance revision once at the start of `runSession` and passes it through agent-step, failure, and human-resolution transitions. `WorkflowTransitionRequest.expectedRevision` is therefore no longer hardcoded to `1`, avoiding stale-revision/409 conflicts on later turns.
- Agent steps create a fresh AgentOS case id and issue a single-use result capability through `AgentStepResultService.issue(...)` when the attempt starts. The attempt id, capability token, and case id flow through `CapabilityResolver`, `AgentOsAgentTurnCapability`, and `HttpAgentOsProxyClient`. The proxy sends them both as case-creation metadata and `X-Factory-*` headers, and repeats the headers when posting the turn message.
- `POST /api/factory/step-result-bindings` is now a real Spring REST endpoint. It obtains tenant scope from the trusted context, accepts a capability in the request body or `Authorization: Bearer` header, verifies it without redeeming it, optionally checks a declared attempt id, and returns only the non-sensitive binding projection.
- A conditional scheduled worker drains pending outbox events by organization. For `result_submitted`, it reads the continuation coordinates, resolves the namespace repository root through AgentOS (falling back to `.`), and invokes `SessionRunService.runSession` after draining. Failures are logged per organization/continuation so one error does not stop subsequent work. The drain is disabled in test and OpenAPI-generation contexts and is configurable in `application.yml`.

The existing JDBC persistence remains in place. The repository adds token lookup and stores namespace/workflow/step/case continuation coordinates in the outbox payload; no schema or migration changes are included.

## Files carrying the change

### Revision handling
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`

### Capability, binding, and AgentOS transport
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/AgentStepResultRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcAgentStepResultRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepResultService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/FactoryStepResultBindingController.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/FactoryStepResultBindingDtos.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentTurnCapability.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentOsAgentTurnCapability.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityResolver.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/AgentOsProxyClient.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/HttpAgentOsProxyClient.kt`
- `factory-service/src/main/resources/application-openapi.yml`

### Outbox scheduling and continuation
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainWorker.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/OutboxSchedulingConfiguration.kt`
- `factory-service/src/main/resources/application.yml`
- `factory-service/src/test/kotlin/io/whozoss/factory/PostgresContainerSpec.kt`

## How to verify or use it

- Run the factory-service Kotlin test target through Nx. The focused coverage added in this change is in:
  - `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/OutboxDrainWorkerTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionCapabilityIssuanceTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityResolverTest.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/proxy/AgentOsAgentTurnTest.kt`
- The proxy test verifies case creation and message requests carry the attempt id and capability token in the body/headers. The capability tests verify issuance and forwarding, including the no-issuer fallback. The worker tests verify scoped `result_submitted` continuation, ignored unrelated events, missing-coordinate handling, and failure isolation.
- In a running service, set `FACTORY_OUTBOX_DRAIN_ENABLED=true` (the default in `application.yml`) and optionally tune `FACTORY_OUTBOX_DRAIN_INTERVAL_MS` and `FACTORY_OUTBOX_DRAIN_INITIAL_DELAY_MS`. Set it to `false` for fixture-sensitive tests or contexts without the outbox table.
- To verify a result binding, call `POST /api/factory/step-result-bindings` with the trusted tenant context and either `{ "capabilityToken": "...", "attemptId": "..." }` or an `Authorization: Bearer ...` header. A successful response contains the bound attempt/workflow/step/case identity, expiry, and submission budget; the token itself is not returned.

The implementation plan and verification notes are recorded in `specs/71e4a781_factory_engine_first_phase1.md`.

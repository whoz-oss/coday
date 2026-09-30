# AgentOS SSE contract characterization

## What changed

This change freezes the current AgentOS SSE behavior before a Factory → AgentOS bridge is implemented. It adds a source-backed contract document, an automated characterization suite, and a test-memory adjustment; no AgentOS runtime execution behavior was changed.

## Contract and recommendations

`app_docs/agentos-sse-contract.md` is the main reference. It documents the exact endpoint (`GET /api/cases/{caseId}/events`), Spring permission check and authentication path, the single `case-event` SSE channel, UUID `CaseEvent.id` values, and the JSON `type` discriminator. It also records the actual durability boundary: ordinary case events are persisted and replayable, while `TransientCaseEvent` variants are live-only.

The document establishes that durable replay is ordered by `timestamp ASC, id ASC`, live delivery is FIFO, replay precedes the live drain, and `includePreviousEvents=true` replays the complete history. AgentOS currently has no `Last-Event-ID` handling, cursor parameter, or sequence number; reconnects therefore replay from the beginning. A per-connection event-id set prevents history/live duplication, but does not survive reconnects. Connections made after the runtime disappears replay the stored snapshot and complete. Runtime or connection saturation closes with an error, while heartbeat comments are emitted at the configured interval.

It also analyzes multi-turn identity and the PF4J bridge components, including lease CAS/acquire, acknowledge/release/invalidate semantics, lifecycle cleanup, execution-context provision, and result submission. The documented gaps are explicit: bridge bindings and pending checkpoints are in-memory, the host does not wire the binding transport, the plugin is not active by default, and the AgentOS core has no turn/attempt idempotency or durable SSE cursor. The recommendation is to use explicit full replay plus bridge-side durable deduplication by `eventId`, with REST reconciliation as a fallback, and to keep core idempotency changes out of phase one. Attempt idempotency should remain at the Factory boundary; cursor support is described only as an optional future core evolution.

## Tests and verification

`agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseEvent/CaseEventSseCharacterizationSpec.kt` adds eight Kotest characterization cases around the real `CaseEventSseController` and `DefaultCaseEventEmitter`: pre-execution live connection, mid-run history then live delivery, the `includePreviousEvents=false` path, post-termination replay and completion, reconnect full replay, event-id deduplication, saturation failure, and heartbeat comments. The test reads pre-servlet `SseEmitter` frames through reflection so it can freeze the observable contract without changing runtime code or requiring Tomcat. The contract document records the suite as 8 tests with 0 failures.

`agentos/agentos-service/build.gradle.kts` changes the test JVM heap from `2g` to `1536m`, with comments explaining the measured suite peak and constrained build sandbox.

`specs/7eeba80f_agentos_sse_contract_characterization.md` captures the task plan, acceptance criteria, investigation points, and the resulting characterization findings/recommendations.

## How to use

- Read `app_docs/agentos-sse-contract.md` before implementing the bridge; its appendix maps each guarantee to the relevant Kotlin/Spring file and method.
- Run the AgentOS service tests through the repository’s normal Nx/Gradle test target; the characterization class is under the `agentos-service` test source set.
- Treat any failure in `CaseEventSseCharacterizationSpec` as a contract change requiring review, especially after Spring or AgentOS SSE changes. The tests intentionally couple to Spring’s pre-initialization emitter buffering to detect such changes.

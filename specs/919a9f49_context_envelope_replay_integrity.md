# Plan Lot D : Context Envelope & Replay Integrity

## Overview
This plan specifies the implementation for Lot D (Context Envelope & Replay Integrity) in Coday / Factory (`factory/factory-service` and `factory/factory-forge-plugin`).
The goal is to formalize, persist, freeze, and replay context envelopes for execution attempts, resolve workstream <-> namespace mappings explicitly, adapt the context builder in `factory-forge-plugin`, ensure proper JSON serialization/deserialization, and port the `expected_amendment_seq` schema field (without implementing Lot E amendment logic).

---

## 1. Objectives & Architectural Requirements

### Requirement 1: Frozen Context Envelope Per Attempt
- Context envelopes generated for an execution attempt must be frozen, persisted on the attempt record (`DurableAgentAttempt`), and replayed verbatim during retries/re-execution instead of re-deriving dynamic inputs.
- Target entry points in `factory-service`:
  - `CapabilityExecutionService.buildBrief`: Build context brief or retrieve the frozen context envelope for an attempt.
  - `CreateWorkflowRunService`: Freeze/persist initial context parameters or initial request context upon workflow run creation.
  - `DurableAgentAttempt`: Extend domain model, persistence node (`DurableAgentAttemptNode`), and DTO (`DurableAgentAttemptDto`) with context envelope / context brief storage and `expected_amendment_seq`.

### Requirement 2: Explicit Workstream <-> Namespace Resolution
- Clarify and make explicit the mapping/resolution between `workstream` and `namespace`.
- Ensure `WorkstreamService`, `WorkflowController`, `ControllerCaseService`, and `CapabilityExecutionService` consistently resolve `namespaceId` from `workstream` (fallback to `Workstream.namespaceId` or caller `TrustContext` when explicit `namespaceId` is not provided).

### Requirement 3: Context Builder in `factory-forge-plugin`
- Adapt/implement the context builder in `factory-forge-plugin` (`ForgeStoryOperations.buildBrief` or context builder service) to support structured context envelopes, serialization, and consistency with Factory core envelope schemas.

### Requirement 4: Correct JSON Serialization & Deserialization
- Ensure all context envelope objects, map properties, and nested structures use Jackson `ObjectMapper` / `CanonicalJsonHash` safely.
- Verify serialization across Neo4j persistence layers (`DurableAgentAttemptNode`, `WorkflowEvidenceNode`, etc.) and REST API DTOs.

### Requirement 5: Port `expected_amendment_seq`
- Add the `expected_amendment_seq: Long? = null` (or `Int? = null`) field to relevant data structures, DTOs, domain models, and persistence nodes (`DurableAgentAttempt`, `DurableAgentAttemptDto`, `DurableAgentAttemptNode`, `WorkflowStartCommand` / `CreateWorkflowRunService.Command` as needed).
- Strictly **DO NOT** implement amendment processing/business logic (reserved for Lot E).

---

## 2. Implementation Steps

### Task 1: Domain Model & Persistence Updates (`expected_amendment_seq` & Context Envelope)
- **Files to modify**:
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttempt.kt`:
    - Add `val contextEnvelope: String? = null` (or `Map<String, Any?>?` rendered as JSON)
    - Add `val expectedAmendmentSeq: Long? = null`
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt`:
    - Add `contextEnvelope` and `expectedAmendmentSeq` fields to DTO and `toDto()` extension function.
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/DurableAgentAttemptNode.kt`:
    - Add `@Convert` or String fields for `contextEnvelope` and `expectedAmendmentSeq`.
    - Update `toDomain()` and `fromDomain()` mapping functions.
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt` & `SpringDataNeo4jDurableAgentAttemptRepository.kt`:
    - Ensure Cypher queries and node mappings persist and load `contextEnvelope` and `expectedAmendmentSeq`.
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/CreateWorkflowRunService.kt` & `WorkflowStartCommand.kt`:
    - Add `expectedAmendmentSeq: Long? = null` to `Command` and `WorkflowStartCommand` for schema completeness.

### Task 2: Attempt Context Freeze & Replay in `CapabilityExecutionService` & `CreateWorkflowRunService`
- **Files to modify**:
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`:
    - In `buildBrief(...)` / `resolveAgentViaAdapter(...)`:
      - Check if the attempt already has a frozen `brief` / `contextEnvelope` persisted in `DurableAgentAttempt`.
      - If present, reuse the frozen brief/envelope directly to guarantee replay integrity.
      - If absent (first attempt), build the brief, construct/freeze the context envelope, and persist it when registering the attempt.
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/CreateWorkflowRunService.kt`:
    - Ensure initial request/parameters and context envelope are frozen and passed down to `workflowService.start`.

### Task 3: Workstream <-> Namespace Explicit Resolution
- **Files to modify**:
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workstream/WorkstreamService.kt`:
    - Provide a helper method `resolveNamespaceId(scope: TenantScope, requestedNamespaceId: String?): String` that retrieves `Workstream.namespaceId` if `requestedNamespaceId` is blank, falling back to `DEFAULT_NAMESPACE_ID` if unset.
  - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` and controllers (`WorkflowController`, `ControllerCaseService`):
    - Explicitly use `workstreamService.resolveNamespaceId(...)` wherever workstream and namespace mapping takes place.

### Task 4: Context Builder Adaptation in `factory-forge-plugin`
- **Files to modify**:
  - `factory/factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/domain/ForgeStoryOperations.kt`:
    - Update `buildBrief` and related context helper methods to structure and return context envelopes / briefs that conform to Jackson JSON serialization.
  - `factory/factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/service/StoryOperationService.kt`:
    - Connect story context construction with frozen envelope expectations.

### Task 5: JSON Serialization & Unit/Integration Tests
- **Files to create/modify**:
  - `factory/factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionContextEnvelopeTest.kt`:
    - Unit/integration test verifying that attempt context envelopes are frozen upon registration and replayed unchanged on retry attempts.
  - `factory/factory-service/src/test/kotlin/io/whozoss/factory/workstream/WorkstreamNamespaceResolutionTest.kt`:
    - Unit test verifying explicit workstream <-> namespace resolution logic.
  - `factory/factory-forge-plugin/src/test/kotlin/io/whozoss/factory/forge/domain/ForgeStoryOperationsTest.kt`:
    - Test JSON serialization and brief construction in `factory-forge-plugin`.

---

## 3. Verification & Quality Checks

Run target Nx tests after implementation:
1. `pnpm nx test factory-forge-plugin`
2. `pnpm nx test factory-service`

Ensure strict compliance with constraints:
- Do NOT touch AgentOS execution adapter (`AgentOsExecutionAdapter`, `DefaultAgentOsExecutionAdapter`).
- Do NOT touch UI / frontend code.
- Do NOT implement Lot E amendment logic (only include the schema field `expected_amendment_seq`).

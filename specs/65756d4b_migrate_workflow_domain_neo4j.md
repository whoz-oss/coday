# Plan: Migrate Workflow Domain Aggregate to Embedded Neo4j in factory-service

## Overview
This task migrates Phase 3: the Workflow domain aggregate (including definitions, instances, projections, step states, transitions, code transitions, evidence, and human interactions/events) from relational/JDBC persistence to embedded Neo4j in `factory-service`.

Following the methodology guidelines established in previous phases:
1. **CREATE ONLY** new Neo4j node entities, Spring Data Neo4j repositories, and mapping repository adapters (`Neo4jWorkflowRepository`, `Neo4jWorkflowEvidenceRepository`, `Neo4jHumanInteractionRepository`).
2. **DO NOT DELETE** `JdbcWorkflowRepository.kt`. Keep it in place as inactive (remove `@Repository` or register Neo4j implementations as `@Primary` so Spring Dependency Injection chooses the Neo4j beans).
3. Update `Neo4jPersistenceConfiguration` and `Neo4jSchemaInitializer`.
4. Ensure all integration tests run and pass against embedded Neo4j.

---

## Domain Models and Scope to Migrate
Domain entities in `io.whozoss.factory.workflow.domain`:
- `WorkflowDefinitionRecord` (`workflow_definitions`) -> Node entity `WorkflowDefinitionNode`
- `WorkflowInstanceRecord` (`workflow_instances`) -> Node entity `WorkflowInstanceNode`
- `WorkflowProjectionRecord` (`workflow_projections`) -> Node entity `WorkflowProjectionNode`
- `WorkflowStepStateRecord` (`workflow_step_states`) -> Node entity `WorkflowStepStateNode`
- Workflow Transitions (`workflow_transitions`) -> Node entity `WorkflowTransitionNode`
- Workflow Code Transitions (`workflow_code_transitions`) -> Node entity `WorkflowCodeTransitionNode`
- `WorkflowEvidenceItem` (`workflow_evidence`) -> Node entity `WorkflowEvidenceNode`
- `HumanInteractionRecord` (`human_interactions`) -> Node entity `HumanInteractionNode`
- `HumanInteractionEventRecord` (`human_interaction_events`) -> Node entity `HumanInteractionEventNode`

Domain Ports in `io.whozoss.factory.workflow.persistence.WorkflowRepository.kt`:
- `WorkflowRepository`
- `WorkflowEvidenceRepository`
- `HumanInteractionRepository`

---

## Files to Create

### 1. Node Entities (`factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/`)
- `WorkflowDefinitionNode.kt`: `@Node("WorkflowDefinition")`
  - Composite Key `@Id val id: String` (`$organizationId|$workflowType|$version`)
  - Fields: `organizationId`, `workflowType`, `version`, `definitionHash`, `definitionJson` (serialized map), `createdAt`, `updatedAt`
- `WorkflowInstanceNode.kt`: `@Node("WorkflowInstance")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `@Version val revision: Int?`, `status`, `creationCommandHash`, `instanceJson`, `projectionJson`, `createdAt`, `updatedAt`
- `WorkflowProjectionNode.kt`: `@Node("WorkflowProjection")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `schemaVersion`, `@Version val revision: Int?`, `projectionHash`, `status`, `projectionJson`, `instanceJson`, `governanceMode`, `definitionVersion`, `definitionHash`, `relationsJson`, `controllerExecution`, `lifecycleState`, `createdAt`, `updatedAt`
- `WorkflowStepStateNode.kt`: `@Node("WorkflowStepState")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `stepId`, `revision: Int`, `status`, `payloadJson`, `createdAt`, `updatedAt`
- `WorkflowTransitionNode.kt`: `@Node("WorkflowTransition")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$transitionId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `transitionId`, `fromStepId`, `toStepId`, `eventName`, `payloadJson`, `createdAt`
- `WorkflowCodeTransitionNode.kt`: `@Node("WorkflowCodeTransition")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$codeTransitionId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `codeTransitionId`, `stepId`, `outcome`, `exitCode`, `payloadJson`, `createdAt`
- `WorkflowEvidenceNode.kt`: `@Node("WorkflowEvidence")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$evidenceId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `evidenceId`, `evidenceType`, `source`, `producer`, `payloadJson`, `idempotencyKey`, `stepId`, `createdAt`
- `HumanInteractionNode.kt`: `@Node("HumanInteraction")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `interactionId`, `interactionType`, `status`, `revision: Int`, `payloadJson`, `createdAt`, `updatedAt`
- `HumanInteractionEventNode.kt`: `@Node("HumanInteractionEvent")`
  - Composite Key `@Id val id: String` (`$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId|$eventId`)
  - Fields: `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `interactionId`, `eventId`, `eventType`, `actorId`, `payloadJson`, `createdAt`

### 2. Spring Data Neo4j Repositories (`factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/`)
- `SpringDataNeo4jWorkflowRepositories.kt` (or individual `SpringDataNeo4j*.kt` files):
  - `SpringDataNeo4jWorkflowDefinitionRepository : Neo4jRepository<WorkflowDefinitionNode, String>`
    - `findAllByOrganizationId(organizationId: String): List<WorkflowDefinitionNode>`
  - `SpringDataNeo4jWorkflowInstanceRepository : Neo4jRepository<WorkflowInstanceNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndStatus(org: String, ws: String, ns: String, status: String): List<WorkflowInstanceNode>`
    - Custom Cypher methods for compare-and-swap update / status update.
  - `SpringDataNeo4jWorkflowProjectionRepository : Neo4jRepository<WorkflowProjectionNode, String>`
    - Custom Cypher / method queries for listProjections by scope/namespace/lifecycleState.
  - `SpringDataNeo4jWorkflowStepStateRepository : Neo4jRepository<WorkflowStepStateNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAscStepIdAsc(...)`
  - `SpringDataNeo4jWorkflowTransitionRepository : Neo4jRepository<WorkflowTransitionNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAsc(...)`
  - `SpringDataNeo4jWorkflowCodeTransitionRepository : Neo4jRepository<WorkflowCodeTransitionNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAsc(...)`
  - `SpringDataNeo4jWorkflowEvidenceRepository : Neo4jRepository<WorkflowEvidenceNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAsc(...)`
    - `findByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdAndIdempotencyKey(...)`
  - `SpringDataNeo4jHumanInteractionRepository : Neo4jRepository<HumanInteractionNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAsc(...)`
  - `SpringDataNeo4jHumanInteractionEventRepository : Neo4jRepository<HumanInteractionEventNode, String>`
    - `findAllByOrganizationIdAndWorkstreamIdAndNamespaceIdAndWorkflowIdOrderByCreatedAtAsc(...)`

### 3. Neo4j Repository Implementations / Domain Adapters
- `Neo4jWorkflowRepository.kt`: `@Repository` `@Primary` implementing `WorkflowRepository`
- `Neo4jWorkflowEvidenceRepository.kt`: `@Repository` `@Primary` implementing `WorkflowEvidenceRepository`
- `Neo4jHumanInteractionRepository.kt`: `@Repository` `@Primary` implementing `HumanInteractionRepository`

---

## Files to Update

### 1. `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
Add `io.whozoss.factory.workflow.persistence` to the `@EnableNeo4jRepositories` `basePackages` list.

### 2. `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`
Add constraints and indexes for workflow nodes:
- Constraints on `@Id` for each node: `WorkflowDefinition`, `WorkflowInstance`, `WorkflowProjection`, `WorkflowStepState`, `WorkflowTransition`, `WorkflowCodeTransition`, `WorkflowEvidence`, `HumanInteraction`, `HumanInteractionEvent`.
- Indexes for lookup queries (e.g., `WorkflowInstance` by scope/namespace, `WorkflowProjection` by scope/lifecycleState, `WorkflowEvidence` idempotency key, `HumanInteraction` status).

### 3. Existing Integration Tests
Convert tests extending `DomainIntegrationTest` (PostgreSQL) to extend `Neo4jDomainIntegrationTest` or `Neo4jIntegrationTest` if applicable, ensuring all tests run against embedded Neo4j:
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowServiceIntegrationTest.kt` (change inheritance from `DomainIntegrationTest` to `Neo4jIntegrationTest` / `Neo4jDomainIntegrationTest`).
- Check all other workflow tests (`SessionSequencerIntegrationTest`, `SessionDefinitionImportIntegrationTest`, `WorkflowControllerHttpTest`, `WorkflowDefinitionAdminIntegrationTest`) to confirm they pass.

---

## Key Implementation Invariants & Details

1. **Composite Identifiers**:
   - `WorkflowDefinitionNode`: `$organizationId|$workflowType|$version`
   - `WorkflowInstanceNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId`
   - `WorkflowProjectionNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId`
   - `WorkflowStepStateNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId`
   - `WorkflowTransitionNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$transitionId`
   - `WorkflowCodeTransitionNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$codeTransitionId`
   - `WorkflowEvidenceNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$evidenceId`
   - `HumanInteractionNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId`
   - `HumanInteractionEventNode`: `$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId|$eventId`

2. **Optimistic Locking & Revisions**:
   - For `updateInstance`, `updateStepStatus`, `update` (HumanInteraction), check `expectedRevision`. Perform CAS updates or handle revision increments identically to `JdbcWorkflowRepository`.
   - For `publishProjection`, support expected revision check (0 for creation, match current revision for update) and handle `ProjectionPublishResult.Changed`, `Idempotent`, `Conflict`.

3. **Serializations**:
   - Convert complex domain maps (`definition`, `instance`, `projection`, `payload`, `relations`, `facts`) to/from JSON strings using Jackson `ObjectMapper`, matching the pattern used across factory Neo4j node entities.

4. **Spring DI Wiring**:
   - Mark `Neo4jWorkflowRepository`, `Neo4jWorkflowEvidenceRepository`, `Neo4jHumanInteractionRepository` as `@Primary` `@Repository`.
   - Keep `JdbcWorkflowRepository.kt` in place without `@Primary` (or remove `@Repository` if needed to avoid duplicate un-qualifiable beans if not `@Primary`).

---

## Verification Plan

### Test Commands to Run:
```bash
# Run factory-service unit and integration tests
pnpm nx test factory-service
```
Specifically test workflow components:
```bash
./gradlew test --tests "io.whozoss.factory.workflow.*"
```

---

## Step-by-Step Task Breakdown

1. **Step 1: Create Workflow Node Entities**
   - Create Node data classes in `io.whozoss.factory.workflow.persistence`.
2. **Step 2: Create Spring Data Neo4j Repositories**
   - Implement SDN repository interfaces in `io.whozoss.factory.workflow.persistence`.
3. **Step 3: Implement Domain Port Adapters**
   - Implement `Neo4jWorkflowRepository`, `Neo4jWorkflowEvidenceRepository`, and `Neo4jHumanInteractionRepository` with `@Primary`.
4. **Step 4: Update Configuration & Initializer**
   - Update `Neo4jPersistenceConfiguration` with basePackage `io.whozoss.factory.workflow.persistence`.
   - Update `Neo4jSchemaInitializer` with Cypher constraints and indexes.
5. **Step 5: Update Integration Tests**
   - Update `WorkflowServiceIntegrationTest` and related tests to inherit from `Neo4jIntegrationTest`.
6. **Step 6: Verification**
   - Run tests to confirm zero regressions and 100% pass rate.

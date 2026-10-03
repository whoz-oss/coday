# Plan: Factory Workflow Governed Actions, Blockers, and Cost Control Proxy Endpoints

## Executive Summary
This plan details the implementation in `factory-service` to make it the authority for governed workflow actions and blockers (`GET /api/factory/workflows/{workflowId}/actions`), and add cost control proxy endpoints (`POST /api/factory/workflows/{workflowId}/cost/continue` and `POST /api/factory/workflows/{workflowId}/cost/stop`).

## Files to Touch and New Files

1. **New Domain/DTO Models**:
   - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowActionsModels.kt`
     - DTOs for `AllowedActionDto`, `WorkflowBlockerDto`, `WorkflowActionsResponseDto`, and request body DTOs (`WorkflowCostControlRequestDto`).

2. **New Service**:
   - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowActionsService.kt`
     - Evaluates state derived purely from projection steps, open human interactions, durable agent attempts, and `realCost` aggregate/paused status.
     - Computes list of `allowedActions` and active `blockers`.

3. **AgentOS Proxy Client updates**:
   - `factory-service/src/main/kotlin/io/whozoss/factory/proxy/AgentOsProxyClient.kt`
     - Add `continueRunCost(caseId: String, expectedThreshold: Double? = null, externalUserId: String? = null): Boolean`
     - Add `stopRunCost(caseId: String, externalUserId: String? = null): Boolean`
   - `factory-service/src/main/kotlin/io/whozoss/factory/proxy/HttpAgentOsProxyClient.kt`
     - Implement `continueRunCost` calling `POST /api/cases/{caseId}/run-cost/continue`
     - Implement `stopRunCost` calling `POST /api/cases/{caseId}/run-cost/stop`

4. **Controller Updates**:
   - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`
     - Add `GET /api/factory/workflows/{workflowId}/actions`
     - Add `POST /api/factory/workflows/{workflowId}/cost/continue`
     - Add `POST /api/factory/workflows/{workflowId}/cost/stop`

5. **Testing**:
   - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowActionsServiceTest.kt`
   - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowActionsControllerTest.kt`
   - `factory-service/src/test/kotlin/io/whozoss/factory/proxy/AgentOsProxyCostControlTest.kt`

## Detailed Implementation Steps

### Step 1: DTO Models (`WorkflowActionsModels.kt`)
- `AllowedActionDto`:
  - `type`: String (`"reply"` | `"retry"` | `"cancel_attempt"` | `"continue_cost"` | `"stop_cost"`)
  - Target IDs (nullable): `interactionId`, `stepId`, `attemptId`, `caseId`, `questionEventId`
  - `expectedRevision`: Int
  - `label`: String? (optional label/metadata)
- `WorkflowBlockerDto`:
  - `code`: String (`"WAITING_HUMAN_INTERACTION"`, `"STEP_BLOCKED"`, `"ATTEMPT_FAILED"`, `"REAL_COST_PAUSED"`, `"VERIFICATION_FAILED"`, `"UNKNOWN_RUNTIME"`)
  - `stepId`: String?
  - `message`: String
- `WorkflowActionsResponseDto`:
  - `allowedActions`: List<AllowedActionDto>
  - `blockers`: List<WorkflowBlockerDto>
- `WorkflowCostControlRequestDto`:
  - `expectedThreshold`: Double?
  - `caseId`: String?
  - `namespaceId`: String?

### Step 2: AgentOS Proxy Client Updates
- Update `AgentOsProxyClient` interface and `HttpAgentOsProxyClient` implementation to support `continueRunCost` and `stopRunCost`.
- Handle AgentOS HTTP calls cleanly, returning boolean success or throwing `AgentOsUnavailableException` / handling errors.

### Step 3: WorkflowActionsService Implementation
- Compute `allowedActions` & `blockers` based on:
  - Open human interactions -> `reply` action + `WAITING_HUMAN_INTERACTION` blocker.
  - Blocked steps -> `retry` action + `STEP_BLOCKED` blocker.
  - Active attempts -> `cancel_attempt` action.
  - Real cost aggregate (`paused` state) -> `continue_cost` / `stop_cost` actions + `REAL_COST_PAUSED` blocker.
  - Verification failures or unknown runtime -> `VERIFICATION_FAILED` or `UNKNOWN_RUNTIME` blockers.
- Include `expectedRevision` from workflow projection / interaction revision.

### Step 4: Controller Endpoints in WorkflowController
- `GET /api/factory/workflows/{workflowId}/actions`:
  - Resolve caller with `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)`.
  - Delegate to `WorkflowActionsService` (or `WorkflowService`).
  - Return `WorkflowDataEnvelope(...)`.
- `POST /api/factory/workflows/{workflowId}/cost/continue` & `stop`:
  - Resolve caller identity.
  - Resolve target caseId(s) from workflow instance and durable agent attempts.
  - Call proxy `continueRunCost` / `stopRunCost`.
  - If AgentOS proxy client is null or disabled / unavailable, catch and throw standard `FactoryException(HttpStatus.SERVICE_UNAVAILABLE.value(), "SERVICE_UNAVAILABLE", "Usage tracking is disabled")` yielding clean 503 response.

### Step 5: Unit & Integration Testing
- Test calculation of `allowedActions` and `blockers` under various states (open interaction, blocked step, paused cost, active attempt).
- Test presence of `expectedRevision`.
- Test 503 error handling when AgentOS proxy is disabled or unavailable.
- Run tests via `pnpm nx test factory-service` or `./gradlew test` in `factory-service`.

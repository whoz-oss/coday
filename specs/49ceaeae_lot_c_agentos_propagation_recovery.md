# Implementation Plan: Lot C - PROPAGATION ET RÉCUPÉRATION AgentOS de la famille de cases

This plan specifies the implementation for Lot C in `factory/factory-service`, introducing AgentOS case family (`parentCaseId`) propagation, deterministic cold-cache recovery with remote adoption/rejection, and full end-to-end preservation across nominal dispatch, recovery, and retry flows.

## 1. Overview & Architectural Goals

Lot B established `rootCaseId` and `parentCaseId` persistence on `DurableAgentAttempt` and `WorkflowInstance`. Lot C connects `AgentOsExecutionAdapter`, `CapabilityExecutionService`, and `BridgeRecoveryWorker` so that:
1. `TrustedCaseBinding` and `ExecutionRecord` carry `parentCaseId`.
2. POST `/api/cases` sends `parentCaseId` when present (`if (!binding.parentCaseId.isNullOrBlank()) body["parentCaseId"] = binding.parentCaseId`).
3. `DefaultAgentOsExecutionAdapter` implements deterministic cold-cache recovery when its in-memory map `executions` is cold or missing an entry for a given `caseId`:
   - Checks `GET /api/cases/{caseId}` against AgentOS.
   - If present (200 OK): validates compatibility (`namespaceId` match, `parentCaseId` match against AgentOS response's `parentCaseId` or `parentId`).
     - Compatible: adopts existing remote case, caches it in `executions`, registers in `ActiveCaseRegistry`, and returns `CaseHandle(caseId, namespaceId, recovered = true)`.
     - Incompatible: rejects with `IllegalStateException` ("Case mismatch / non-adoptable existing case").
   - If missing (404 Not Found): proceeds to POST `/api/cases`.
   - On 409 Conflict / POST race: re-checks/adopts remote case if compatible, never duplicates or changes the `caseId`.
4. Nominal dispatch (`CapabilityExecutionService`), business retry, and technical recovery (`BridgeRecoveryWorker`) construct `TrustedCaseBinding` with `parentCaseId` populated from `reservation.parentCaseId` / `attempt.parentCaseId`.

---

## 2. Affected Files & Direct Scope

All changes are strictly contained inside `factory/factory-service/`:

### Core Production Files to Modify:
1. `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/TrustedCaseBinding.kt`
2. `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsExecutionAdapter.kt`
3. `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/DefaultAgentOsExecutionAdapter.kt`
4. `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
5. `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeRecoveryWorker.kt`

### Test Files to Modify / Create:
1. `factory/factory-service/src/test/kotlin/io/whozoss/factory/adapter/agentos/DefaultAgentOsExecutionAdapterTest.kt`
2. `factory/factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/BridgeRecoveryWorkerTest.kt`
3. `factory/factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionIntegrationTest.kt`
4. `factory/factory-service/src/test/kotlin/io/whozoss/factory/capability/DurableCaseFamilyIntegrationTest.kt` (if applicable)

---

## 3. Step-by-Step Implementation Details

### Step 1: Data Contract Updates (`TrustedCaseBinding.kt`)
Add optional `parentCaseId` field with default `null`:
```kotlin
data class TrustedCaseBinding(
    val caseId: String,
    val attemptId: String,
    val namespaceId: String? = null,
    val parentCaseId: String? = null,
    val runtimeId: String? = null,
    val capabilityToken: String? = null,
    val agentName: String? = null,
    val environmentRef: String? = null,
    val environmentRevision: Int? = null,
    val externalUserId: String? = null,
)
```

### Step 2: Interface Adapter Updates (`AgentOsExecutionAdapter.kt`)
Update the historical `createOrRecoverExecution` overload (or provide default/updated parameters) and ensure binding delegation forwards `binding.parentCaseId`.

- Historical method signature update:
```kotlin
fun createOrRecoverExecution(
    namespaceId: String,
    workflowId: String,
    stepId: String,
    externalUserId: String?,
    attemptId: String,
    capabilityToken: String?,
    caseId: String,
    parentCaseId: String? = null,
): CaseHandle
```
- Delegate method in `AgentOsExecutionAdapter`:
```kotlin
override fun createOrRecoverExecution(binding: TrustedCaseBinding, workflowId: String, stepId: String): CaseHandle =
    createOrRecoverExecution(
        namespaceId = binding.namespaceId ?: "",
        workflowId = workflowId,
        stepId = stepId,
        externalUserId = binding.externalUserId,
        attemptId = binding.attemptId,
        capabilityToken = binding.capabilityToken,
        caseId = binding.caseId,
        parentCaseId = binding.parentCaseId,
    )
```

### Step 3: Default AgentOS Execution Adapter Updates (`DefaultAgentOsExecutionAdapter.kt`)

1. **Update `ExecutionRecord`**:
```kotlin
private data class ExecutionRecord(
    val caseId: String,
    val namespaceId: String?,
    val parentCaseId: String? = null,
    val externalUserId: String?,
    val capabilityToken: String?,
    val agentName: String? = null,
    val runtimeId: String? = null,
    val environmentRef: String? = null,
    val environmentRevision: Int? = null,
) {
    fun toBinding(attemptId: String): TrustedCaseBinding = TrustedCaseBinding(
        caseId = caseId,
        attemptId = attemptId,
        namespaceId = namespaceId,
        parentCaseId = parentCaseId,
        runtimeId = runtimeId,
        capabilityToken = capabilityToken,
        agentName = agentName,
        environmentRef = environmentRef,
        environmentRevision = environmentRevision,
        externalUserId = externalUserId,
    )
}
```

2. **Implement Cold Cache Reconciliation & Adoption in `createOrRecoverExecution(binding, workflowId, stepId)`**:
   - Check `executions[binding.attemptId]` first. If found, register in `registry` and return `CaseHandle(it.caseId, it.namespaceId, recovered = true)`.
   - If not found in `executions` cache for `binding.attemptId`, perform a cold cache check against remote AgentOS: `GET /api/cases/{binding.caseId}`.
     - Note: Use `runCatching` or standard RestClient response checking (`HttpStatus` or catching `HttpClientErrorException.NotFound` / 404).
     - If `GET /api/cases/{caseId}` returns 200 OK with body `remoteCase`:
       - Extract `remoteNamespaceId = remoteCase["namespaceId"] as? String`
       - Extract `remoteParentId = (remoteCase["parentCaseId"] as? String) ?: (remoteCase["parentId"] as? String)`
       - Check compatibility:
         ```kotlin
         val namespaceMatches = binding.namespaceId.isNullOrBlank() || remoteNamespaceId.isNullOrBlank() || binding.namespaceId == remoteNamespaceId
         val parentMatches = binding.parentCaseId == remoteParentId
         if (namespaceMatches && parentMatches) {
             val record = ExecutionRecord(
                 caseId = binding.caseId,
                 namespaceId = remoteNamespaceId ?: binding.namespaceId,
                 parentCaseId = remoteParentId ?: binding.parentCaseId,
                 externalUserId = binding.externalUserId,
                 capabilityToken = binding.capabilityToken,
                 agentName = binding.agentName,
                 runtimeId = binding.runtimeId,
                 environmentRef = binding.environmentRef,
                 environmentRevision = binding.environmentRevision,
             )
             executions[binding.attemptId] = record
             registry.register(record.toBinding(binding.attemptId))
             return CaseHandle(binding.caseId, record.namespaceId, recovered = true)
         } else {
             throw IllegalStateException("Case ${binding.caseId} exists in AgentOS but is incompatible with requested binding: namespaceId expected=${binding.namespaceId} actual=$remoteNamespaceId, parentCaseId expected=${binding.parentCaseId} actual=$remoteParentId")
         }
         ```
   - If remote case does NOT exist (404 or remote check yields null):
     - Construct POST `/api/cases` body:
       ```kotlin
       val body = LinkedHashMap<String, Any?>()
       body["namespaceId"] = binding.namespaceId
       body["title"] = "Factory session $workflowId · step $stepId"
       body["id"] = binding.caseId
       body["attemptId"] = binding.attemptId
       if (!binding.parentCaseId.isNullOrBlank()) body["parentCaseId"] = binding.parentCaseId
       if (!binding.capabilityToken.isNullOrBlank()) body["capabilityToken"] = binding.capabilityToken
       ```
     - Execute POST `/api/cases`.
     - In case POST `/api/cases` fails with 409 Conflict (or returns existing case), re-query `GET /api/cases/{binding.caseId}` and perform the same compatibility adoption check as above.
     - Save `ExecutionRecord` to `executions[binding.attemptId]` and register in `registry`.

3. **Update Historical Signature Implementation**:
```kotlin
override fun createOrRecoverExecution(
    namespaceId: String,
    workflowId: String,
    stepId: String,
    externalUserId: String?,
    attemptId: String,
    capabilityToken: String?,
    caseId: String,
    parentCaseId: String?,
): CaseHandle = createOrRecoverExecution(
    TrustedCaseBinding(
        caseId = caseId,
        attemptId = attemptId,
        namespaceId = namespaceId,
        parentCaseId = parentCaseId,
        capabilityToken = capabilityToken,
        externalUserId = externalUserId,
    ),
    workflowId = workflowId,
    stepId = stepId,
)
```

### Step 4: Capability Execution Service Updates (`CapabilityExecutionService.kt`)

In `CapabilityExecutionService.kt`:
1. `AgentReservation` data class (if used for binding assembly) or `executeRemoteTurn` method:
   - Ensure `reservation` or `persisted` passes `parentCaseId` to `TrustedCaseBinding`.
   - In `executeRemoteTurn(...)`:
     ```kotlin
     val binding = TrustedCaseBinding(
         caseId = reservation.caseId,
         namespaceId = namespaceId,
         parentCaseId = reservation.parentCaseId, // Populated from AgentReservation / DurableAgentAttempt
         attemptId = reservation.attemptId,
         capabilityToken = reservation.capabilityToken,
         agentName = agentId,
     )
     ```
   - Update `AgentReservation` data class to include `parentCaseId: String? = null`.
   - In `reserveAgentAttempt(...)`:
     - When building `AgentReservation`, set `parentCaseId = persisted.parentCaseId ?: family.parentCaseId`.

### Step 5: Recovery Worker Updates (`BridgeRecoveryWorker.kt`)

In `BridgeRecoveryWorker.kt`:
- In `redrive(candidate: ScopedDurableAgentAttempt)`:
  - Replace raw parameter call to `adapter.createOrRecoverExecution` with `TrustedCaseBinding`:
    ```kotlin
    val binding = TrustedCaseBinding(
        caseId = attempt.caseId,
        attemptId = attempt.attemptId,
        namespaceId = attempt.namespaceId,
        parentCaseId = attempt.parentCaseId,
        capabilityToken = attempt.capabilityToken,
        agentName = attempt.agentName,
        environmentRef = attempt.environmentRef,
        environmentRevision = attempt.expectedEnvironmentRevision,
    )
    adapter.createOrRecoverExecution(binding, attempt.workflowId, attempt.stepId)
    ```
  - Also ensure `startTurn` call uses binding or passes proper parameters with `attempt.parentCaseId`.

---

## 4. Verification & Testing Plan

### A. Unit Tests in `DefaultAgentOsExecutionAdapterTest.kt`
1. `parentCaseId sent in POST /api/cases`: Verify `jsonPath("$.parentCaseId").value("parent-1")` when `parentCaseId` is set on `TrustedCaseBinding`.
2. `Cold cache recovery - Compatible Adoption`:
   - Cold cache (`executions` map is empty).
   - Mock `GET /api/cases/case-1` returning 200 OK with `parentCaseId = "parent-1"` and `namespaceId = "ns-1"`.
   - Call `createOrRecoverExecution` with `binding(caseId = "case-1", parentCaseId = "parent-1", namespaceId = "ns-1")`.
   - Assert returns `CaseHandle("case-1", "ns-1", recovered = true)`. No POST `/api/cases` executed.
3. `Cold cache recovery - Incompatible Rejection`:
   - Cold cache.
   - Mock `GET /api/cases/case-1` returning 200 OK with `parentCaseId = "different-parent"`.
   - Call `createOrRecoverExecution` with `binding(caseId = "case-1", parentCaseId = "parent-1")`.
   - Assert throws `IllegalStateException`.
4. `Cold cache recovery - 404 proceeds to POST`:
   - Mock `GET /api/cases/case-1` returning 404 Not Found.
   - Mock POST `/api/cases` returning 200 OK.
   - Assert returns `CaseHandle("case-1", "ns-1", recovered = false)`.

### B. Integration & Worker Tests
1. `BridgeRecoveryWorkerTest.kt`: Verify that `redrive` constructs binding with `attempt.parentCaseId` and passes it to adapter.
2. `CapabilityExecutionIntegrationTest.kt` / `DurableCaseFamilyIntegrationTest.kt`: Verify end-to-end nominal dispatch and retry preserving `parentCaseId`.

---

## 5. Execution Command

Run test suite via Gradle inside `factory/`:
```bash
cd /work/app/factory && ./gradlew :factory-service:test
```
Or specifically:
```bash
cd /work/app/factory && ./gradlew :factory-service:test --tests "io.whozoss.factory.adapter.agentos.DefaultAgentOsExecutionAdapterTest"
cd /work/app/factory && ./gradlew :factory-service:test --tests "io.whozoss.factory.agentattempt.BridgeRecoveryWorkerTest"
```

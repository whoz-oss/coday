# Task T2a Plan: Non-destructive SPI Hooks Foundation in AgentOS SDK and Runtime

## Context & Objectives
Task T2a is the first step in decoupling Factory logic from AgentOS.
The goal is to define 4 generic SPI extension interfaces in `agentos-sdk` and integrate them into the AgentOS runtime (`CaseRuntime` and `CaseServiceImpl`) with default no-op behavior.
100% of existing functionality (including current Factory code like `FactoryCheckpointClient`) must be preserved.
No Factory code is removed or modified in this step (removal happens in T2c), and no PF4J plugin or `factory/` directory code is introduced (T2b).

---

## Strict Constraints & Rules
1. **DO NOT** remove or modify `FactoryCheckpointRef`, `factoryCheckpoint`, `FactoryCheckpointClient` or any existing Factory code in AgentOS.
2. **DO NOT** create any PF4J plugin or touch anything under `factory/` or `apps/` or external libs.
3. **DO NOT** modify existing tests unless required to pass constructor default parameters safely.
4. Interfaces must use generic domain names and contracts (NO mention of "Factory" in interface or parameter names/contracts).
5. All interfaces must provide safe default / no-op implementations (or default methods / default constructor parameters) so existing callers continue working without changes.

---

## Detailed Design & Changes

### 1. SPI Extension Interfaces in `agentos-sdk`

Package: `io.whozoss/agentos/sdk/spi/` (or package under `io.whozoss.agentos.sdk.spi`)
*Note: Interfaces should extend `org.pf4j.ExtensionPoint` (like `UserContextProvider` or `ToolPlugin`) so they can be discovered via PF4J in T2b, while remaining plain interfaces usable in Spring/standalone.*

Let's define the 4 SPI interfaces:

#### 1.1 `AnswerInterceptor`
Location: `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/AnswerInterceptor.kt`
- **Purpose**: Intercepts and validates answers to questions before event persistence and resuming agent turns.
- **Contract**:
  ```kotlin
  package io.whozoss.agentos.sdk.spi

  import io.whozoss.agentos.sdk.actor.Actor
  import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
  import org.pf4j.ExtensionPoint
  import java.util.UUID

  sealed interface AnswerInterceptResult {
      object Accept : AnswerInterceptResult
      data class Reject(val reason: String) : AnswerInterceptResult
  }

  interface AnswerInterceptor : ExtensionPoint {
      fun interceptAnswer(
          caseId: UUID,
          questionEvent: QuestionEvent,
          answerText: String,
          actor: Actor,
      ): AnswerInterceptResult = AnswerInterceptResult.Accept
  }
  ```

#### 1.2 `CaseLifecycleObserver`
Location: `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/CaseLifecycleObserver.kt`
- **Purpose**: Observes case status transitions and stored/emitted events in real time.
- **Contract**:
  ```kotlin
  package io.whozoss.agentos.sdk.spi

  import io.whozoss.agentos.sdk.caseEvent.CaseEvent
  import io.whozoss.agentos.sdk.caseFlow.CaseStatus
  import org.pf4j.ExtensionPoint
  import java.util.UUID

  interface CaseLifecycleObserver : ExtensionPoint {
      fun onStatusChanged(caseId: UUID, oldStatus: CaseStatus, newStatus: CaseStatus) {}
      fun onEventStored(caseId: UUID, event: CaseEvent) {}
  }
  ```

#### 1.3 `ExternalExecutionContextProvider`
Location: `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/ExternalExecutionContextProvider.kt`
- **Purpose**: Provides optional external execution context data to case runs / sessions (e.g. metadata, environment context, external session variables).
- **Contract**:
  ```kotlin
  package io.whozoss.agentos.sdk.spi

  import org.pf4j.ExtensionPoint
  import java.util.UUID

  interface ExternalExecutionContextProvider : ExtensionPoint {
      fun provideExecutionContext(
          caseId: UUID,
          namespaceId: UUID,
          userId: UUID? = null,
      ): Map<String, Any?> = emptyMap()
  }
  ```

#### 1.4 `ToolGrantPolicy`
Location: `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/ToolGrantPolicy.kt`
- **Purpose**: Evaluates allowed/denied tool grants for agents/cases or resolves extra tools dynamically.
- **Contract**:
  ```kotlin
  package io.whozoss.agentos.sdk.spi

  import io.whozoss.agentos.sdk.tool.ToolContext
  import org.pf4j.ExtensionPoint

  interface ToolGrantPolicy : ExtensionPoint {
      fun evaluateGrants(
          agentName: String,
          toolContext: ToolContext,
          requestedIntegrations: List<String> = emptyList(),
      ): List<String> = emptyList() // Or pass-through / allowed tool names
  }
  ```

---

### 2. Runtime & Service Integration in `agentos-service`

#### 2.1 Wiring in `CaseRuntime` (`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseRuntime.kt`)

Add optional parameters with defaults:
- `answerInterceptors: List<AnswerInterceptor> = emptyList()`
- `lifecycleObservers: List<CaseLifecycleObserver> = emptyList()`

Integration points in `CaseRuntime`:
1. **`addUserMessage` (Answer Processing)**:
   When `answerToEventId != null` and `questionEvent` is resolved:
   - Before (or alongside) existing Factory checkpoint validation, invoke `answerInterceptors`:
     ```kotlin
     for (interceptor in answerInterceptors) {
         val result = interceptor.interceptAnswer(id, questionEvent, answerText, actor)
         if (result is AnswerInterceptResult.Reject) {
             logger.warn { "[CaseRuntime $id] Answer rejected by interceptor: ${result.reason}" }
             storeAndEmitEvent(
                 WarnEvent(
                     namespaceId = namespaceId,
                     caseId = id,
                     message = "Answer rejected: ${result.reason}. Please try again.",
                 ),
             )
             return
         }
     }
     ```
   - Keep existing `factoryCheckpoint` logic untouched right after.

2. **`storeAndEmitEvent` (Event Observation)**:
   In `storeAndEmitEvent(event: CaseEvent)`:
   - Call `lifecycleObservers.forEach { observer -> runCatching { observer.onEventStored(id, saved) } }`.

#### 2.2 Wiring in `CaseServiceImpl` (`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`)

Inject optional lists of Spring beans or default empty lists into `CaseServiceImpl`:
- `@Autowired(required = false) private val answerInterceptors: List<AnswerInterceptor> = emptyList()`
- `@Autowired(required = false) private val lifecycleObservers: List<CaseLifecycleObserver> = emptyList()`
- `@Autowired(required = false) private val externalExecutionContextProviders: List<ExternalExecutionContextProvider> = emptyList()`
- `@Autowired(required = false) private val toolGrantPolicies: List<ToolGrantPolicy> = emptyList()`

Integration points in `CaseServiceImpl`:
1. **`buildRuntime`**:
   Pass `answerInterceptors` and `lifecycleObservers` to `CaseRuntime`.

2. **`handleStatusChange` (Status Observation)**:
   When `handleStatusChange` is called, notify `lifecycleObservers`:
   ```kotlin
   lifecycleObservers.forEach { observer ->
       runCatching { observer.onStatusChanged(caseId, oldStatus, newStatus) }
   }
   ```

3. **`ExternalExecutionContextProvider` Integration**:
   In `addMessage` or execution context preparation, combine external context from `externalExecutionContextProviders` with `sessionContext`:
   ```kotlin
   val externalContext = externalExecutionContextProviders.flatMap { provider ->
       runCatching { provider.provideExecutionContext(caseId, runtime.namespaceId, userId) }.getOrElse { emptyMap() }.entries
   }.associate { it.key to it.value }
   val mergedSessionContext = if (externalContext.isNotEmpty()) {
       (sessionContext ?: emptyMap()) + externalContext
   } else sessionContext
   ```

4. **`ToolGrantPolicy` Integration**:
   Wire into `AgentServiceImpl` or `CaseServiceImpl` where tools/grants are resolved, defaulting to pass-through/neutral behavior when empty.

---

## Verification Plan

### Automated Verification
Run Gradle tests in `agentos`:
```bash
cd agentos && ./gradlew :agentos-sdk:build :agentos-service:test
```

Verify that all existing tests in `CaseRuntimeSpec`, `CaseServiceImplSpec`, and `FactoryCheckpointCaseRuntimeSpec` pass without any regression.

---

## File Summary to Touch/Create
1. **New SDK Interfaces**:
   - `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/AnswerInterceptor.kt`
   - `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/CaseLifecycleObserver.kt`
   - `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/ExternalExecutionContextProvider.kt`
   - `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/ToolGrantPolicy.kt`
2. **Runtime Wiring**:
   - `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseRuntime.kt`
   - `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`
3. **Unit Test Coverage (New)**:
   - `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/spi/SpiHooksIntegrationSpec.kt`

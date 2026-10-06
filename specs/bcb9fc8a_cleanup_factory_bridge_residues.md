# Refactor Plan: Remove Remaining Factory Bridge Residues from SDK and Service Core

## Overview

The goal of this refactoring is to remove all remaining Factory Bridge residues from `agentos-sdk` and `agentos-service` core so that Factory concepts live exclusively within `agentos-factory-bridge-plugin`.

In a previous commit (`5a60a878`), the `factory/` package under `agentos-service` was removed. Now, the 3 remaining files that still reference Factory Bridge concepts in `agentos-sdk` and `agentos-service` need to be cleaned up.

## Exact Scope of Changes

### Modifying Existing Files (ONLY 3 FILES)

1. **`agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`**
   - Remove `FactoryCheckpointRef` data class definition.
   - Remove `factoryCheckpoint: FactoryCheckpointRef? = null` property from `QuestionEvent`.

2. **`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseRuntime.kt`**
   - Remove import `io.whozoss.agentos.factory.FactoryCheckpointClient`.
   - Remove parameter `private val factoryCheckpointClient: FactoryCheckpointClient? = null` from `CaseRuntime` constructor.
   - Remove the Factory validation block inside `addUserMessage` (where `factoryCheckpoint` was validated against `factoryCheckpointClient`).

3. **`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`**
   - Remove imports of `io.whozoss.agentos.factory.FactoryCheckpointClient` and `FactoryStepResultBindingRegistry`.
   - Remove property `factoryStepResultBindings` (a ConcurrentHashMap or injected dependency).
   - Remove property `@Value("\${agentos.factory.base-url:}") private val factoryBaseUrl: String = ""`.
   - Remove property `factoryHttpClient`.
   - Remove `FactoryCheckpointClient` instantiation / passing in `buildRuntime`.
   - Remove `factoryStepResultBindings.remove(caseId)` calls in `killSingleCase` and `handleStatusChange`.

### Mandatory Constraints & Negative Constraints

- **CRITICAL GATE INSTRUCTION**: Do NOT claim or attempt to delete files under `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/` or `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/factory/`. Those files were ALREADY removed in commit `5a60a878` and no longer exist on disk.
- **Do NOT touch**:
  - `agentos/agentos-factory-bridge-plugin/`
  - `factory/` at the repository root
  - SPI hooks (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`)

## Detailed Step-by-Step Instructions

### Step 1: Update `CaseEvent.kt`
File: `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`
- Locate `QuestionEvent` data class definition.
- Remove `val factoryCheckpoint: FactoryCheckpointRef? = null` parameter.
- Locate `FactoryCheckpointRef` data class definition (if present at the bottom or near `QuestionEvent`).
- Remove the data class `FactoryCheckpointRef`.

### Step 2: Update `CaseRuntime.kt`
File: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseRuntime.kt`
- Remove import `io.whozoss.agentos.factory.FactoryCheckpointClient`.
- Remove `private val factoryCheckpointClient: FactoryCheckpointClient? = null` from `CaseRuntime` class constructor signature.
- In `addUserMessage`, locate and remove the block checking `questionEvent.factoryCheckpoint` against `factoryCheckpointClient`.

### Step 3: Update `CaseServiceImpl.kt`
File: `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`
- Remove imports:
  - `io.whozoss.agentos.factory.FactoryCheckpointClient`
  - `io.whozoss.agentos.factory.FactoryStepResultBindingRegistry` (or similar factory imports)
- Remove `factoryStepResultBindings` field/property.
- Remove `@Value("\${agentos.factory.base-url:}") private val factoryBaseUrl: String = ""`.
- Remove `factoryHttpClient` property or initialization logic.
- In `buildRuntime(...)`: remove passing `factoryCheckpointClient` / `factoryBaseUrl` / `factoryHttpClient` when instantiating `CaseRuntime`.
- In `killSingleCase(...)` and `handleStatusChange(...)`: remove `factoryStepResultBindings.remove(caseId)` calls.

### Step 4: Verification & Testing
1. Execute Gradle build inside `agentos/`:
   ```bash
   cd agentos && ./gradlew build
   ```
   Ensure build and unit/integration tests pass cleanly.
2. Verify with search:
   Ensure `grep -rn "FactoryCheckpoint" agentos/agentos-sdk agentos/agentos-service` returns no results.
3. Verify git status and diffs:
   Ensure ONLY the 3 files listed above were modified.

### Step 5: Commit
Create git commit with conventional commit format:
`refactor(agentos): remove remaining Factory Bridge residues from SDK and service core`

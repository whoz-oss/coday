# Implementation Plan: Final cleanup of Factory bridge residues in AgentOS core and SDK

Goal: Remove all remaining Factory-specific references and files from `agentos-sdk` and `agentos-service` (core), leaving all Factory logic exclusively inside `agentos-factory-bridge-plugin/`.

## User Review Required

> [!IMPORTANT]
> This plan cleans up hardcoded Factory bridge references in `agentos-sdk` and `agentos-service`. Generic SPI interfaces (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`) already exist and will NOT be touched or modified. No plugin code in `agentos-factory-bridge-plugin/` or workspace `factory/` will be altered.

## Proposed Changes

### 1. `agentos-sdk`

#### [`agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`]
- Remove `FactoryCheckpointRef` data class definition (lines 281-295).
- Remove `factoryCheckpoint` field on `QuestionEvent` (line 321) and clean up its KDoc reference.

### 2. `agentos-service` (Main Source)

#### [Deletions - Entire `io.whozoss.agentos.factory` package in `agentos-service`]
Delete all 16 files in `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/`:
- `FactoryCheckpointClient.kt`
- `FactoryEnvironmentBindingService.kt`
- `FactoryEnvironmentTool.kt`
- `FactoryGetWorkflowTool.kt`
- `FactoryProjectionValidation.kt`
- `FactoryPublishProjectionTool.kt`
- `FactoryRecordEvidenceTool.kt`
- `FactoryRequestHumanDecisionTool.kt`
- `FactoryRequestTransitionTool.kt`
- `FactoryStartWorkflowTool.kt`
- `FactoryStepResultBindingController.kt`
- `FactoryStepResultBindingRegistry.kt`
- `FactorySubmitStepResultTool.kt`
- `FactoryToolGrantService.kt`
- `FactoryToolPlugin.kt`
- `FactoryTransitionWorkflowTool.kt`

This empties and deletes the directory `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/`.

#### [`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseRuntime.kt`]
- Remove import `io.whozoss.agentos.factory.FactoryCheckpointClient`.
- Remove constructor parameter `factoryCheckpointClient: FactoryCheckpointClient? = null` and its KDoc references.
- Remove the Factory validation block inside `addUserMessage` (where `factoryCheckpointClient?.submitDecision` was called when `questionEvent.factoryCheckpoint` was non-null).

#### [`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImpl.kt`]
- Remove imports `io.whozoss.agentos.factory.FactoryCheckpointClient` and `io.whozoss.agentos.factory.FactoryStepResultBindingRegistry`.
- Remove property `factoryStepResultBindings` and Spring `@Value` `factoryBaseUrl` / `factoryHttpClient`.
- Remove `FactoryCheckpointClient` instantiation inside `buildRuntime`.
- Remove `factoryStepResultBindings.remove(caseId)` calls in `killSingleCase` and `handleStatusChange`.

#### [`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/agent/AgentInterrupt.kt`]
- Remove import `io.whozoss.agentos.sdk.caseEvent.FactoryCheckpointRef`.
- Remove `factoryCheckpoint` field on `AgentInterrupt.AwaitAnswer` and clean up its KDoc.

#### [`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/agent/AgentInterruptHandler.kt`]
- Remove `factoryCheckpoint = e.factoryCheckpoint` assignment when instantiating `QuestionEvent` in `emitInterruptAndFinishEvents`.

#### [`agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/agent/AgentServiceImpl.kt`]
- Remove imports of `FactoryToolGrantService` and `FactoryEnvironmentBindingService`.
- Remove constructor/property parameters `factoryToolGrantService` and `factoryEnvironmentBindingService`.
- Remove usage of `factoryToolGrantService` and `factoryEnvironmentBindingService` inside tool resolution methods.

### 3. `agentos-service` (Test Source)

#### [Deletions - Entire `io.whozoss.agentos.factory` package in `agentos-service` tests]
Delete all 9 test files in `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/factory/`:
- `FactoryCheckpointCaseRuntimeSpec.kt`
- `FactoryCheckpointClientSpec.kt`
- `FactoryGetWorkflowToolSpec.kt`
- `FactoryPublishProjectionToolSpec.kt`
- `FactoryRecordEvidenceToolSpec.kt`
- `FactoryRequestHumanDecisionToolSpec.kt`
- `FactoryRequestTransitionToolSpec.kt`
- `FactoryStartWorkflowToolSpec.kt`
- `FactoryStepResultBindingRegistrySpec.kt`
- `FactorySubmitStepResultToolSpec.kt`

This empties and deletes the directory `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/factory/`.

#### [Code Adjustments in remaining tests]
- Update `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/agent/AgentServiceImplUnitSpec.kt`:
  - Remove imports and references to `FactoryToolGrantService` and `FactoryEnvironmentBindingService`.
- Update `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/caseFlow/CaseServiceImplSpec.kt`:
  - Remove `factoryBaseUrl = ""` argument in `CaseServiceImpl` construction.
- Update `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/spi/SpiHooksIntegrationSpec.kt`:
  - Clean up any KDoc references mentioning "Factory checkpoint gate".

## Verification Plan

### Automated Tests
1. Run `./gradlew build` inside `agentos/` directory:
   ```bash
   cd agentos && ./gradlew build -Dorg.gradle.jvmargs="-Xmx1g"
   ```
2. Verify that compile and tests succeed across `agentos-sdk`, `agentos-service`, and `agentos-factory-bridge-plugin`.

### Search Audit
1. Run grep search for `Factory` in `agentos-sdk`:
   ```bash
   git grep -i "Factory" agentos/agentos-sdk
   ```
   Verify NO Factory Bridge references remain (only Jackson/general terms if any).

2. Run grep search for `Factory` in `agentos-service/src`:
   ```bash
   git grep -i "Factory" agentos/agentos-service/src
   ```
   Verify only generic factory design patterns remain (e.g. `StaticCredentialFactory`, `AuthServiceFactory`, `LoggerFactory`, Jackson `YAMLFactory`).

3. Verify no uncommitted modifications remain outside the plan scope (`git status`).

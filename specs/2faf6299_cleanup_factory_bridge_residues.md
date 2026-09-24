# Final Factory Bridge Cleanup in AgentOS Core and SDK

## Task Overview
Remove all remaining legacy Factory Bridge code and references from `agentos-sdk` and `agentos-service` (core). All Factory Bridge capabilities now reside strictly in `agentos-factory-bridge-plugin/`.

## Key Objective & Target Areas

1. **`agentos-sdk`**:
   - In `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/caseEvent/CaseEvent.kt`:
     - Delete `data class FactoryCheckpointRef`.
     - Remove `val factoryCheckpoint: FactoryCheckpointRef? = null` property from `QuestionEvent` data class.
     - Clean up doc comments referencing `FactoryCheckpointRef` or `factoryCheckpoint`.

2. **`agentos-service` Main Source Deletions**:
   - Delete all files inside `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/`:
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
   - Remove directory `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/factory/`.

3. **`agentos-service` Test Source Deletions**:
   - Delete all files inside `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/factory/`:
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
   - Remove directory `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/factory/`.

4. **`agentos-service` Core Adjustments**:
   - `CaseRuntime.kt`:
     - Remove import `io.whozoss.agentos.factory.FactoryCheckpointClient`.
     - Remove constructor parameter `factoryCheckpointClient: FactoryCheckpointClient? = null`.
     - Remove KDoc references to `factoryCheckpointClient`.
     - In `addUserMessage` (handling of `QuestionEvent`), remove the Factory checkpoint validation block (`val checkpoint = questionEvent.factoryCheckpoint...`) and answer submission call.
   - `CaseServiceImpl.kt`:
     - Remove imports `io.whozoss.agentos.factory.FactoryCheckpointClient` and `io.whozoss.agentos.factory.FactoryStepResultBindingRegistry`.
     - Remove properties `factoryStepResultBindings`, `factoryBaseUrl`, and `factoryHttpClient`.
     - In `buildRuntime()`, remove `factoryCheckpointClient = checkpointClient` argument.
     - In `killSingleCase()` and `handleStatusChange()`, remove `factoryStepResultBindings.remove(caseId)`.
   - `AgentServiceImpl.kt`:
     - Remove imports `io.whozoss.agentos.factory.FactoryToolGrantService` and `io.whozoss.agentos.factory.FactoryEnvironmentBindingService`.
     - Remove constructor parameters `factoryToolGrantService` and `factoryEnvironmentBindingService`.
     - In `resolveToolsForRun()`, remove `factoryTools` evaluation (`factoryToolGrantService.grantTools(...)`) and `buildWorkUnitEnvironmentTools(...)`.
     - Delete `buildWorkUnitEnvironmentTools()` helper method and constant `WORK_UNIT_FILE_ACCESS`.
   - `AgentInterrupt.kt`:
     - Remove import `io.whozoss.agentos.sdk.caseEvent.FactoryCheckpointRef`.
     - Remove `val factoryCheckpoint: FactoryCheckpointRef? = null` parameter from `AgentInterrupt.AwaitAnswer`.
     - Remove KDoc references to `FactoryRequestHumanDecisionTool` / Factory checkpoint.
   - `AgentInterruptHandler.kt`:
     - Remove `factoryCheckpoint = e.factoryCheckpoint` argument in `QuestionEvent` instantiation inside `emitInterruptAndFinishEvents`.
   - `application.yml` (`agentos/agentos-service/src/main/resources/application.yml`):
     - Remove the `agentos.factory:` block (`base-url:...`).
   - `AgentServiceImplUnitSpec.kt`:
     - Update test instantiations of `AgentServiceImpl` to remove `factoryToolGrantService` and `factoryEnvironmentBindingService` mock arguments/fields.
   - `CaseServiceImplSpec.kt`:
     - Update test instantiations of `CaseServiceImpl` where `factoryBaseUrl` was supplied.

5. **Do NOT Touch**:
   - Do NOT touch `agentos/agentos-factory-bridge-plugin/`.
   - Do NOT touch `factory/` directory at workspace root.
   - Generic SPI hooks in `agentos-sdk` (`AnswerInterceptor`, `CaseLifecycleObserver`, `ExternalExecutionContextProvider`, `ToolGrantPolicy`) remain in place.

## Verification Steps
1. `./gradlew build` in `agentos/` directory:
   Ensure clean compilation and passing tests across `agentos-sdk`, `agentos-service`, and `agentos-factory-bridge-plugin`.
2. Code search for `Factory` in `agentos-sdk` and `agentos-service/src`:
   Verify no Factory Bridge references remain (only generic non-Bridge occurrences like `ChatModelFactory`, `StaticCredentialFactory`, `AuthServiceFactory`, `YAMLFactory`, etc.).
3. Conventional commit message:
   `refactor(agentos): remove remaining legacy factory bridge code from core service and sdk`

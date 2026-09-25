/**
 * Persistence adapters barrel.
 *
 * Each adapter implements a `ports/persistence` repository interface over the
 * durable-storage kernel and an injected concrete store. The legacy `.mjs`
 * facades re-export these adapters and their `create*` wiring helpers, keeping
 * the `.mjs` stores as the runtime authority during the coexistence.
 */

export * from './storage-kernel.js'

export {
  FilesystemWorkflowDefinitionRepository,
  WorkflowDefinitionRepositoryError,
  createFilesystemWorkflowDefinitionRepository,
  type WorkflowDefinitionRegistryLike,
} from './filesystem-workflow-definition-repository.js'

export {
  FilesystemWorkflowInstanceRepository,
  WorkflowInstanceRepositoryError,
  createFilesystemWorkflowInstanceRepository,
  type WorkflowProjectionStoreLike,
  type WorkflowStoreResult,
  type WorkflowInstanceTransitionInput,
} from './filesystem-workflow-instance-repository.js'

export {
  FilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowEvidenceRepository,
  type WorkflowEvidenceStoreLike,
} from './filesystem-workflow-evidence-repository.js'

export {
  FilesystemWorkflowHumanInteractionRepository,
  WorkflowHumanInteractionRepositoryError,
  createFilesystemWorkflowHumanInteractionRepository,
  type WorkflowHumanInteractionStoreLike,
} from './filesystem-workflow-human-interaction-repository.js'

export { AgentStepAttemptStore } from './agent-step-attempt-store.js'

// The `AgentStepAttemptStoreLike` / `AgentStepResultStoreLike` structural
// dependency interfaces are exported by their adapter modules. They are not
// re-exported here: the application layer already exposes identically named
// injection ports (`application/agent-attempt/factory-agent-step-executor.ts`),
// and the operational entrypoint re-exports both barrels, so a second export
// would be an ambiguous `export *`.

export {
  FilesystemAgentStepAttemptRepository,
  createFilesystemAgentStepAttemptRepository,
} from './filesystem-agent-step-attempt-repository.js'

export { AgentStepResultStore, type AgentStepResultStoreOptions } from './agent-step-result-store.js'

export {
  FilesystemAgentStepResultRepository,
  createFilesystemAgentStepResultRepository,
} from './filesystem-agent-step-result-repository.js'

export {
  FilesystemOracleExecutionRepository,
  createFilesystemOracleExecutionRepository,
} from './filesystem-oracle-execution-repository.js'

export {
  FilesystemWorkEnvironmentRepository,
  createFilesystemWorkEnvironmentRepository,
  type WorkUnitEnvironmentStoreLike,
} from './filesystem-work-environment-repository.js'

export {
  FilesystemDeliveryRepository,
  createFilesystemDeliveryRepository,
  type DeliveryStoreLike,
} from './filesystem-delivery-repository.js'

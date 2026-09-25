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

/**
 * Persistence ports barrel.
 *
 * Pure interfaces: importing this module pulls in no runtime code, only the
 * domain vocabulary each bounded context persists.
 */

export type {
  WorkflowDefinitionRepository,
  WorkflowDefinitionWithHash,
  WorkflowDefinitionRepositoryErrorCode,
} from './workflow-definition-repository.js'
export { WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES } from './workflow-definition-repository.js'

export type {
  WorkflowInstanceRepository,
  WorkflowInstanceSnapshot,
  WorkflowInstanceRemoveActor,
} from './workflow-instance-repository.js'

export type {
  WorkflowEvidenceRepository,
  WorkflowEvidenceListFilter,
  WorkflowEvidenceRecordResult,
} from './workflow-evidence-repository.js'

export type {
  WorkflowHumanInteractionRepository,
  WorkflowHumanInteractionListOptions,
  WorkflowHumanInteractionReconcileOptions,
  WorkflowHumanInteractionOpenOptions,
  WorkflowHumanInteractionTransitionOptions,
  WorkflowHumanInteractionRecordResult,
  HumanInteractionOpenTransition,
  HumanInteractionReplyAction,
} from './workflow-human-interaction-repository.js'

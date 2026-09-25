import type { WorkflowDefinition } from '../../domain/workflow/workflow-definition.js'

/**
 * Persistence port for the workflow-definition domain context.
 *
 * Clean-architecture boundary: the application depends on this interface and on
 * pure domain types only. A filesystem adapter (`adapters/persistence`) is
 * injected at the composition edge; nothing here knows about `.mjs` legacy
 * registries, files, or error transport.
 */

/** A validated definition plus its canonical content hash. */
export type WorkflowDefinitionWithHash = WorkflowDefinition & { definitionHash: string }

/** Stable error codes surfaced by definition repositories. */
export const WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES = Object.freeze({
  WORKFLOW_DEFINITION_NOT_FOUND: 'WORKFLOW_DEFINITION_NOT_FOUND',
  INVALID_DEFINITION_FILE: 'INVALID_DEFINITION_FILE',
  DEFINITION_PATH_MISMATCH: 'DEFINITION_PATH_MISMATCH',
  DEFINITION_COLLISION: 'DEFINITION_COLLISION',
} as const)

export type WorkflowDefinitionRepositoryErrorCode =
  (typeof WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES)[keyof typeof WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES]

export interface WorkflowDefinitionRepository {
  /** Every known definition, sorted by `workflowType` then numeric `version`. */
  list(): Promise<WorkflowDefinitionWithHash[]>
  /** The definition at an exact `workflowType@version`, or `null`. */
  get(workflowType: string, version: string): Promise<WorkflowDefinitionWithHash | null>
  /** The highest version of `workflowType`; rejects when none exists. */
  resolveUnique(workflowType: string): Promise<WorkflowDefinitionWithHash>
}

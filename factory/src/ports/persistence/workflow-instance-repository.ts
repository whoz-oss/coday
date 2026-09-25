import type {
  ControllerExecutionInput,
  WorkflowDefinitionInput,
  WorkflowInstance,
  WorkflowProjection,
  WorkflowStartCommand,
} from '../../domain/workflow/workflow-instance.js'

/**
 * Persistence port for the workflow-instance (projection) domain context.
 *
 * The authoritative artifact is a revisioned snapshot pairing the domain
 * `WorkflowInstance` with its read `WorkflowProjection`; the port exposes that
 * pair and lifecycle operations, never the journal or the tombstone files.
 */

export interface WorkflowInstanceSnapshot {
  instance: WorkflowInstance
  projection: WorkflowProjection
}

export interface WorkflowInstanceRemoveActor {
  actorId?: string
  removedBy?: string
  restoredBy?: string
  purgedBy?: string
}

export interface WorkflowInstanceRepository {
  /** Live projections of a namespace, sorted by `workflowId`. */
  list(namespaceId: string): Promise<WorkflowProjection[]>
  /** The snapshot of one workflow, or `null` when absent. */
  get(namespaceId: string, workflowId: string): Promise<WorkflowInstanceSnapshot | null>
  /** Creates a governed workflow, or returns the existing snapshot when idempotent. */
  create(
    namespaceId: string,
    command: WorkflowStartCommand,
    definition: WorkflowDefinitionInput,
    controllerExecution: ControllerExecutionInput
  ): Promise<WorkflowInstanceSnapshot>
  /**
   * Applies one transition. The transition payload carries the domain request,
   * definition and evidence; adapters stay agnostic of the policy.
   */
  transition(namespaceId: string, workflowId: string, transition: unknown): Promise<WorkflowInstanceSnapshot>
  /** Removes the workflow (recoverable trash + tombstone). */
  remove(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void>
  /** Restores a removed workflow. */
  restore(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void>
  /** Purges the trash of a removed workflow. */
  purge(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void>
}

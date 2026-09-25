import type {
  HumanInteractionInput,
  HumanInteractionRecord,
  HumanInteractionReply,
  WorkflowHumanInteractionEvent,
} from '../../domain/interaction/workflow-human-interaction.js'
import type {
  WorkflowHumanInteractionListOptions,
  WorkflowHumanInteractionOpenOptions,
  WorkflowHumanInteractionRecordResult,
  WorkflowHumanInteractionReconcileOptions,
  WorkflowHumanInteractionRepository,
  WorkflowHumanInteractionTransitionOptions,
} from '../../ports/persistence/workflow-human-interaction-repository.js'

/**
 * Filesystem human-interaction repository adapter.
 *
 * The store owns the append-only `human-interactions.jsonl` event log and its
 * projection (opening → open/aborted → replied). Opening and replying are
 * transactional and require the authoritative workflow transition, which the
 * caller supplies through `options.transition` / `options.action` so this
 * adapter never depends on the workflow-instance repository.
 */

export interface WorkflowHumanInteractionStoreLike {
  list(namespaceId: string, storageId: string, options?: { openOnly?: boolean }): Promise<HumanInteractionRecord[]>
  events(namespaceId: string, storageId: string): Promise<WorkflowHumanInteractionEvent[]>
  reconcileOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    snapshot: unknown,
    options?: { workflowFacts?: unknown[] }
  ): Promise<unknown>
  open(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    transition: (interaction: HumanInteractionRecord) => Promise<unknown>
  ): Promise<{
    interaction: HumanInteractionRecord
    transition?: { ok?: boolean; idempotent?: boolean; changed?: boolean }
  }>
  transact(
    namespaceId: string,
    storageId: string,
    interactionId: string,
    action: (interaction: HumanInteractionRecord) => Promise<unknown>
  ): Promise<{ interaction: HumanInteractionRecord }>
}

export class WorkflowHumanInteractionRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'WorkflowHumanInteractionRepositoryError'
    this.code = code
    this.details = details
  }
}

export class FilesystemWorkflowHumanInteractionRepository implements WorkflowHumanInteractionRepository {
  constructor(private readonly store: WorkflowHumanInteractionStoreLike) {}

  list(
    namespaceId: string,
    storageId: string,
    options?: WorkflowHumanInteractionListOptions
  ): Promise<HumanInteractionRecord[]> {
    return this.store.list(namespaceId, storageId, options)
  }

  events(namespaceId: string, storageId: string): Promise<WorkflowHumanInteractionEvent[]> {
    return this.store.events(namespaceId, storageId)
  }

  async reconcileOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    snapshot: unknown,
    options?: WorkflowHumanInteractionReconcileOptions
  ): Promise<HumanInteractionRecord> {
    const result = (await this.store.reconcileOpen(namespaceId, storageId, input, snapshot, options)) as {
      interaction?: HumanInteractionRecord
    }
    if (!result?.interaction)
      throw new WorkflowHumanInteractionRepositoryError('INTERACTION_RECOVERY_NOT_FOUND', { namespaceId, storageId })
    return result.interaction
  }

  async recordOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    options?: WorkflowHumanInteractionOpenOptions
  ): Promise<WorkflowHumanInteractionRecordResult> {
    const transition = options?.transition
    if (!transition)
      throw new WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_TRANSITION_REQUIRED', {
        namespaceId,
        storageId,
      })
    const result = await this.store.open(namespaceId, storageId, input, transition)
    const idempotent = Boolean(result.transition?.idempotent)
    return { created: !idempotent, idempotent, interaction: result.interaction }
  }

  async recordTransition(
    namespaceId: string,
    storageId: string,
    interactionId: string,
    reply: HumanInteractionReply,
    actorId: string,
    evidenceId: string,
    transitionRequestId: string,
    options?: WorkflowHumanInteractionTransitionOptions
  ): Promise<HumanInteractionRecord> {
    const action = options?.action
    if (!action)
      throw new WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_ACTION_REQUIRED', {
        namespaceId,
        storageId,
        interactionId,
      })
    const result = await this.store.transact(namespaceId, storageId, interactionId, action)
    void reply
    void actorId
    void evidenceId
    void transitionRequestId
    return result.interaction
  }
}

/** Wires a filesystem human-interaction repository around a concrete store. */
export function createFilesystemWorkflowHumanInteractionRepository(
  store: WorkflowHumanInteractionStoreLike
): FilesystemWorkflowHumanInteractionRepository {
  return new FilesystemWorkflowHumanInteractionRepository(store)
}

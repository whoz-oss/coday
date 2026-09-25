import type {
  HumanInteractionInput,
  HumanInteractionRecord,
  HumanInteractionReply,
  WorkflowHumanInteractionEvent,
} from '../../domain/interaction/workflow-human-interaction.js'

/**
 * Persistence port for the workflow human-interaction domain context.
 *
 * The store is an append-only event log projected into interaction records.
 * Opening a checkpoint and replying to it are transactional: they require a
 * workflow-transition gateway supplied by the caller, so the port carries it in
 * the `options` bag rather than hard-coding the workflow (instance) repository
 * dependency into the human-interaction boundary.
 */

export interface WorkflowHumanInteractionListOptions {
  openOnly?: boolean
}

export interface WorkflowHumanInteractionReconcileOptions {
  workflowFacts?: unknown[]
}

/**
 * Callback that performs the authoritative workflow transition backing an
 * interaction opening. Injected so this port stays independent from the
 * workflow-instance repository.
 */
export type HumanInteractionOpenTransition = (interaction: HumanInteractionRecord) => Promise<unknown>

/**
 * Callback that performs the authoritative workflow transition backing a reply.
 * Receives the projected interaction and returns `{ reply, actorId, evidenceId, transition }`.
 */
export type HumanInteractionReplyAction = (interaction: HumanInteractionRecord) => Promise<unknown>

export interface WorkflowHumanInteractionOpenOptions extends WorkflowHumanInteractionReconcileOptions {
  transition?: HumanInteractionOpenTransition
}

export interface WorkflowHumanInteractionTransitionOptions extends WorkflowHumanInteractionReconcileOptions {
  action?: HumanInteractionReplyAction
}

export interface WorkflowHumanInteractionRecordResult {
  created: boolean
  idempotent: boolean
  interaction: HumanInteractionRecord
}

export interface WorkflowHumanInteractionRepository {
  /** Projected interactions of a workflow scope, chronologically sorted. */
  list(
    namespaceId: string,
    storageId: string,
    options?: WorkflowHumanInteractionListOptions
  ): Promise<HumanInteractionRecord[]>
  /** Raw append-only events of a workflow scope. */
  events(namespaceId: string, storageId: string): Promise<WorkflowHumanInteractionEvent[]>
  /** Reconciles an interrupted opening against authoritative workflow facts. */
  reconcileOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    snapshot: unknown,
    options?: WorkflowHumanInteractionReconcileOptions
  ): Promise<HumanInteractionRecord>
  /** Opens an interaction, delegating the authoritative transition to `options.transition`. */
  recordOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    options?: WorkflowHumanInteractionOpenOptions
  ): Promise<WorkflowHumanInteractionRecordResult>
  /** Records a reply, delegating the authoritative transition to `options.action`. */
  recordTransition(
    namespaceId: string,
    storageId: string,
    interactionId: string,
    reply: HumanInteractionReply,
    actorId: string,
    evidenceId: string,
    transitionRequestId: string,
    options?: WorkflowHumanInteractionTransitionOptions
  ): Promise<HumanInteractionRecord>
}

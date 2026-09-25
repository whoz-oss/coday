import { createHash } from 'node:crypto'
import { WORKFLOW_STATUSES, type WorkflowStatus } from '../workflow/workflow-transition-policy.js'

/**
 * Pure workflow human-interaction domain: interaction kinds/statuses, canonical
 * input normalization, open-input validation and semantic hashing.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/workflow-human-interaction-store.mjs`
 * keeps the durable append-only store (file I/O) and delegates pure helpers here.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. Only `node:crypto` is allowed.
 */

export const HUMAN_INTERACTION_KINDS = Object.freeze(['approval', 'choice', 'text'] as const)

export type HumanInteractionKind = (typeof HUMAN_INTERACTION_KINDS)[number]

export const WORKFLOW_HUMAN_INTERACTION_STATUSES = Object.freeze(['opening', 'open', 'replied', 'aborted'] as const)

export type WorkflowHumanInteractionStatus = (typeof WORKFLOW_HUMAN_INTERACTION_STATUSES)[number]

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const KINDS = new Set<string>(HUMAN_INTERACTION_KINDS)

export interface HumanInteractionAction {
  id: string
  label: string
  requestedStatus: WorkflowStatus
}

export interface HumanInteractionInput {
  workflowId: string
  stepId: string
  expectedRevision: number
  kind: HumanInteractionKind
  prompt: string
  actions: HumanInteractionAction[]
  idempotencyKey: string
  interactionId?: string
  interactionType?: string
  reasonCode?: string
}

export interface NormalizedHumanInteractionInput {
  workflowId: string
  stepId: string
  expectedRevision: number
  kind: HumanInteractionKind
  prompt: string
  actions: HumanInteractionAction[]
  idempotencyKey: string
  interactionId?: string
  interactionType?: string
  reasonCode?: string
}

export type HumanInteractionSemanticInput = Pick<
  HumanInteractionInput,
  'workflowId' | 'stepId' | 'expectedRevision' | 'kind' | 'prompt' | 'actions'
> & { interactionType?: string; reasonCode?: string }

export interface HumanInteractionReply {
  actionId?: string
  text?: string
  [key: string]: unknown
}

export interface HumanInteractionRecord {
  interactionId: string
  workflowId: string
  stepId: string
  expectedRevision: number
  kind: HumanInteractionKind
  prompt: string
  actions: HumanInteractionAction[]
  idempotencyKey: string
  semanticHash: string
  openedAt: string
  status?: WorkflowHumanInteractionStatus
  revision?: number
  interactionType?: string
  reasonCode?: string
  reply?: HumanInteractionReply
  actorId?: string
  repliedAt?: string
  evidenceId?: string
  transitionRequestId?: string
  errorCode?: string
  [key: string]: unknown
}

export type WorkflowHumanInteractionEventName =
  | 'interaction_opening'
  | 'interaction_opened'
  | 'interaction_open_aborted'
  | 'interaction_transitioned'

export interface WorkflowHumanInteractionEvent {
  event: WorkflowHumanInteractionEventName
  interaction?: HumanInteractionRecord
  interactionId?: string
  revision?: number
  errorCode?: string
  reply?: HumanInteractionReply
  actorId?: string
  repliedAt?: string
  evidenceId?: string
  transitionRequestId?: string
  recoveredAt?: string
  recovery?: string
  [key: string]: unknown
}

export function canonicalHumanInteractionInput(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((entry) => canonicalHumanInteractionInput(entry))
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, canonicalHumanInteractionInput(record[key])])
    )
  }
  return value
}

export function humanInteractionSemanticHash(input: HumanInteractionSemanticInput): string {
  return createHash('sha256')
    .update(
      JSON.stringify(
        canonicalHumanInteractionInput({
          workflowId: input.workflowId,
          stepId: input.stepId,
          expectedRevision: input.expectedRevision,
          kind: input.kind,
          prompt: input.prompt,
          actions: input.actions,
          interactionType: input.interactionType,
          reasonCode: input.reasonCode,
        })
      )
    )
    .digest('hex')
}

export function validateHumanInteractionOpenInput(input: unknown): NormalizedHumanInteractionInput | null {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null
  const record = input as Record<string, unknown>
  const rawActions = record.actions
  const actionsValid =
    Array.isArray(rawActions) &&
    rawActions.length === 2 &&
    new Set(rawActions.map((action) => (action as { id?: unknown } | null | undefined)?.id)).size === 2 &&
    rawActions.every((action) => {
      if (!action || typeof action !== 'object' || Array.isArray(action)) return false
      const candidate = action as Record<string, unknown>
      return (
        SAFE_ID.test(typeof candidate.id === 'string' ? candidate.id : '') &&
        typeof candidate.label === 'string' &&
        !!candidate.label &&
        candidate.label.length <= 128 &&
        (WORKFLOW_STATUSES as readonly string[]).includes(candidate.requestedStatus as string)
      )
    })
  if (
    !SAFE_ID.test(String(record.workflowId ?? '')) ||
    !SAFE_ID.test(String(record.stepId ?? '')) ||
    !KINDS.has(record.kind as string) ||
    !Number.isSafeInteger(record.expectedRevision) ||
    (record.expectedRevision as number) < 1 ||
    typeof record.prompt !== 'string' ||
    !record.prompt ||
    record.prompt.length > 2000 ||
    !actionsValid ||
    typeof record.idempotencyKey !== 'string' ||
    !record.idempotencyKey ||
    record.idempotencyKey.length > 128 ||
    /[\r\n]/.test(record.idempotencyKey) ||
    (record.interactionId !== undefined && !SAFE_ID.test(String(record.interactionId)))
  )
    return null
  return {
    workflowId: record.workflowId as string,
    stepId: record.stepId as string,
    expectedRevision: record.expectedRevision as number,
    kind: record.kind as HumanInteractionKind,
    prompt: record.prompt,
    actions: (rawActions as Array<Record<string, unknown>>).map((action) => ({
      id: action.id as string,
      label: action.label as string,
      requestedStatus: action.requestedStatus as WorkflowStatus,
    })),
    idempotencyKey: record.idempotencyKey,
    ...(record.interactionId ? { interactionId: record.interactionId as string } : {}),
    ...(record.interactionType ? { interactionType: record.interactionType as string } : {}),
    ...(record.reasonCode ? { reasonCode: record.reasonCode as string } : {}),
  }
}

export function openedInteractionRevision(
  event: Pick<WorkflowHumanInteractionEvent, 'interaction' | 'revision'>
): number | undefined {
  return event.interaction?.revision ?? event.revision
}

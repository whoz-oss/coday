/**
 * Pure delivery-promotion policy: request validation, idempotency hashing and
 * the ordered, evidence-gated promotion rules between delivery stages.
 *
 * The policy binds a promotion to its controlling execution (namespace,
 * workflow, parent case, dashboard runtime), to the delivery definition hash
 * and to the evidence recorded against the exact environment and head commit.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/delivery-policy.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: only `node:crypto` is used (hashing and request ids); no
 * `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

import { createHash, randomUUID } from 'node:crypto'
import { DELIVERY_STAGES } from './delivery-definition.js'
import type { DeliveryDefinition } from './delivery-definition.js'

/** The stage every delivery starts in. */
export const DELIVERY_INITIAL_STAGE = 'implementation-ready'

/** A validated promotion request, ready for policy evaluation. */
export interface DeliveryPromotionRequest {
  requestId: string
  deliveryId: string
  expectedRevision: number
  requestedStage: string
  evidenceIds: string[]
  idempotencyKey: string
}

/** The controlling execution a promotion is bound to. */
export interface DeliveryExecutionContext {
  kind: string
  namespaceId: string
  workflowId: string
  caseId: string
  runtimeId: string
  actorId?: string
}

/** The snapshot surface the promotion policy reads. */
export interface DeliveryPromotionSnapshot {
  revision: number
  namespaceId: string
  workflowId: string
  parentCaseId: string
  definitionHash: string
  stage: string
  deliveryId: string
  environmentHash: string
  headCommit: string
  evidenceIds?: string[]
}

/** One recorded evidence fact the promotion policy binds on. */
export interface DeliveryEvidenceItem {
  evidenceId: string
  namespaceId: string
  workflowId: string
  deliveryId: string
  environmentHash: string
  caseId: string
  headCommit: string
  kind: string
  outcome: string
  oracleId?: string
  source?: { kind?: string }
}

/** A delivery definition together with its content hash. */
export type HashedDeliveryDefinition = DeliveryDefinition & { definitionHash: string }

/** Result of validating a raw promotion request. */
export type DeliveryPromotionRequestValidation =
  | { ok: true; value: DeliveryPromotionRequest }
  | { ok: false; error: { code: 'INVALID_DELIVERY_REQUEST' } }

/** Inputs of a promotion policy evaluation. */
export interface DeliveryPromotionEvaluation {
  request: DeliveryPromotionRequest
  snapshot: DeliveryPromotionSnapshot | null
  definition: HashedDeliveryDefinition
  evidence: DeliveryEvidenceItem[]
  execution: DeliveryExecutionContext
}

/** A promotion decision: allowed, or denied with a machine code and reason. */
export type DeliveryPromotionDecision = { allowed: true } | { allowed: false; code: string; reason: string }

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const FIELDS = new Set(['deliveryId', 'expectedRevision', 'requestedStage', 'evidenceIds', 'idempotencyKey'])
const deny = (code: string, reason: string): DeliveryPromotionDecision => ({ allowed: false, code, reason })
const hash = (value: unknown): string => createHash('sha256').update(JSON.stringify(value)).digest('hex')

/** Validates a raw promotion request against the expected delivery identity. */
export function validateDeliveryPromotionRequest(
  input: unknown,
  expectedDeliveryId: string
): DeliveryPromotionRequestValidation {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((key) => !FIELDS.has(key)))
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  const candidate = input as Record<string, unknown>
  if (
    candidate.deliveryId !== expectedDeliveryId ||
    !SAFE.test((candidate.deliveryId ?? '') as string) ||
    !Number.isSafeInteger(candidate.expectedRevision) ||
    (candidate.expectedRevision as number) < 1 ||
    !(DELIVERY_STAGES as readonly string[]).includes(candidate.requestedStage as string)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  if (
    !Array.isArray(candidate.evidenceIds) ||
    candidate.evidenceIds.length > 100 ||
    new Set(candidate.evidenceIds).size !== candidate.evidenceIds.length ||
    candidate.evidenceIds.some((id) => !SAFE.test(id ?? ''))
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  if (
    typeof candidate.idempotencyKey !== 'string' ||
    !candidate.idempotencyKey ||
    candidate.idempotencyKey.length > 128 ||
    /[\r\n]/.test(candidate.idempotencyKey)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  return {
    ok: true,
    value: {
      requestId: randomUUID(),
      deliveryId: candidate.deliveryId as string,
      expectedRevision: candidate.expectedRevision as number,
      requestedStage: candidate.requestedStage as string,
      evidenceIds: [...candidate.evidenceIds] as string[],
      idempotencyKey: candidate.idempotencyKey,
    },
  }
}

/** Idempotency scope hash: binds the request to its controlling execution. */
export const deliveryScopeHash = (
  namespaceId: string,
  request: DeliveryPromotionRequest,
  execution: DeliveryExecutionContext
): string =>
  hash({
    namespaceId,
    deliveryId: request.deliveryId,
    caseId: execution.caseId,
    runtimeId: execution.runtimeId,
    idempotencyKey: request.idempotencyKey,
  })

/** Idempotency semantic hash: the exact promotion content being requested. */
export const deliverySemanticHash = (request: DeliveryPromotionRequest): string =>
  hash({
    deliveryId: request.deliveryId,
    expectedRevision: request.expectedRevision,
    requestedStage: request.requestedStage,
    evidenceIds: [...request.evidenceIds].sort(),
  })

/** Evaluates whether a promotion request may proceed against the current snapshot. */
export function evaluateDeliveryPromotion({
  request,
  snapshot,
  definition,
  evidence,
  execution,
}: DeliveryPromotionEvaluation): DeliveryPromotionDecision {
  if (!snapshot) return deny('DELIVERY_NOT_FOUND', 'delivery_not_found')
  if (snapshot.revision !== request.expectedRevision) return deny('REVISION_CONFLICT', 'stale_delivery_revision')
  if (
    snapshot.namespaceId !== execution.namespaceId ||
    snapshot.workflowId !== execution.workflowId ||
    snapshot.parentCaseId !== execution.caseId
  )
    return deny('DELIVERY_SCOPE_MISMATCH', 'controlling_execution_mismatch')
  if (snapshot.definitionHash !== definition.definitionHash)
    return deny('DELIVERY_DEFINITION_MISMATCH', 'definition_identity_mismatch')
  const currentIndex = (DELIVERY_STAGES as readonly string[]).indexOf(snapshot.stage),
    requestedIndex = (DELIVERY_STAGES as readonly string[]).indexOf(request.requestedStage)
  if (requestedIndex !== currentIndex + 1) return deny('ILLEGAL_PROMOTION', 'ordered_promotion_required')
  const checkpoint = definition.checkpoints.find((item) => item.stage === request.requestedStage)
  if (!checkpoint) return deny('DELIVERY_DEFINITION_MISMATCH', 'checkpoint_missing')
  const isHuman = checkpoint.responsibility.kind === 'human'
  if (
    isHuman
      ? !(execution.kind === 'factory-human' && execution.actorId && execution.runtimeId === 'factory-dashboard')
      : !(execution.kind === 'factory-control-plane' && execution.runtimeId === 'factory-dashboard')
  )
    return deny('ACTOR_NOT_AUTHORIZED', 'factory_responsibility_required')
  const selected: DeliveryEvidenceItem[] = []
  for (const id of request.evidenceIds) {
    const item = evidence.find((candidate) => candidate.evidenceId === id)
    if (!item) return deny('EVIDENCE_NOT_FOUND', 'evidence_not_found')
    // runtimeId on evidence is the runtime that recorded the fact (e.g. 'agentos'), not the dashboard.
    // We bind on namespace, workflow, delivery, environment hash, case, and head commit — not runtime.
    if (
      item.namespaceId !== snapshot.namespaceId ||
      item.workflowId !== snapshot.workflowId ||
      item.deliveryId !== snapshot.deliveryId ||
      item.environmentHash !== snapshot.environmentHash ||
      item.caseId !== snapshot.parentCaseId ||
      item.headCommit !== snapshot.headCommit
    )
      return deny('EVIDENCE_SCOPE_MISMATCH', 'bounded_fact_mismatch')
    selected.push(item)
  }
  for (const requirement of checkpoint.requiredEvidence) {
    const match = selected.find(
      (item) =>
        item.kind === requirement.kind &&
        item.outcome === requirement.outcome &&
        (!requirement.oracleId || item.oracleId === requirement.oracleId) &&
        item.source?.kind !== 'agent'
    )
    if (!match) return deny('PASS_EVIDENCE_REQUIRED', `${requirement.kind}:${requirement.outcome}`)
  }
  if (
    request.requestedStage === 'release-approved' &&
    !selected.some(
      (item) => item.kind === 'human-decision' && item.outcome === 'approved' && item.source?.kind === 'factory-human'
    )
  )
    return deny('HUMAN_APPROVAL_REQUIRED', 'release_approval_missing')
  if (
    request.requestedStage === 'deployed' &&
    currentIndex < (DELIVERY_STAGES as readonly string[]).indexOf('release-approved')
  )
    return deny('RELEASE_NOT_APPROVED', 'release_approval_missing')
  if (
    request.requestedStage === 'production-verified' &&
    !selected.some((item) => item.kind === 'smoke-result' && item.outcome === 'pass')
  )
    return deny('SMOKE_PASS_REQUIRED', 'production_smoke_missing')
  return { allowed: true }
}

/** Applies an allowed promotion to a snapshot, producing the next revision. */
export function applyDeliveryPromotion(
  snapshot: DeliveryPromotionSnapshot,
  request: DeliveryPromotionRequest,
  observedAt = new Date().toISOString()
): DeliveryPromotionSnapshot & { updatedAt: string } {
  return {
    ...snapshot,
    stage: request.requestedStage,
    revision: snapshot.revision + 1,
    updatedAt: observedAt,
    evidenceIds: [...new Set([...(snapshot.evidenceIds ?? []), ...request.evidenceIds])],
  }
}

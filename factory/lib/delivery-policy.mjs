import { createHash, randomUUID } from 'node:crypto'
import { DELIVERY_STAGES } from './delivery-definition.mjs'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const FIELDS = new Set(['deliveryId', 'expectedRevision', 'requestedStage', 'evidenceIds', 'idempotencyKey'])
const deny = (code, reason) => ({ allowed: false, code, reason })
const hash = (value) => createHash('sha256').update(JSON.stringify(value)).digest('hex')
export const DELIVERY_INITIAL_STAGE = 'implementation-ready'

export function validateDeliveryPromotionRequest(input, expectedDeliveryId) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((key) => !FIELDS.has(key)))
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  if (
    input.deliveryId !== expectedDeliveryId ||
    !SAFE.test(input.deliveryId ?? '') ||
    !Number.isSafeInteger(input.expectedRevision) ||
    input.expectedRevision < 1 ||
    !DELIVERY_STAGES.includes(input.requestedStage)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  if (
    !Array.isArray(input.evidenceIds) ||
    input.evidenceIds.length > 100 ||
    new Set(input.evidenceIds).size !== input.evidenceIds.length ||
    input.evidenceIds.some((id) => !SAFE.test(id ?? ''))
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  if (
    typeof input.idempotencyKey !== 'string' ||
    !input.idempotencyKey ||
    input.idempotencyKey.length > 128 ||
    /[\r\n]/.test(input.idempotencyKey)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_REQUEST' } }
  return {
    ok: true,
    value: {
      requestId: randomUUID(),
      deliveryId: input.deliveryId,
      expectedRevision: input.expectedRevision,
      requestedStage: input.requestedStage,
      evidenceIds: [...input.evidenceIds],
      idempotencyKey: input.idempotencyKey,
    },
  }
}
export const deliveryScopeHash = (namespaceId, request, execution) =>
  hash({
    namespaceId,
    deliveryId: request.deliveryId,
    caseId: execution.caseId,
    runtimeId: execution.runtimeId,
    idempotencyKey: request.idempotencyKey,
  })
export const deliverySemanticHash = (request) =>
  hash({
    deliveryId: request.deliveryId,
    expectedRevision: request.expectedRevision,
    requestedStage: request.requestedStage,
    evidenceIds: [...request.evidenceIds].sort(),
  })

export function evaluateDeliveryPromotion({ request, snapshot, definition, evidence, execution }) {
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
  const currentIndex = DELIVERY_STAGES.indexOf(snapshot.stage),
    requestedIndex = DELIVERY_STAGES.indexOf(request.requestedStage)
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
  const selected = []
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
  if (request.requestedStage === 'deployed' && currentIndex < DELIVERY_STAGES.indexOf('release-approved'))
    return deny('RELEASE_NOT_APPROVED', 'release_approval_missing')
  if (
    request.requestedStage === 'production-verified' &&
    !selected.some((item) => item.kind === 'smoke-result' && item.outcome === 'pass')
  )
    return deny('SMOKE_PASS_REQUIRED', 'production_smoke_missing')
  return { allowed: true }
}

export function applyDeliveryPromotion(snapshot, request, observedAt = new Date().toISOString()) {
  return {
    ...snapshot,
    stage: request.requestedStage,
    revision: snapshot.revision + 1,
    updatedAt: observedAt,
    evidenceIds: [...new Set([...(snapshot.evidenceIds ?? []), ...request.evidenceIds])],
  }
}

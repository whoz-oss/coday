/**
 * Pure delivery-operation domain: operation kinds and states, request
 * normalization, identity derivation, state-machine transitions and the
 * persisted operation-record contract.
 *
 * A delivery operation (deployment, production verification, rollback,
 * rollback verification) is identified by an idempotency scope hash bound to
 * its controlling execution and a semantic hash of its exact content.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-operation-definition.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 *
 * Domain purity: only `node:crypto` is used (hashing); no `node:fs`, HTTP,
 * AgentOS or Git CLI dependency.
 */

import { createHash } from 'node:crypto'

/** Delivery operation kinds, in vocabulary order. */
export const DELIVERY_OPERATION_KINDS = Object.freeze([
  'deployment',
  'production-verification',
  'rollback',
  'rollback-verification',
] as const)

/** One of the delivery operation kinds. */
export type DeliveryOperationKind = (typeof DELIVERY_OPERATION_KINDS)[number]

/** Delivery operation lifecycle states. */
export const DELIVERY_OPERATION_STATES = Object.freeze(['pending', 'running', 'succeeded', 'failed', 'indeterminate'])

/** One of the delivery operation lifecycle states. */
export type DeliveryOperationState = (typeof DELIVERY_OPERATION_STATES)[number]

/** Machine-readable error codes of the delivery-operation contract. */
export const DELIVERY_OPERATION_ERROR_CODES = Object.freeze({
  INVALID_REQUEST: 'INVALID_DELIVERY_OPERATION_REQUEST',
  INVALID_RECORD: 'INVALID_DELIVERY_OPERATION_RECORD',
  INVALID_TRANSITION: 'INVALID_DELIVERY_OPERATION_TRANSITION',
  RECONCILIATION_REQUIRED: 'DELIVERY_OPERATION_RECONCILIATION_REQUIRED',
} as const)

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/,
  SHA = /^[0-9a-f]{40}$/i,
  DIGEST = /^sha256:[0-9a-f]{64}$/i,
  MEDIA = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$/i

/**
 * Canonical JSON shape: object keys sorted recursively, arrays preserved in
 * order. Two values that differ only by key order hash identically.
 */
const canonical = (value: unknown): unknown =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((k) => [k, canonical((value as Record<string, unknown>)[k])])
        )
      : value

/** Stable `sha256:`-prefixed hash of a value's canonical JSON form. */
export const canonicalDeliveryHash = (value: unknown): string =>
  `sha256:${createHash('sha256')
    .update(JSON.stringify(canonical(value)))
    .digest('hex')}`

/** Validation failure of a delivery-operation request. */
export interface DeliveryOperationRequestFailure {
  ok: false
  error: { code: string; path: string; reason: string }
}

const fail = (path: string, reason = 'invalid_value'): DeliveryOperationRequestFailure => ({
  ok: false,
  error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_REQUEST, path, reason },
})
const exact = (v: unknown, fields: string[]): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every((k) => fields.includes(k))
const id = (v: unknown): v is string => typeof v === 'string' && SAFE.test(v),
  digest = (v: unknown): v is string => typeof v === 'string' && DIGEST.test(v),
  sha = (v: unknown): v is string => typeof v === 'string' && SHA.test(v)

/** An artifact reference: immutable content identity plus provenance. */
export interface DeliveryArtifactRef {
  digest: string
  mediaType: string
  producerRef: string
  buildRef: string
  sourceCommit: string
}

/** A release reference: the approved binding of an artifact to a commit. */
export interface DeliveryReleaseRef {
  releaseId: string
  artifactDigest: string
  sourceCommit: string
  approvedEvidenceId: string
}

/** A reference to a previously succeeded delivery operation. */
export interface DeliveryOperationRef {
  operationId: string
  kind: string
  state: string
  targetHash: string
  sourceCommit: string
  artifactDigest: string
}

function artifact(
  v: unknown,
  path = 'artifactRef'
): { ok: true; value: Readonly<DeliveryArtifactRef> } | DeliveryOperationRequestFailure {
  if (
    !exact(v, ['digest', 'mediaType', 'producerRef', 'buildRef', 'sourceCommit']) ||
    !digest(v.digest) ||
    !MEDIA.test((v.mediaType ?? '') as string) ||
    !id(v.producerRef) ||
    !id(v.buildRef) ||
    !sha(v.sourceCommit)
  )
    return fail(path)
  return {
    ok: true,
    value: Object.freeze({
      ...(v as unknown as DeliveryArtifactRef),
      digest: v.digest.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase(),
    }),
  }
}
function release(
  v: unknown,
  path = 'releaseRef'
): { ok: true; value: Readonly<DeliveryReleaseRef> } | DeliveryOperationRequestFailure {
  if (
    !exact(v, ['releaseId', 'artifactDigest', 'sourceCommit', 'approvedEvidenceId']) ||
    !id(v.releaseId) ||
    !digest(v.artifactDigest) ||
    !sha(v.sourceCommit) ||
    !id(v.approvedEvidenceId)
  )
    return fail(path)
  return {
    ok: true,
    value: Object.freeze({
      ...(v as unknown as DeliveryReleaseRef),
      artifactDigest: v.artifactDigest.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase(),
    }),
  }
}
function operationRef(
  v: unknown,
  path: string,
  kind: string
): { ok: true; value: Readonly<DeliveryOperationRef> } | DeliveryOperationRequestFailure {
  if (
    !exact(v, ['operationId', 'kind', 'state', 'targetHash', 'sourceCommit', 'artifactDigest']) ||
    !id(v.operationId) ||
    v.kind !== kind ||
    v.state !== 'succeeded' ||
    !digest(v.targetHash) ||
    !sha(v.sourceCommit) ||
    !digest(v.artifactDigest)
  )
    return fail(path)
  return {
    ok: true,
    value: Object.freeze({
      ...(v as unknown as DeliveryOperationRef),
      targetHash: v.targetHash.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase(),
      artifactDigest: v.artifactDigest.toLowerCase(),
    }),
  }
}
const BASE = ['kind', 'expectedRevision', 'idempotencyKey', 'targetId']
const SPEC: Record<string, string[]> = {
  deployment: ['artifactRef', 'releaseRef'],
  'production-verification': ['deploymentRef'],
  rollback: ['deploymentRef', 'priorArtifactRef', 'priorReleaseRef', 'rollbackRequestId', 'approvedEvidenceId'],
  'rollback-verification': ['rollbackRef'],
}

/** A normalized, frozen delivery-operation request. */
export interface NormalizedDeliveryOperationRequest {
  kind: DeliveryOperationKind
  expectedRevision: number
  idempotencyKey: string
  targetId: string
  artifactRef?: Readonly<DeliveryArtifactRef>
  releaseRef?: Readonly<DeliveryReleaseRef>
  deploymentRef?: Readonly<DeliveryOperationRef>
  rollbackRef?: Readonly<DeliveryOperationRef>
  priorArtifactRef?: Readonly<DeliveryArtifactRef>
  priorReleaseRef?: Readonly<DeliveryReleaseRef>
  rollbackRequestId?: string
  approvedEvidenceId?: string
}

/** Result of normalizing a raw delivery-operation request. */
export type DeliveryOperationRequestNormalization =
  | { ok: true; value: Readonly<NormalizedDeliveryOperationRequest> }
  | DeliveryOperationRequestFailure

/** Validates and normalizes a raw delivery-operation request. */
export function normalizeDeliveryOperationRequest(input: unknown): DeliveryOperationRequestNormalization {
  const candidate = input as Record<string, unknown> | null | undefined
  if (
    !candidate ||
    !(DELIVERY_OPERATION_KINDS as readonly string[]).includes(candidate.kind as string) ||
    !exact(candidate, [...BASE, ...(SPEC[candidate.kind as string] as string[])])
  )
    return fail('$', 'unknown_or_missing_field')
  if (
    !Number.isSafeInteger(candidate.expectedRevision) ||
    (candidate.expectedRevision as number) < 1 ||
    !id(candidate.idempotencyKey) ||
    !id(candidate.targetId)
  )
    return fail('$')
  const out: NormalizedDeliveryOperationRequest = {
    kind: candidate.kind as DeliveryOperationKind,
    expectedRevision: candidate.expectedRevision as number,
    idempotencyKey: candidate.idempotencyKey,
    targetId: candidate.targetId,
  }
  if (candidate.kind === 'deployment') {
    const a = artifact(candidate.artifactRef),
      r = release(candidate.releaseRef)
    if (!a.ok) return a
    if (!r.ok) return r
    if (a.value.digest !== r.value.artifactDigest || a.value.sourceCommit !== r.value.sourceCommit)
      return fail('releaseRef', 'artifact_identity_mismatch')
    Object.assign(out, { artifactRef: a.value, releaseRef: r.value })
  }
  if (candidate.kind === 'production-verification') {
    const d = operationRef(candidate.deploymentRef, 'deploymentRef', 'deployment')
    if (!d.ok) return d
    out.deploymentRef = d.value
  }
  if (candidate.kind === 'rollback') {
    const d = operationRef(candidate.deploymentRef, 'deploymentRef', 'deployment'),
      a = artifact(candidate.priorArtifactRef, 'priorArtifactRef'),
      r = release(candidate.priorReleaseRef, 'priorReleaseRef')
    if (!d.ok) return d
    if (!a.ok) return a
    if (!r.ok) return r
    if (
      !id(candidate.rollbackRequestId) ||
      !id(candidate.approvedEvidenceId) ||
      a.value.digest !== r.value.artifactDigest ||
      a.value.sourceCommit !== r.value.sourceCommit
    )
      return fail('$', 'rollback_identity_mismatch')
    Object.assign(out, {
      deploymentRef: d.value,
      priorArtifactRef: a.value,
      priorReleaseRef: r.value,
      rollbackRequestId: candidate.rollbackRequestId,
      approvedEvidenceId: candidate.approvedEvidenceId,
    })
  }
  if (candidate.kind === 'rollback-verification') {
    const r = operationRef(candidate.rollbackRef, 'rollbackRef', 'rollback')
    if (!r.ok) return r
    out.rollbackRef = r.value
  }
  return { ok: true, value: Object.freeze(out) }
}

/** The controlling scope an operation identity is derived from. */
export interface DeliveryOperationScope {
  namespaceId: string
  workflowId: string
  deliveryId: string
  caseId: string
  runtimeId: string
}

/** The derived identity of a delivery operation. */
export interface DeliveryOperationIdentity {
  operationId: string
  scopeHash: string
  semanticHash: string
}

/** Result of deriving a delivery-operation identity. */
export type DeliveryOperationIdentityDerivation =
  | { ok: true; value: DeliveryOperationIdentity }
  | DeliveryOperationRequestFailure

/** Derives the deterministic idempotency identity of an operation request. */
export function deriveDeliveryOperationIdentity(
  { namespaceId, workflowId, deliveryId, caseId, runtimeId }: DeliveryOperationScope,
  request: NormalizedDeliveryOperationRequest,
  targetHash: unknown
): DeliveryOperationIdentityDerivation {
  for (const v of [namespaceId, workflowId, deliveryId, caseId, runtimeId]) if (!id(v)) return fail('scope')
  if (!digest(targetHash)) return fail('targetHash')
  const scopeHash = canonicalDeliveryHash({
    namespaceId,
    workflowId,
    deliveryId,
    caseId,
    runtimeId,
    idempotencyKey: request.idempotencyKey,
  })
  const semanticHash = canonicalDeliveryHash({
    kind: request.kind,
    expectedRevision: request.expectedRevision,
    targetHash,
    ...Object.fromEntries(
      Object.entries(request).filter(([k]) => /Ref$/.test(k) || ['rollbackRequestId', 'approvedEvidenceId'].includes(k))
    ),
  })
  return { ok: true, value: { operationId: `dop_${scopeHash.slice(7, 39)}`, scopeHash, semanticHash } }
}
const ALLOWED: Record<string, string[]> = {
  pending: ['running', 'failed'],
  running: ['succeeded', 'failed', 'indeterminate'],
  indeterminate: ['succeeded', 'failed'],
  succeeded: [],
  failed: [],
}

/** An adapter observation used to reconcile an indeterminate operation. */
export interface DeliveryOperationObservation {
  operationId?: unknown
  state?: unknown
  [key: string]: unknown
}

/** Result of validating an operation state transition. */
export type DeliveryOperationTransitionValidation = { ok: true } | { ok: false; error: { code: string } }

/** Validates a persisted operation state transition against the state machine. */
export function validateDeliveryOperationTransition(
  previous: Record<string, unknown> | null | undefined,
  next: Record<string, unknown> | null | undefined,
  { inspectedObservation }: { inspectedObservation?: DeliveryOperationObservation } = {}
): DeliveryOperationTransitionValidation {
  if (
    !previous ||
    !next ||
    previous.operationId !== next.operationId ||
    !DELIVERY_OPERATION_STATES.includes(previous.state as DeliveryOperationState) ||
    !ALLOWED[previous.state as string]?.includes(next.state as string)
  )
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_TRANSITION } }
  if (
    previous.state === 'indeterminate' &&
    (!inspectedObservation ||
      inspectedObservation.operationId !== previous.operationId ||
      inspectedObservation.state !== next.state ||
      !['succeeded', 'failed'].includes(next.state as string) ||
      next.resolvedOperationId !== previous.operationId)
  )
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.RECONCILIATION_REQUIRED } }
  return { ok: true }
}

/** Result of validating a persisted operation record. */
export type DeliveryOperationRecordValidation =
  | { ok: true; value: Readonly<Record<string, unknown>> }
  | { ok: false; error: { code: string; path?: string } }

/** Validates a persisted delivery-operation record against the contract. */
export function validateDeliveryOperationRecord(v: unknown): DeliveryOperationRecordValidation {
  const fields = [
    'recordType',
    'operationId',
    'kind',
    'expectedRevision',
    'targetRef',
    'artifactRef',
    'releaseRef',
    'deploymentRef',
    'rollbackRef',
    'state',
    'attempt',
    'requestedAt',
    'startedAt',
    'completedAt',
    'execution',
    'adapterCorrelation',
    'scopeHash',
    'semanticHash',
    'result',
    'error',
    'resolvedOperationId',
    'sourceCommit',
    'artifactDigest',
    'rollbackRequestId',
    'approvedEvidenceId',
  ]
  if (
    !exact(v, fields) ||
    v.recordType !== 'delivery-operation' ||
    !id(v.operationId) ||
    !(DELIVERY_OPERATION_KINDS as readonly string[]).includes(v.kind as string) ||
    !DELIVERY_OPERATION_STATES.includes(v.state as DeliveryOperationState) ||
    !Number.isSafeInteger(v.expectedRevision) ||
    !Number.isSafeInteger(v.attempt) ||
    (v.attempt as number) < 0 ||
    !digest(v.scopeHash) ||
    !digest(v.semanticHash)
  )
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD } }
  for (const key of ['requestedAt', 'startedAt', 'completedAt'])
    if (
      v[key] !== undefined &&
      (!Number.isFinite(Date.parse(v[key] as string)) ||
        new Date(Date.parse(v[key] as string)).toISOString() !== v[key])
    )
      return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD, path: key } }
  return { ok: true, value: Object.freeze({ ...v }) }
}

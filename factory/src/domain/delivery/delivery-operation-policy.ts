/**
 * Pure delivery-operation policy: decides whether a normalized operation
 * request may proceed against the current delivery snapshot and the trusted
 * target, and resolves verification requests against the trusted suite.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-operation-policy.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 *
 * Domain purity: no `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

import type { NormalizedDeliveryOperationRequest } from './delivery-operation-definition.js'

/** The snapshot surface the operation policy reads. */
export interface DeliveryOperationPolicySnapshot {
  revision: number
  headCommit: string
  stage: string
}

/** The trusted target surface the operation policy binds on. */
export interface DeliveryOperationPolicyTarget {
  targetHash: string
  supportsRollback?: boolean
  verificationSuiteId?: string
  verificationSuiteHash?: string
}

/** An existing operation record surface used for indeterminacy checks. */
export interface DeliveryOperationPolicyExistingOperation {
  state?: unknown
  resolvedOperationId?: unknown
}

/** Inputs of an operation policy evaluation. */
export interface DeliveryOperationPolicyEvaluation {
  request: NormalizedDeliveryOperationRequest
  snapshot: DeliveryOperationPolicySnapshot | null
  target: DeliveryOperationPolicyTarget | null
  identity?: { targetHash?: unknown }
  existingOperations?: DeliveryOperationPolicyExistingOperation[]
}

/** An operation policy decision: allowed, or denied with a code and reason. */
export type DeliveryOperationPolicyDecision = { allowed: true } | { allowed: false; code: string; reason: string }

const deny = (code: string, reason: string): DeliveryOperationPolicyDecision => ({ allowed: false, code, reason }),
  pass = (): DeliveryOperationPolicyDecision => ({ allowed: true })

/** Evaluates whether an operation request may proceed against the snapshot and target. */
export function evaluateDeliveryOperationPolicy({
  request,
  snapshot,
  target,
  identity,
  existingOperations = [],
}: DeliveryOperationPolicyEvaluation): DeliveryOperationPolicyDecision {
  if (!snapshot) return deny('DELIVERY_NOT_FOUND', 'delivery_not_found')
  if (snapshot.revision !== request.expectedRevision) return deny('REVISION_CONFLICT', 'stale_delivery_revision')
  if (!target) return deny('DELIVERY_TARGET_NOT_FOUND', 'trusted_target_missing')
  if (identity?.targetHash !== target.targetHash)
    return deny('DELIVERY_TARGET_HASH_MISMATCH', 'target_binding_mismatch')
  if (existingOperations.some((o) => o.state === 'indeterminate' && !o.resolvedOperationId))
    return deny('DELIVERY_OPERATION_INDETERMINATE', 'reconciliation_required')
  const head = snapshot.headCommit
  if (request.artifactRef && request.artifactRef.sourceCommit !== head)
    return deny('SOURCE_COMMIT_MISMATCH', 'artifact_not_at_head')
  if (request.releaseRef && request.releaseRef.sourceCommit !== head)
    return deny('SOURCE_COMMIT_MISMATCH', 'release_not_at_head')
  if (request.kind === 'deployment' && snapshot.stage !== 'release-approved')
    return deny('RELEASE_NOT_APPROVED', 'release_approved_stage_required')
  if (request.kind === 'production-verification') {
    if (snapshot.stage !== 'deployed' || request.deploymentRef?.state !== 'succeeded')
      return deny('SUCCESSFUL_DEPLOYMENT_REQUIRED', 'linked_deployment_required')
    if (request.deploymentRef?.targetHash !== target.targetHash || request.deploymentRef?.sourceCommit !== head)
      return deny('DEPLOYMENT_SCOPE_MISMATCH', 'deployment_binding_mismatch')
    if (!target.verificationSuiteId || !target.verificationSuiteHash)
      return deny('VERIFICATION_SUITE_NOT_CONFIGURED', 'trusted_suite_required')
  }
  if (request.kind === 'rollback') {
    if (request.deploymentRef?.state !== 'succeeded')
      return deny('SUCCESSFUL_DEPLOYMENT_REQUIRED', 'linked_deployment_required')
    if (!target.supportsRollback) return deny('ROLLBACK_NOT_SUPPORTED', 'target_disallows_rollback')
    if (!request.approvedEvidenceId) return deny('ROLLBACK_APPROVAL_REQUIRED', 'approval_evidence_required')
    if (request.priorArtifactRef?.digest === request.deploymentRef?.artifactDigest)
      return deny('ROLLBACK_RELEASE_UNCHANGED', 'prior_release_must_differ')
    if (request.deploymentRef?.targetHash !== target.targetHash || request.deploymentRef?.sourceCommit !== head)
      return deny('DEPLOYMENT_SCOPE_MISMATCH', 'deployment_binding_mismatch')
  }
  if (request.kind === 'rollback-verification') {
    if (request.rollbackRef?.state !== 'succeeded')
      return deny('SUCCESSFUL_ROLLBACK_REQUIRED', 'linked_rollback_required')
    if (request.rollbackRef?.targetHash !== target.targetHash)
      return deny('ROLLBACK_SCOPE_MISMATCH', 'rollback_binding_mismatch')
    if (!target.verificationSuiteId || !target.verificationSuiteHash)
      return deny('VERIFICATION_SUITE_NOT_CONFIGURED', 'trusted_suite_required')
  }
  return pass()
}

/** A verification request bound to its trusted verification suite. */
export type DeliveryVerificationRequestResolution =
  | { ok: true; value: Readonly<Record<string, unknown>> }
  | { ok: false; error: { code: 'VERIFICATION_SUITE_NOT_CONFIGURED' } }

/** Binds a verification request to the trusted suite of the target. */
export function resolveDeliveryVerificationRequest(
  request: NormalizedDeliveryOperationRequest,
  target: DeliveryOperationPolicyTarget | null | undefined
): DeliveryVerificationRequestResolution {
  if (!target?.verificationSuiteId || !target?.verificationSuiteHash)
    return { ok: false, error: { code: 'VERIFICATION_SUITE_NOT_CONFIGURED' } }
  return {
    ok: true,
    value: Object.freeze({
      ...request,
      verificationSuiteRef: Object.freeze({
        suiteId: target.verificationSuiteId,
        suiteHash: target.verificationSuiteHash,
      }),
    }),
  }
}

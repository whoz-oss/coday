/**
 * Trusted Factory control-plane for delivery operations (deployments,
 * production verifications, rollbacks and their approvals).
 *
 * Every operation is prepared through the same pipeline: untrusted-input
 * rejection, request normalization, delivery resolution, trusted-target
 * binding and pure policy evaluation. Adapter execution itself is delegated
 * to the injected registries.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-operation-controller.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 */

import {
  canonicalDeliveryHash,
  normalizeDeliveryOperationRequest,
} from '../../domain/delivery/delivery-operation-definition.js'
import type {
  DeliveryOperationKind,
  NormalizedDeliveryOperationRequest,
} from '../../domain/delivery/delivery-operation-definition.js'
import {
  evaluateDeliveryOperationPolicy,
  resolveDeliveryVerificationRequest,
} from '../../domain/delivery/delivery-operation-policy.js'
import type { DeliveryOperationProjection, DeliveryStore } from '../../adapters/persistence/delivery-store.js'
import type { DeliveryTarget, DeliveryTargetRegistryLike } from '../../adapters/delivery/delivery-target-registry.js'
import type {
  DeliveryController,
  DeliveryControllerFailure,
  DeliveryResolution,
  DeliveryTrustIdentity,
} from './delivery-controller.js'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const REASON = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
const FORBIDDEN = new Set([
  'targetConfig',
  'adapterId',
  'adapterTargetRef',
  'callbackUrl',
  'command',
  'env',
  'credentials',
  'result',
  'outcome',
  'success',
  'sourceKind',
  'facts',
])
const exact = (value: unknown, fields: string[]): value is Record<string, unknown> =>
  value !== null &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).every((key) => fields.includes(key)) &&
  !Object.keys(value).some((key) => FORBIDDEN.has(key))
const response = (
  error: { code?: string; [key: string]: unknown } | undefined,
  fallback = 409
): DeliveryControllerFailure => ({
  ok: false,
  status:
    error?.code?.includes('NOT_CONFIGURED') || error?.code === 'DELIVERY_TARGET_REGISTRY_UNAVAILABLE' ? 503 : fallback,
  error: error as { code: string },
})
const execution = (identity: DeliveryTrustIdentity, workflowId: string): Record<string, unknown> => ({
  kind: 'factory-control-plane',
  namespaceId: identity.namespaceId,
  workflowId,
  caseId: identity.caseId,
  runtimeId: 'factory-dashboard',
  actorId: identity.resolvedActorId ?? identity.actorId ?? 'factory-operator',
})
const requestId = (scopeHash: string): string => `rrq_${scopeHash.slice(7, 39)}`

/** HTTP-shaped result of an operation controller method. */
export type DeliveryOperationControllerResult = { ok: true; status: number; data: unknown } | DeliveryControllerFailure

/** A prepared operation: resolved delivery, target, request, adapter and projection. */
export interface DeliveryOperationPreparation {
  ok: true
  resolved: DeliveryResolution
  target: DeliveryTarget
  request: Readonly<NormalizedDeliveryOperationRequest>
  adapter: unknown
  projection: DeliveryOperationProjection
}

/** Dependencies of `DeliveryOperationController`. */
export interface DeliveryOperationControllerOptions {
  deliveryController: DeliveryController
  store: DeliveryStore
  targetRegistry: DeliveryTargetRegistryLike
  deploymentAdapters?: Map<string, unknown>
  verificationAdapters?: Map<string, unknown>
}

export class DeliveryOperationController {
  private readonly deliveryController: DeliveryController
  private readonly store: DeliveryStore
  private readonly targetRegistry: DeliveryTargetRegistryLike
  private readonly deploymentAdapters: Map<string, unknown>
  private readonly verificationAdapters: Map<string, unknown>

  constructor({
    deliveryController,
    store,
    targetRegistry,
    deploymentAdapters = new Map(),
    verificationAdapters = new Map(),
  }: DeliveryOperationControllerOptions) {
    this.deliveryController = deliveryController
    this.store = store
    this.targetRegistry = targetRegistry
    this.deploymentAdapters = deploymentAdapters
    this.verificationAdapters = verificationAdapters
  }
  async resolve(
    identity: DeliveryTrustIdentity,
    workflowId: string
  ): Promise<DeliveryResolution | DeliveryControllerFailure> {
    return this.deliveryController.resolve(identity, workflowId)
  }
  async target(targetId: unknown): Promise<{ ok: true; target: DeliveryTarget } | DeliveryControllerFailure> {
    const found = await this.targetRegistry.lookup(targetId)
    return found.ok
      ? found
      : response(found.error, found.error?.code === 'DELIVERY_TARGET_REGISTRY_UNAVAILABLE' ? 503 : 404)
  }
  adapter(
    registry: Map<string, unknown>,
    target: DeliveryTarget
  ): { ok: true; adapter: unknown } | DeliveryControllerFailure {
    const adapter = registry.get?.(target.adapterId)
    return adapter ? { ok: true, adapter } : response({ code: 'DELIVERY_ADAPTER_NOT_CONFIGURED' }, 503)
  }
  async status(identity: DeliveryTrustIdentity, workflowId: string): Promise<DeliveryOperationControllerResult> {
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId)
    return {
      ok: true,
      status: 200,
      data: {
        ...resolved.snapshot,
        deliveryOperations: projection.operations,
        unresolvedIndeterminate: projection.unresolvedIndeterminate,
        rollbackRequests: projection.rollbackRequests,
      },
    }
  }
  async prepare(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown,
    kind: DeliveryOperationKind,
    fields: string[],
    adapterRegistry: Map<string, unknown>
  ): Promise<DeliveryOperationPreparation | DeliveryControllerFailure> {
    if (!exact(body, fields)) return response({ code: 'UNTRUSTED_DELIVERY_INPUT' }, 400)
    const normalized = normalizeDeliveryOperationRequest({ ...body, kind })
    if (!normalized.ok) return response(normalized.error, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const targetResult = await this.target(normalized.value.targetId)
    if (!targetResult.ok) return targetResult
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId)
    const decision = evaluateDeliveryOperationPolicy({
      request: normalized.value,
      snapshot: resolved.snapshot,
      target: targetResult.target,
      identity: { targetHash: targetResult.target.targetHash },
      existingOperations: projection.operations,
    })
    if (!decision.allowed) return response({ code: decision.code, reason: decision.reason })
    const available = this.adapter(adapterRegistry, targetResult.target)
    if (!available.ok) return available
    return {
      ok: true,
      resolved,
      target: targetResult.target,
      request: normalized.value,
      adapter: available.adapter,
      projection,
    }
  }
  async deploy(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    const prepared = await this.prepare(
      identity,
      workflowId,
      body,
      'deployment',
      ['expectedRevision', 'idempotencyKey', 'targetId', 'artifactRef', 'releaseRef'],
      this.deploymentAdapters
    )
    if (!prepared.ok) return prepared
    return response({ code: 'DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED' }, 503)
  }
  async verify(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    const prepared = await this.prepare(
      identity,
      workflowId,
      body,
      'production-verification',
      ['expectedRevision', 'idempotencyKey', 'targetId', 'deploymentRef'],
      this.verificationAdapters
    )
    if (!prepared.ok) return prepared
    const suite = resolveDeliveryVerificationRequest(prepared.request, prepared.target)
    if (!suite.ok) return response(suite.error)
    return response({ code: 'DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED' }, 503)
  }
  async reconcile(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    if (!exact(body, ['operationId'])) return response({ code: 'UNTRUSTED_DELIVERY_INPUT' }, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId)
    const operation = projection.operations.find((item) => item.operationId === body.operationId)
    if (!operation || !['running', 'indeterminate'].includes(operation.state as string))
      return response({ code: 'DELIVERY_OPERATION_NOT_RECONCILABLE' }, 409)
    const adapterResult = this.adapter(
      (operation.kind as string).includes('verification') ? this.verificationAdapters : this.deploymentAdapters,
      operation.targetRef as DeliveryTarget
    )
    if (!adapterResult.ok) return adapterResult
    return response({ code: 'DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED' }, 503)
  }
  async requestRollback(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    const fields = [
      'expectedRevision',
      'idempotencyKey',
      'targetId',
      'deploymentRef',
      'priorArtifactRef',
      'priorReleaseRef',
      'reasonCode',
      'reason',
    ]
    if (
      !exact(body, fields) ||
      !SAFE.test((body.idempotencyKey ?? '') as string) ||
      !SAFE.test((body.targetId ?? '') as string) ||
      !REASON.test((body.reasonCode ?? '') as string) ||
      (body.reason !== undefined &&
        (typeof body.reason !== 'string' || body.reason.length < 1 || body.reason.length > 512))
    )
      return response({ code: 'INVALID_ROLLBACK_REQUEST' }, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const targetResult = await this.target(body.targetId)
    if (!targetResult.ok) return targetResult
    if (resolved.snapshot.revision !== body.expectedRevision) return response({ code: 'REVISION_CONFLICT' })
    const scopeHash = canonicalDeliveryHash({
      namespaceId: identity.namespaceId,
      workflowId,
      deliveryId: resolved.snapshot.deliveryId,
      caseId: identity.caseId,
      runtimeId: 'factory-dashboard',
      idempotencyKey: body.idempotencyKey,
    })
    const semanticHash = canonicalDeliveryHash({
      targetHash: targetResult.target.targetHash,
      deploymentRef: body.deploymentRef,
      priorArtifactRef: body.priorArtifactRef,
      priorReleaseRef: body.priorReleaseRef,
      reasonCode: body.reasonCode,
      reason: body.reason ?? null,
      expectedRevision: body.expectedRevision,
    })
    const result = await this.store.createRollbackRequest({
      namespaceId: identity.namespaceId,
      deliveryId: resolved.snapshot.deliveryId,
      workflowId,
      caseId: identity.caseId,
      runtimeId: 'factory-dashboard',
      request: {
        rollbackRequestId: requestId(scopeHash),
        expectedRevision: body.expectedRevision,
        idempotencyKey: body.idempotencyKey,
        targetId: body.targetId,
        targetHash: targetResult.target.targetHash,
        deploymentRef: body.deploymentRef,
        priorArtifactRef: body.priorArtifactRef,
        priorReleaseRef: body.priorReleaseRef,
        reasonCode: body.reasonCode,
        reason: body.reason,
        scopeHash,
        semanticHash,
      },
      execution: execution(identity, workflowId),
    })
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.request } : response(result.error)
  }
  async approveRollback(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    requestIdValue: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    if (
      !exact(body, ['expectedRevision', 'idempotencyKey']) ||
      !SAFE.test(requestIdValue ?? '') ||
      !SAFE.test((body.idempotencyKey ?? '') as string)
    )
      return response({ code: 'INVALID_ROLLBACK_APPROVAL' }, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const result = await this.store.approveRollbackRequest(
      identity.namespaceId,
      resolved.snapshot.deliveryId,
      requestIdValue,
      {
        expectedRevision: body.expectedRevision as number,
        idempotencyKey: body.idempotencyKey as string,
        execution: execution(identity, workflowId),
      }
    )
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.request } : response(result.error)
  }
  async executeRollback(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    requestIdValue: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    if (!exact(body, ['expectedRevision', 'idempotencyKey']) || !SAFE.test(requestIdValue ?? ''))
      return response({ code: 'UNTRUSTED_DELIVERY_INPUT' }, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId)
    const rollback = projection.rollbackRequests.find((item) => item.rollbackRequestId === requestIdValue)
    if (!rollback) return response({ code: 'ROLLBACK_REQUEST_NOT_FOUND' }, 404)
    if (rollback.status !== 'approved') return response({ code: 'ROLLBACK_APPROVAL_REQUIRED' })
    const targetResult = await this.target(rollback.targetId)
    if (!targetResult.ok) return targetResult
    const available = this.adapter(this.deploymentAdapters, targetResult.target)
    if (!available.ok) return available
    return response({ code: 'DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED' }, 503)
  }
  async verifyRollback(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    requestIdValue: string,
    body: unknown
  ): Promise<DeliveryOperationControllerResult> {
    if (
      !exact(body, ['expectedRevision', 'idempotencyKey', 'rollbackRef', 'targetId']) ||
      !SAFE.test(requestIdValue ?? '')
    )
      return response({ code: 'UNTRUSTED_DELIVERY_INPUT' }, 400)
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const targetResult = await this.target(body.targetId)
    if (!targetResult.ok) return targetResult
    const available = this.adapter(this.verificationAdapters, targetResult.target)
    if (!available.ok) return available
    return response({ code: 'DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED' }, 503)
  }
}

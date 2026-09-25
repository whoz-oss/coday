/**
 * File-backed delivery store.
 *
 * A directory per delivery, addressed by a digest of `namespaceId:deliveryId`
 * so the raw delivery id never appears as a path segment. Each delivery holds
 * an atomic `delivery.json` snapshot, an append-only `operations.jsonl`
 * journal and a `pending.json` write-ahead marker that makes a crash between
 * the journal append and the snapshot rename recoverable.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/delivery-store.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Persistence shape (`delivery.json`, `operations.jsonl`, `pending.json` field
 * names, revision numbering, scope/semantic hashes and rollback records) is
 * part of the on-disk contract and must not change.
 */

import { createHash, randomBytes } from 'node:crypto'
import { appendFile, mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { dirname, isAbsolute, join } from 'node:path'
import {
  deliveryScopeHash,
  deliverySemanticHash,
  evaluateDeliveryPromotion,
  applyDeliveryPromotion,
  type DeliveryEvidenceItem,
  type DeliveryExecutionContext,
  type DeliveryPromotionRequest,
  type HashedDeliveryDefinition,
} from '../../domain/delivery/delivery-policy.js'
import {
  normalizeDeliveryOperationRequest,
  deriveDeliveryOperationIdentity,
  validateDeliveryOperationRecord,
  validateDeliveryOperationTransition,
  type DeliveryOperationObservation,
} from '../../domain/delivery/delivery-operation-definition.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const HASH = /^sha256:[0-9a-f]{64}$/

/**
 * Canonical JSON shape: object keys sorted recursively, `undefined` values
 * dropped, arrays preserved in order.
 */
const canonical = (value: unknown): unknown =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .filter((key) => (value as Record<string, unknown>)[key] !== undefined)
            .sort()
            .map((key) => [key, canonical((value as Record<string, unknown>)[key])])
        )
      : value
const hash = (value: unknown): string =>
  createHash('sha256')
    .update(JSON.stringify(canonical(value)))
    .digest('hex')
async function sync(path: string): Promise<void> {
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}
async function atomic(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  const temp = `${path}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`
  const handle = await open(temp, 'wx', 0o600)
  try {
    await handle.writeFile(`${JSON.stringify(value)}\n`)
    await handle.sync()
  } finally {
    await handle.close()
  }
  await rename(temp, path)
  await sync(dirname(path))
}
async function append(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { mode: 0o600 })
  await sync(path)
}
const validSnapshot = (value: unknown): value is Record<string, unknown> =>
  value !== null &&
  typeof value === 'object' &&
  (value as Record<string, unknown>).schemaVersion === '1' &&
  UUID.test(((value as Record<string, unknown>).namespaceId ?? '') as string) &&
  SAFE.test(((value as Record<string, unknown>).deliveryId ?? '') as string) &&
  SAFE.test(((value as Record<string, unknown>).workflowId ?? '') as string) &&
  UUID.test(((value as Record<string, unknown>).environmentId ?? '') as string) &&
  HASH.test(((value as Record<string, unknown>).environmentHash ?? '') as string) &&
  UUID.test(((value as Record<string, unknown>).parentCaseId ?? '') as string) &&
  SAFE.test(((value as Record<string, unknown>).runtimeId ?? '') as string) &&
  SHA.test(((value as Record<string, unknown>).baseCommit ?? '') as string) &&
  SHA.test(((value as Record<string, unknown>).headCommit ?? '') as string) &&
  Number.isSafeInteger((value as Record<string, unknown>).revision) &&
  ((value as Record<string, unknown>).revision as number) > 0

/** A persisted delivery snapshot. */
export interface DeliverySnapshot {
  schemaVersion: '1'
  namespaceId: string
  deliveryId: string
  workflowId: string
  environmentId: string
  environmentHash: string
  parentCaseId: string
  runtimeId: string
  baseCommit: string
  headCommit: string
  stage: string
  revision: number
  definitionHash: string
  evidenceIds?: string[]
  snapshotHash?: string | undefined
  [key: string]: unknown
}

/** The four paths a delivery directory holds. */
export interface DeliveryStorePaths {
  directory: string
  snapshot: string
  journal: string
  pending: string
}

/** One record of the append-only operations journal. */
export interface DeliveryJournalRecord {
  [key: string]: unknown
  recordType?: string
  operationId?: string
  rollbackRequestId?: string
  resolvedOperationId?: string
  state?: string
  scopeHash?: string
  semanticHash?: string
  approvalScopeHash?: string
  approvalSemanticHash?: string
}

/** Projection of the journal into current operations and rollback requests. */
export interface DeliveryOperationProjection {
  history: DeliveryJournalRecord[]
  operations: DeliveryJournalRecord[]
  rollbackRequests: DeliveryJournalRecord[]
  rollbackRequestHistory: DeliveryJournalRecord[]
  unresolvedIndeterminate: DeliveryJournalRecord[]
}

/** A store failure carrying a machine code (and optional extra fields). */
export interface DeliveryStoreFailure {
  ok: false
  error: { code: string; [key: string]: unknown }
}

/** Result of a store write that either changed state or reported a failure. */
export type DeliveryStoreWriteResult =
  | { ok: true; changed: boolean; idempotent?: boolean; snapshot?: DeliverySnapshot | null; [key: string]: unknown }
  | DeliveryStoreFailure

/** Injected fault seam, awaited between persistence steps. */
export type DeliveryStoreFault = (seam: string) => Promise<void> | void

/** Options accepted by `DeliveryStore`. */
export interface DeliveryStoreOptions {
  fault?: DeliveryStoreFault
}

/** Input of a promotion against the store. */
export interface DeliveryStorePromoteInput {
  namespaceId: string
  request: DeliveryPromotionRequest
  definition: HashedDeliveryDefinition
  evidence: DeliveryEvidenceItem[]
  execution: DeliveryExecutionContext
}

/** Input of a rollback-request creation against the store. */
export interface DeliveryStoreRollbackRequestInput {
  namespaceId: string
  deliveryId: string
  workflowId: string
  caseId: string
  runtimeId: string
  request: Record<string, unknown>
  execution: Record<string, unknown>
}

/** Input of a rollback-request approval against the store. */
export interface DeliveryStoreRollbackApprovalInput {
  expectedRevision: number
  idempotencyKey: string
  execution: Record<string, unknown>
}

/** Input of a delivery-operation creation against the store. */
export interface DeliveryStoreOperationInput {
  namespaceId: string
  workflowId: string
  deliveryId: string
  caseId: string
  runtimeId: string
  request: unknown
  targetRef?: Record<string, unknown>
  execution: Record<string, unknown>
}

/** A requested state transition of a persisted delivery operation. */
export interface DeliveryOperationTransitionInput {
  state: string
  adapterCorrelation?: unknown
  result?: unknown
  error?: unknown
  resolvedOperationId?: unknown
}

export class DeliveryStore {
  private readonly dataRoot: string
  private readonly fault: DeliveryStoreFault
  private readonly locks: Map<string, Promise<unknown>>

  constructor(dataRoot: string, { fault = async () => {} }: DeliveryStoreOptions = {}) {
    if (!isAbsolute(dataRoot)) throw new Error('INVALID_DATA_ROOT')
    this.dataRoot = dataRoot
    this.fault = fault
    this.locks = new Map()
  }
  async initialize(): Promise<void> {
    await mkdir(join(this.dataRoot, 'deliveries'), { recursive: true })
  }
  paths(namespaceId: string, deliveryId: string): DeliveryStorePaths {
    if (!UUID.test(namespaceId ?? '') || !SAFE.test(deliveryId ?? '')) throw new Error('INVALID_DELIVERY_SCOPE')
    const directory = join(
      this.dataRoot,
      'deliveries',
      namespaceId,
      createHash('sha256').update(`${namespaceId}:${deliveryId}`).digest('hex')
    )
    return {
      directory,
      snapshot: join(directory, 'delivery.json'),
      journal: join(directory, 'operations.jsonl'),
      pending: join(directory, 'pending.json'),
    }
  }
  private _locked<T>(namespaceId: string, deliveryId: string, action: () => Promise<T>): Promise<T> {
    const key = `${namespaceId}\0${deliveryId}`,
      prior = this.locks.get(key) ?? Promise.resolve(),
      operation = prior.then(action),
      tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
  private async _json(path: string): Promise<Record<string, unknown> | null> {
    try {
      return JSON.parse(await readFile(path, 'utf8')) as Record<string, unknown>
    } catch (error) {
      if ((error as { code?: string })?.code === 'ENOENT') return null
      throw Object.assign(new Error('CORRUPT_DELIVERY_STORAGE'), { code: 'CORRUPT_DELIVERY_STORAGE' })
    }
  }
  async journal(namespaceId: string, deliveryId: string): Promise<DeliveryJournalRecord[]> {
    try {
      return (await readFile(this.paths(namespaceId, deliveryId).journal, 'utf8'))
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line) as DeliveryJournalRecord)
    } catch (error) {
      if ((error as { code?: string })?.code === 'ENOENT') return []
      throw Object.assign(new Error('CORRUPT_DELIVERY_JOURNAL'), { code: 'CORRUPT_DELIVERY_JOURNAL' })
    }
  }
  private async _recover(paths: DeliveryStorePaths): Promise<void> {
    const pending = await this._json(paths.pending)
    if (!pending) return
    const pendingSnapshot = pending.snapshot as DeliverySnapshot,
      pendingOperation = pending.operation as DeliveryJournalRecord
    const records = await this.journal(pendingSnapshot.namespaceId, pendingSnapshot.deliveryId)
    const matching = records.filter((item) => item.operationId === pendingOperation.operationId)
    if (!matching.length)
      throw Object.assign(new Error('DELIVERY_OPERATION_INDETERMINATE'), { code: 'DELIVERY_OPERATION_INDETERMINATE' })
    const last = matching[matching.length - 1] as DeliveryJournalRecord
    if (last.state === 'succeeded' && pending.snapshotHash === hash(pendingSnapshot)) {
      await atomic(paths.snapshot, pendingSnapshot)
      await rm(paths.pending, { force: true })
      return
    }
    throw Object.assign(new Error('DELIVERY_OPERATION_INDETERMINATE'), { code: 'DELIVERY_OPERATION_INDETERMINATE' })
  }
  async read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null> {
    const paths = this.paths(namespaceId, deliveryId)
    await this._recover(paths)
    const snapshot = (await this._json(paths.snapshot)) as DeliverySnapshot | null
    if (!snapshot) return null
    if (!validSnapshot(snapshot) || snapshot.snapshotHash !== hash({ ...snapshot, snapshotHash: undefined }))
      throw Object.assign(new Error('CORRUPT_DELIVERY_STORAGE'), { code: 'CORRUPT_DELIVERY_STORAGE' })
    return snapshot
  }
  async create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult> {
    return this._locked(input.namespaceId as string, input.deliveryId as string, async () => {
      const current = await this.read(input.namespaceId as string, input.deliveryId as string)
      if (current)
        return JSON.stringify({ ...current, snapshotHash: undefined }) === JSON.stringify(input)
          ? { ok: true, changed: false, snapshot: current }
          : { ok: false, error: { code: 'DELIVERY_IDENTITY_CONFLICT' } }
      if (!validSnapshot(input)) return { ok: false, error: { code: 'INVALID_DELIVERY_SNAPSHOT' } }
      return this._write(null, input, { kind: 'delivery_created', idempotencyKey: `create:${input.deliveryId}` })
    })
  }
  async promote({
    namespaceId,
    request,
    definition,
    evidence,
    execution,
  }: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, request.deliveryId, async () => {
      const current = await this.read(namespaceId, request.deliveryId)
      const records = await this.journal(namespaceId, request.deliveryId)
      const scopeHash = deliveryScopeHash(namespaceId, request, execution),
        semanticHash = deliverySemanticHash(request),
        prior = records.find((item) => item.scopeHash === scopeHash && item.state === 'succeeded')
      if (prior) {
        if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
        return { ok: true, changed: false, idempotent: true, snapshot: current }
      }
      const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution })
      if (!decision.allowed) return { ok: false, error: decision }
      const next = applyDeliveryPromotion(current as DeliverySnapshot, request)
      return this._write(current, next as unknown as Record<string, unknown>, {
        kind: 'delivery_promoted',
        idempotencyKey: request.idempotencyKey,
        scopeHash,
        semanticHash,
        evidenceIds: request.evidenceIds,
      })
    })
  }
  private async _write(
    current: DeliverySnapshot | null,
    value: Record<string, unknown>,
    operationInput: {
      kind: string
      idempotencyKey: string
      scopeHash?: string
      semanticHash?: string
      evidenceIds?: string[]
    }
  ): Promise<DeliveryStoreWriteResult> {
    const paths = this.paths(value.namespaceId as string, value.deliveryId as string),
      operationId = createHash('sha256')
        .update(`${value.namespaceId}:${value.deliveryId}:${operationInput.idempotencyKey}`)
        .digest('hex'),
      operation: Record<string, unknown> = {
        schemaVersion: '1',
        operationId,
        deliveryId: value.deliveryId,
        revision: (current?.revision ?? 0) + (current ? 1 : 0),
        kind: operationInput.kind,
        state: 'pending',
        timestamp: new Date().toISOString(),
        ...(operationInput.scopeHash
          ? { scopeHash: operationInput.scopeHash, semanticHash: operationInput.semanticHash }
          : {}),
        ...(operationInput.evidenceIds ? { evidenceIds: [...operationInput.evidenceIds].sort() } : {}),
      }
    const clean = { ...value }
    delete clean.snapshotHash
    const snapshot = { ...clean, snapshotHash: hash(clean) } as DeliverySnapshot
    await atomic(paths.pending, { operation, snapshot, snapshotHash: hash(snapshot) })
    await append(paths.journal, operation)
    await this.fault('after-pending-journal')
    const running = { ...operation, state: 'running', timestamp: new Date().toISOString() }
    await append(paths.journal, running)
    await this.fault('after-running')
    const succeeded = {
      ...operation,
      state: 'succeeded',
      timestamp: new Date().toISOString(),
      resultHash: snapshot.snapshotHash,
    }
    await append(paths.journal, succeeded)
    await this.fault('after-success')
    await atomic(paths.snapshot, snapshot)
    await this.fault('after-snapshot')
    await rm(paths.pending, { force: true })
    return { ok: true, changed: true, idempotent: false, snapshot }
  }
  async recordOperation(
    namespaceId: string,
    deliveryId: string,
    input: { kind: string; state: string; idempotencyKey: string; facts: unknown }
  ): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      const records = await this.journal(namespaceId, deliveryId),
        operationId = createHash('sha256').update(`${namespaceId}:${deliveryId}:${input.idempotencyKey}`).digest('hex'),
        prior = records.filter((item) => item.operationId === operationId).at(-1)
      const semanticHash = hash(input.facts)
      if (prior) {
        if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
        return { ok: true, changed: false, operation: prior }
      }
      const operation = {
        schemaVersion: '1',
        operationId,
        deliveryId,
        kind: input.kind,
        state: input.state,
        semanticHash,
        facts: input.facts,
        timestamp: new Date().toISOString(),
      }
      await append(this.paths(namespaceId, deliveryId).journal, operation)
      return { ok: true, changed: true, operation }
    })
  }

  private _deliveryOperationProjection(records: DeliveryJournalRecord[]): DeliveryOperationProjection {
    const history = records.filter((record) => record.recordType === 'delivery-operation')
    const current = new Map<string, DeliveryJournalRecord>()
    const resolved = new Set(
      history
        .filter((record) => record.resolvedOperationId && ['succeeded', 'failed'].includes(record.state as string))
        .map((record) => record.resolvedOperationId as string)
    )
    for (const record of history) current.set(record.operationId as string, record)
    const rollbackHistory = records.filter((record) => record.recordType === 'rollback-request')
    const rollbackCurrent = new Map<string, DeliveryJournalRecord>()
    for (const record of rollbackHistory) rollbackCurrent.set(record.rollbackRequestId as string, record)
    return {
      history,
      operations: [...current.values()],
      rollbackRequests: [...rollbackCurrent.values()],
      rollbackRequestHistory: rollbackHistory,
      unresolvedIndeterminate: [...current.values()].filter(
        (record) => record.state === 'indeterminate' && !resolved.has(record.operationId as string)
      ),
    }
  }
  async readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<
    | (DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] })
    | null
  > {
    const snapshot = await this.read(namespaceId, deliveryId)
    if (!snapshot) return null
    const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId))
    return { ...snapshot, deliveryOperations: projection.operations, rollbackRequests: projection.rollbackRequests }
  }
  async inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection> {
    return this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId))
  }
  async createRollbackRequest({
    namespaceId,
    deliveryId,
    workflowId,
    caseId,
    runtimeId,
    request,
    execution,
  }: DeliveryStoreRollbackRequestInput): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId)
      if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      if (snapshot.workflowId !== workflowId || snapshot.parentCaseId !== caseId || snapshot.runtimeId !== runtimeId)
        return { ok: false, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)),
        prior = projection.rollbackRequestHistory.find((record) => record.scopeHash === request.scopeHash)
      if (prior)
        return prior.semanticHash === request.semanticHash
          ? {
              ok: true,
              changed: false,
              idempotent: true,
              request:
                projection.rollbackRequests.find((item) => item.rollbackRequestId === prior.rollbackRequestId) ?? prior,
            }
          : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== request.expectedRevision) return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      const record = {
        recordType: 'rollback-request',
        schemaVersion: '1',
        rollbackRequestId: request.rollbackRequestId,
        deliveryId,
        workflowId,
        namespaceId,
        caseId,
        runtimeId,
        status: 'requested',
        expectedRevision: request.expectedRevision,
        idempotencyKey: request.idempotencyKey,
        scopeHash: request.scopeHash,
        semanticHash: request.semanticHash,
        targetId: request.targetId,
        targetHash: request.targetHash,
        deploymentRef: canonical(request.deploymentRef),
        priorArtifactRef: canonical(request.priorArtifactRef),
        priorReleaseRef: canonical(request.priorReleaseRef),
        reasonCode: request.reasonCode,
        ...(request.reason ? { reason: request.reason } : {}),
        requestedAt: new Date().toISOString(),
        requestedBy: canonical(execution),
      }
      await append(this.paths(namespaceId, deliveryId).journal, record)
      return { ok: true, changed: true, idempotent: false, request: record }
    })
  }
  async approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId)
      if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)),
        current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId)
      if (!current) return { ok: false, error: { code: 'ROLLBACK_REQUEST_NOT_FOUND' } }
      const scopeHash = `sha256:${hash({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`,
        semanticHash = `sha256:${hash({ rollbackRequestId, expectedRevision: approval.expectedRevision, actorId: approval.execution.actorId })}`,
        prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash)
      if (prior)
        return prior.approvalSemanticHash === semanticHash
          ? { ok: true, changed: false, idempotent: true, request: prior }
          : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision)
        return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      if (current.status !== 'requested') return { ok: false, error: { code: 'ROLLBACK_REQUEST_ALREADY_DECIDED' } }
      const record = {
        ...current,
        status: 'approved',
        approvedAt: new Date().toISOString(),
        approvedBy: canonical(approval.execution),
        approvalScopeHash: scopeHash,
        approvalSemanticHash: semanticHash,
        approvalIdempotencyKey: approval.idempotencyKey,
      }
      await append(this.paths(namespaceId, deliveryId).journal, record)
      return { ok: true, changed: true, idempotent: false, request: record }
    })
  }
  async createDeliveryOperation({
    namespaceId,
    workflowId,
    deliveryId,
    caseId,
    runtimeId,
    request,
    targetRef,
    execution,
  }: DeliveryStoreOperationInput): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      const normalized = normalizeDeliveryOperationRequest(request)
      if (!normalized.ok) return normalized
      const snapshot = await this.read(namespaceId, deliveryId)
      if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      const targetHash = targetRef?.targetHash
      const identity = deriveDeliveryOperationIdentity(
        { namespaceId, workflowId, deliveryId, caseId, runtimeId },
        normalized.value,
        targetHash
      )
      if (!identity.ok) return identity
      const records = await this.journal(namespaceId, deliveryId),
        projection = this._deliveryOperationProjection(records),
        existing = projection.history.find((record) => record.scopeHash === identity.value.scopeHash)
      if (existing)
        return existing.semanticHash === identity.value.semanticHash
          ? {
              ok: true,
              changed: false,
              idempotent: true,
              operation:
                projection.operations.find((record) => record.operationId === existing.operationId) ?? existing,
            }
          : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== normalized.value.expectedRevision)
        return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      if (projection.unresolvedIndeterminate.length)
        return { ok: false, error: { code: 'DELIVERY_OPERATION_INDETERMINATE' } }
      const now = new Date().toISOString(),
        source = normalized.value.artifactRef ?? normalized.value.priorArtifactRef,
        operation: Record<string, unknown> = {
          recordType: 'delivery-operation',
          operationId: identity.value.operationId,
          kind: normalized.value.kind,
          expectedRevision: normalized.value.expectedRevision,
          targetRef: canonical(targetRef),
          artifactRef: normalized.value.artifactRef ?? normalized.value.priorArtifactRef,
          releaseRef: normalized.value.releaseRef ?? normalized.value.priorReleaseRef,
          deploymentRef: normalized.value.deploymentRef,
          rollbackRef: normalized.value.rollbackRef,
          state: 'pending',
          attempt: 0,
          requestedAt: now,
          startedAt: undefined,
          completedAt: undefined,
          execution: canonical(execution),
          adapterCorrelation: undefined,
          scopeHash: identity.value.scopeHash,
          semanticHash: identity.value.semanticHash,
          result: undefined,
          error: undefined,
          resolvedOperationId: undefined,
          sourceCommit: source?.sourceCommit,
          artifactDigest: source?.digest,
          rollbackRequestId: normalized.value.rollbackRequestId,
          approvedEvidenceId: normalized.value.approvedEvidenceId,
        }
      const persisted = Object.fromEntries(Object.entries(operation).filter(([, value]) => value !== undefined))
      const contract = validateDeliveryOperationRecord(persisted)
      if (!contract.ok) return { ok: false, error: contract.error }
      await append(this.paths(namespaceId, deliveryId).journal, persisted)
      await this.fault('after-delivery-operation-write')
      return { ok: true, changed: true, idempotent: false, operation: persisted }
    })
  }
  async recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options: { inspectedObservation?: DeliveryOperationObservation } = {}
  ): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      await this.read(namespaceId, deliveryId)
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)),
        previous = projection.operations.find((record) => record.operationId === operationId)
      if (!previous) return { ok: false, error: { code: 'DELIVERY_OPERATION_NOT_FOUND' } }
      const now = new Date().toISOString(),
        state = transition.state,
        next = {
          ...previous,
          state,
          attempt: state === 'running' ? (previous.attempt as number) + 1 : previous.attempt,
          startedAt: state === 'running' ? now : previous.startedAt,
          completedAt: ['succeeded', 'failed'].includes(state) ? now : undefined,
          adapterCorrelation: transition.adapterCorrelation ?? previous.adapterCorrelation,
          result: transition.result,
          error: transition.error,
          resolvedOperationId: transition.resolvedOperationId,
        }
      const clean = Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)),
        valid = validateDeliveryOperationTransition(previous, clean, options)
      if (!valid.ok) return valid
      const contract = validateDeliveryOperationRecord(clean)
      if (!contract.ok) return contract
      await append(this.paths(namespaceId, deliveryId).journal, clean)
      await this.fault(`after-delivery-operation-${state}`)
      return { ok: true, changed: true, operation: clean }
    })
  }
  async startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult> {
    return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, { state: 'running', adapterCorrelation })
  }
  async reconcileDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    observation: DeliveryOperationObservation
  ): Promise<DeliveryStoreWriteResult> {
    return this.recordDeliveryOperation(
      namespaceId,
      deliveryId,
      operationId,
      {
        state: observation.state as string,
        result: observation.result,
        error: observation.error,
        adapterCorrelation: observation.adapterCorrelation,
        resolvedOperationId: operationId,
      },
      { inspectedObservation: { ...observation, operationId } }
    )
  }

  /** Only an indeterminate logical operation without a later terminal reconciliation blocks. */
  async hasIndeterminateOperation(namespaceId: string, deliveryId: string): Promise<boolean> {
    try {
      return (await this.inspectDeliveryOperations(namespaceId, deliveryId)).unresolvedIndeterminate.length > 0
    } catch {
      return true
    }
  }

  /**
   * Atomically patch specific fields in the delivery snapshot and append a journal record.
   * Supports dot-notation keys like 'git.checkpoint' to set nested properties.
   * Used after checkpoint/push/PR to persist the new state without a full promote cycle.
   */
  async updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult> {
    return this._locked(namespaceId, deliveryId, async () => {
      const current = await this.read(namespaceId, deliveryId)
      if (!current) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      // Apply patch: support dot-notation for nested git.* fields.
      const updated: Record<string, unknown> = { ...current }
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.')
        if (parts.length === 1) {
          updated[key] = value
        } else if (parts.length === 2) {
          const head = parts[0] as string,
            tail = parts[1] as string
          updated[head] = { ...((updated[head] ?? {}) as Record<string, unknown>), [tail]: value }
        } else {
          updated[key] = value
        } // fallback for unexpected depth
      }
      updated.updatedAt = patch.updatedAt ?? new Date().toISOString()
      return this._write(current, updated, operationInput)
    })
  }
}

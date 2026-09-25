import { createHash } from 'node:crypto'

import {
  deriveDeliveryOperationIdentity,
  normalizeDeliveryOperationRequest,
  validateDeliveryOperationRecord,
  validateDeliveryOperationTransition,
  type DeliveryOperationObservation,
} from '../../../domain/delivery/delivery-operation-definition.js'
import {
  applyDeliveryPromotion,
  deliveryScopeHash,
  deliverySemanticHash,
  evaluateDeliveryPromotion,
} from '../../../domain/delivery/delivery-policy.js'
import type { DeliveryRepository } from '../../../ports/persistence/delivery-repository.js'
import type {
  DeliveryJournalRecord,
  DeliveryOperationProjection,
  DeliveryOperationTransitionInput,
  DeliverySnapshot,
  DeliveryStoreOperationInput,
  DeliveryStorePromoteInput,
  DeliveryStoreRollbackApprovalInput,
  DeliveryStoreRollbackRequestInput,
  DeliveryStoreWriteResult,
} from '../delivery-store.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL delivery repository adapter.
 *
 * The durable surface is a `deliveries` snapshot row (optimistic-locking
 * `revision` + the verbatim JSONB payload) plus the append-only
 * `delivery_journal`: every promotion, delivery-operation transition and
 * rollback-request decision is one immutable row, projected back into the live
 * operations and rollback requests exactly like the filesystem store.
 *
 * Delivery stays at ledger level (the convergence forge-ledger is out of scope
 * for B3). Idempotency hashing, promotion policy, operation normalization /
 * identity derivation and the transition/record contracts are the shared pure
 * domain helpers, so the SQL and filesystem adapters apply the exact same
 * rules; multi-write mutations run inside one transaction.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time.
 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const HASH = /^sha256:[0-9a-f]{64}$/

/**
 * Canonical JSON shape: object keys sorted recursively, `undefined` values
 * dropped, arrays preserved in order — identical to the filesystem store.
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

const validSnapshot = (value: unknown): boolean =>
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

/**
 * Error raised by the SQL delivery adapter. Code-compatible with the filesystem
 * `DeliveryStore` / adapter so callers keep their error semantics across the two
 * backends.
 */
export class SqlDeliveryRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'SqlDeliveryRepositoryError'
    this.code = code
    this.details = details
  }
}

export interface SqlDeliveryRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface DeliveryRow {
  revision: number
  payload: unknown
}

interface JournalRow {
  record_sequence: number
  payload: unknown
}

const OPERATION_TERMINAL_STATES = ['succeeded', 'failed']

export class SqlDeliveryRepository implements DeliveryRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string
  readonly #locks: Map<string, Promise<unknown>>

  constructor(client: SqlClient, options: SqlDeliveryRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
    this.#locks = new Map()
  }

  #assertScope(namespaceId: unknown, deliveryId: unknown): asserts namespaceId is string {
    if (!UUID.test((namespaceId ?? '') as string) || !SAFE.test((deliveryId ?? '') as string))
      throw new SqlDeliveryRepositoryError('INVALID_DELIVERY_SCOPE')
  }

  #locked<Result>(namespaceId: string, deliveryId: string, action: () => Promise<Result>): Promise<Result> {
    const key = `${namespaceId}\0${deliveryId}`
    const prior = this.#locks.get(key) ?? Promise.resolve()
    const operation = prior.then(action)
    const tail = operation.catch(() => {})
    this.#locks.set(key, tail)
    return operation.finally(() => {
      if (this.#locks.get(key) === tail) this.#locks.delete(key)
    })
  }

  async #read(client: SqlClient, namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null> {
    this.#assertScope(namespaceId, deliveryId)
    const { rows } = await client.query<DeliveryRow>(
      `SELECT * FROM deliveries
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    )
    const row = rows[0]
    if (!row) return null
    const snapshot = parseJsonColumn<DeliverySnapshot>(row.payload)
    if (!validSnapshot(snapshot) || snapshot.snapshotHash !== hash({ ...snapshot, snapshotHash: undefined }))
      throw new SqlDeliveryRepositoryError('CORRUPT_DELIVERY_STORAGE')
    return snapshot
  }

  async #journal(client: SqlClient, namespaceId: string, deliveryId: string): Promise<DeliveryJournalRecord[]> {
    this.#assertScope(namespaceId, deliveryId)
    const { rows } = await client.query<JournalRow>(
      `SELECT * FROM delivery_journal
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    )
    return rows
      .sort((left, right) => (left.record_sequence ?? 0) - (right.record_sequence ?? 0))
      .map((row) => parseJsonColumn<DeliveryJournalRecord>(row.payload))
  }

  async #append(
    client: SqlClient,
    namespaceId: string,
    deliveryId: string,
    records: readonly DeliveryJournalRecord[]
  ): Promise<void> {
    const { rows } = await client.query<{ record_sequence: number }>(
      `SELECT record_sequence FROM delivery_journal
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    )
    let sequence = rows.reduce((maximum, row) => Math.max(maximum, Number(row.record_sequence) || 0), 0) + 1
    for (const record of records) {
      await client.query(
        `INSERT INTO delivery_journal
           (organization_id, workstream_id, namespace_id, delivery_id, record_sequence, record_id, record_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId,
          sequence,
          `${deliveryId}:${sequence}`,
          (record.recordType as string | undefined) ?? null,
          JSON.stringify(record),
          new Date().toISOString(),
        ]
      )
      sequence++
    }
  }

  #projection(records: readonly DeliveryJournalRecord[]): DeliveryOperationProjection {
    const history = records.filter((record) => record.recordType === 'delivery-operation')
    const current = new Map<string, DeliveryJournalRecord>()
    const resolved = new Set(
      history
        .filter((record) => record.resolvedOperationId && OPERATION_TERMINAL_STATES.includes(record.state as string))
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

  async #write(
    client: SqlClient,
    current: DeliverySnapshot | null,
    value: Record<string, unknown>,
    operationInput: {
      kind: string
      idempotencyKey: string
      scopeHash?: string
      semanticHash?: string
      evidenceIds?: readonly string[]
    }
  ): Promise<DeliveryStoreWriteResult> {
    const namespaceId = value.namespaceId as string
    const deliveryId = value.deliveryId as string
    const operationId = createHash('sha256')
      .update(`${namespaceId}:${deliveryId}:${operationInput.idempotencyKey}`)
      .digest('hex')
    const operation: Record<string, unknown> = {
      schemaVersion: '1',
      operationId,
      deliveryId,
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
    const running: Record<string, unknown> = { ...operation, state: 'running', timestamp: new Date().toISOString() }
    const succeeded: Record<string, unknown> = {
      ...operation,
      state: 'succeeded',
      timestamp: new Date().toISOString(),
      resultHash: snapshot.snapshotHash,
    }
    await this.#append(client, namespaceId, deliveryId, [
      operation as DeliveryJournalRecord,
      running as DeliveryJournalRecord,
      succeeded as DeliveryJournalRecord,
    ])
    const observedAt = new Date().toISOString()
    const payload = JSON.stringify(snapshot)
    const updatedAt = typeof snapshot.updatedAt === 'string' ? snapshot.updatedAt : observedAt
    if (current) {
      const { rowCount } = await client.query(
        `UPDATE deliveries
           SET revision = $1, stage = $2, payload = $3::jsonb, updated_at = $4
         WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND delivery_id = $8`,
        [
          snapshot.revision,
          snapshot.stage as string,
          payload,
          updatedAt,
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId,
        ]
      )
      if (!rowCount) return { ok: false, error: { code: 'REVISION_CONFLICT' } }
    } else {
      const createdAt = typeof snapshot.createdAt === 'string' ? snapshot.createdAt : observedAt
      await client.query(
        `INSERT INTO deliveries
           (organization_id, workstream_id, namespace_id, delivery_id, revision, stage, payload, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId,
          snapshot.revision,
          snapshot.stage as string,
          payload,
          createdAt,
          updatedAt,
        ]
      )
    }
    return { ok: true, changed: true, idempotent: false, snapshot }
  }

  async read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null> {
    return this.#read(this.#client, namespaceId, deliveryId)
  }

  async create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult> {
    const namespaceId = input.namespaceId as string
    const deliveryId = input.deliveryId as string
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, deliveryId)
        if (current)
          return hash({ ...current, snapshotHash: undefined }) === hash(input)
            ? { ok: true as const, changed: false, snapshot: current }
            : { ok: false as const, error: { code: 'DELIVERY_IDENTITY_CONFLICT' } }
        if (!validSnapshot(input)) return { ok: false as const, error: { code: 'INVALID_DELIVERY_SNAPSHOT' } }
        return this.#write(tx, null, input, {
          kind: 'delivery_created',
          idempotencyKey: `create:${deliveryId}`,
        })
      })
    )
  }

  async promote({
    namespaceId,
    request,
    definition,
    evidence,
    execution,
  }: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult> {
    return this.#locked(namespaceId, request.deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, request.deliveryId)
        const records = await this.#journal(tx, namespaceId, request.deliveryId)
        const scopeHash = deliveryScopeHash(namespaceId, request, execution)
        const semanticHash = deliverySemanticHash(request)
        const prior = records.find((item) => item.scopeHash === scopeHash && item.state === 'succeeded')
        if (prior) {
          if (prior.semanticHash !== semanticHash)
            return { ok: false as const, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
          return { ok: true as const, changed: false, idempotent: true, snapshot: current }
        }
        const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution })
        if (!decision.allowed) return { ok: false as const, error: decision }
        const next = applyDeliveryPromotion(current as DeliverySnapshot, request)
        return this.#write(tx, current, next as unknown as Record<string, unknown>, {
          kind: 'delivery_promoted',
          idempotencyKey: request.idempotencyKey,
          scopeHash,
          semanticHash,
          evidenceIds: request.evidenceIds,
        })
      })
    )
  }

  async readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<
    | (DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] })
    | null
  > {
    const snapshot = await this.#read(this.#client, namespaceId, deliveryId)
    if (!snapshot) return null
    const projection = this.#projection(await this.#journal(this.#client, namespaceId, deliveryId))
    return { ...snapshot, deliveryOperations: projection.operations, rollbackRequests: projection.rollbackRequests }
  }

  async inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection> {
    return this.#projection(await this.#journal(this.#client, namespaceId, deliveryId))
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
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const snapshot = await this.#read(tx, namespaceId, deliveryId)
        if (!snapshot) return { ok: false as const, error: { code: 'DELIVERY_NOT_FOUND' } }
        if (snapshot.workflowId !== workflowId || snapshot.parentCaseId !== caseId || snapshot.runtimeId !== runtimeId)
          return { ok: false as const, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId))
        const prior = projection.rollbackRequestHistory.find((record) => record.scopeHash === request.scopeHash)
        if (prior)
          return prior.semanticHash === request.semanticHash
            ? {
                ok: true as const,
                changed: false,
                idempotent: true,
                request:
                  projection.rollbackRequests.find((item) => item.rollbackRequestId === prior.rollbackRequestId) ??
                  prior,
              }
            : { ok: false as const, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
        if (snapshot.revision !== request.expectedRevision)
          return { ok: false as const, error: { code: 'REVISION_CONFLICT' } }
        const record: DeliveryJournalRecord = {
          recordType: 'rollback-request',
          schemaVersion: '1',
          rollbackRequestId: request.rollbackRequestId as string,
          deliveryId,
          workflowId,
          namespaceId,
          caseId,
          runtimeId,
          status: 'requested',
          expectedRevision: request.expectedRevision,
          idempotencyKey: request.idempotencyKey,
          scopeHash: request.scopeHash as string,
          semanticHash: request.semanticHash as string,
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
        await this.#append(tx, namespaceId, deliveryId, [record])
        return { ok: true as const, changed: true, idempotent: false, request: record }
      })
    )
  }

  async approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult> {
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const snapshot = await this.#read(tx, namespaceId, deliveryId)
        if (!snapshot) return { ok: false as const, error: { code: 'DELIVERY_NOT_FOUND' } }
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId))
        const current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId)
        if (!current) return { ok: false as const, error: { code: 'ROLLBACK_REQUEST_NOT_FOUND' } }
        const scopeHash = `sha256:${hash({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`
        const semanticHash = `sha256:${hash({
          rollbackRequestId,
          expectedRevision: approval.expectedRevision,
          actorId: approval.execution.actorId,
        })}`
        const prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash)
        if (prior)
          return prior.approvalSemanticHash === semanticHash
            ? { ok: true as const, changed: false, idempotent: true, request: prior }
            : { ok: false as const, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
        if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision)
          return { ok: false as const, error: { code: 'REVISION_CONFLICT' } }
        if (current.status !== 'requested')
          return { ok: false as const, error: { code: 'ROLLBACK_REQUEST_ALREADY_DECIDED' } }
        const record: DeliveryJournalRecord = {
          ...current,
          status: 'approved',
          approvedAt: new Date().toISOString(),
          approvedBy: canonical(approval.execution),
          approvalScopeHash: scopeHash,
          approvalSemanticHash: semanticHash,
          approvalIdempotencyKey: approval.idempotencyKey,
        }
        await this.#append(tx, namespaceId, deliveryId, [record])
        return { ok: true as const, changed: true, idempotent: false, request: record }
      })
    )
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
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const normalized = normalizeDeliveryOperationRequest(request)
        if (!normalized.ok) return normalized
        const snapshot = await this.#read(tx, namespaceId, deliveryId)
        if (!snapshot) return { ok: false as const, error: { code: 'DELIVERY_NOT_FOUND' } }
        const targetHash = targetRef?.targetHash
        const identity = deriveDeliveryOperationIdentity(
          { namespaceId, workflowId, deliveryId, caseId, runtimeId },
          normalized.value,
          targetHash
        )
        if (!identity.ok) return identity
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId))
        const existing = projection.history.find((record) => record.scopeHash === identity.value.scopeHash)
        if (existing)
          return existing.semanticHash === identity.value.semanticHash
            ? {
                ok: true as const,
                changed: false,
                idempotent: true,
                operation:
                  projection.operations.find((record) => record.operationId === existing.operationId) ?? existing,
              }
            : { ok: false as const, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
        if (snapshot.revision !== normalized.value.expectedRevision)
          return { ok: false as const, error: { code: 'REVISION_CONFLICT' } }
        if (projection.unresolvedIndeterminate.length)
          return { ok: false as const, error: { code: 'DELIVERY_OPERATION_INDETERMINATE' } }
        const now = new Date().toISOString()
        const source = normalized.value.artifactRef ?? normalized.value.priorArtifactRef
        const operation: Record<string, unknown> = {
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
        const persisted = Object.fromEntries(
          Object.entries(operation).filter(([, value]) => value !== undefined)
        ) as DeliveryJournalRecord
        const contract = validateDeliveryOperationRecord(persisted)
        if (!contract.ok) return { ok: false as const, error: contract.error }
        await this.#append(tx, namespaceId, deliveryId, [persisted])
        return { ok: true as const, changed: true, idempotent: false, operation: persisted }
      })
    )
  }

  async recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options: { inspectedObservation?: DeliveryOperationObservation } = {}
  ): Promise<DeliveryStoreWriteResult> {
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        await this.#read(tx, namespaceId, deliveryId)
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId))
        const previous = projection.operations.find((record) => record.operationId === operationId)
        if (!previous) return { ok: false as const, error: { code: 'DELIVERY_OPERATION_NOT_FOUND' } }
        const now = new Date().toISOString()
        const state = transition.state
        const next: Record<string, unknown> = {
          ...previous,
          state,
          attempt: state === 'running' ? (previous.attempt as number) + 1 : previous.attempt,
          startedAt: state === 'running' ? now : previous.startedAt,
          completedAt: OPERATION_TERMINAL_STATES.includes(state) ? now : undefined,
          adapterCorrelation: transition.adapterCorrelation ?? previous.adapterCorrelation,
          result: transition.result,
          error: transition.error,
          resolvedOperationId: transition.resolvedOperationId,
        }
        const clean = Object.fromEntries(
          Object.entries(next).filter(([, value]) => value !== undefined)
        ) as DeliveryJournalRecord
        const valid = validateDeliveryOperationTransition(previous, clean, options)
        if (!valid.ok) return valid
        const contract = validateDeliveryOperationRecord(clean)
        if (!contract.ok) return contract
        await this.#append(tx, namespaceId, deliveryId, [clean])
        return { ok: true as const, changed: true, operation: clean }
      })
    )
  }

  async startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult> {
    return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, {
      state: 'running',
      adapterCorrelation,
    })
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
   * Atomically patches specific fields in the delivery snapshot and appends a
   * journal record. Supports dot-notation keys like `git.checkpoint` to set
   * nested properties.
   */
  async updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult> {
    return this.#locked(namespaceId, deliveryId, () =>
      withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, deliveryId)
        if (!current) return { ok: false as const, error: { code: 'DELIVERY_NOT_FOUND' } }
        const updated: Record<string, unknown> = { ...current }
        for (const [key, value] of Object.entries(patch)) {
          const parts = key.split('.')
          if (parts.length === 1) {
            updated[key] = value
          } else if (parts.length === 2) {
            const head = parts[0] as string
            const tail = parts[1] as string
            updated[head] = { ...((updated[head] ?? {}) as Record<string, unknown>), [tail]: value }
          } else {
            updated[key] = value
          }
        }
        updated.updatedAt = patch.updatedAt ?? new Date().toISOString()
        return this.#write(tx, current, updated, operationInput)
      })
    )
  }
}

/** Wires a SQL delivery repository around a database client. */
export function createSqlDeliveryRepository(
  client: SqlClient,
  options: SqlDeliveryRepositoryOptions = {}
): SqlDeliveryRepository {
  return new SqlDeliveryRepository(client, options)
}

import {
  canTransitionWorker,
  isWorkerIsoInstant,
  isWorkerState,
  validateWorker,
  type Worker,
  type WorkerCreateInput,
  type WorkerState,
} from '../../../domain/worker.js'
import type {
  WorkerListFilter,
  WorkerRepository,
  WorkerRepositoryScope,
} from '../../../ports/persistence/worker-repository.js'
import { DEFAULT_ORGANIZATION_ID, parseJsonColumn, type SqlClient } from './db.js'

/**
 * SQL worker repository adapter (V6 `workers` + V7 liveness columns).
 *
 * The durable surface is the V6 table extended by V7 (`last_heartbeat_at`,
 * `protocol_version`, `capabilities`). The lifecycle rules are the shared pure
 * domain ones (`canTransitionWorker` / `validateWorker`), so every adapter
 * applies the exact same state machine; `revision` is the optimistic-locking
 * column compared-and-swapped by the `UPDATE ... WHERE revision = $n` clause.
 *
 * Tenant scoping (organization) is fixed at wiring time: a worker is an
 * organization-scoped node, never a workstream-scoped one. The heartbeat only
 * records liveness; no lease, fencing token or expiry is implemented here
 * (Jalon C lease protocol is out of scope for this adapter).
 */

/** Error raised by the SQL worker adapter. */
export class SqlWorkerRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'SqlWorkerRepositoryError'
    this.code = code
    this.details = details
  }
}

export interface SqlWorkerRepositoryOptions {
  organizationId?: string
}

interface WorkerRow {
  worker_id: string
  worker_type: string
  status: string
  revision: number
  last_heartbeat_at: string | Date | null
  protocol_version: string | null
  capabilities: unknown
  payload: unknown
  created_at: string | Date
  updated_at: string | Date
}

const WORKER_COLUMNS = [
  'worker_id',
  'worker_type',
  'status',
  'revision',
  'last_heartbeat_at',
  'protocol_version',
  'capabilities',
  'payload',
  'created_at',
  'updated_at',
].join(', ')

function toIsoInstant(value: unknown): string {
  return value instanceof Date ? value.toISOString() : String(value)
}

function toIsoInstantOrNull(value: unknown): string | null {
  if (value === null || value === undefined) return null
  return toIsoInstant(value)
}

export class SqlWorkerRepository implements WorkerRepository {
  readonly #client: SqlClient
  readonly #organizationId: string

  constructor(client: SqlClient, options: SqlWorkerRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
  }

  get scope(): WorkerRepositoryScope {
    return { organizationId: this.#organizationId }
  }

  #toWorker(row: WorkerRow): Worker {
    const validated = validateWorker({
      workerId: row.worker_id,
      workerType: row.worker_type,
      status: row.status,
      revision: Number(row.revision),
      lastHeartbeatAt: toIsoInstantOrNull(row.last_heartbeat_at),
      protocolVersion: row.protocol_version === undefined ? null : row.protocol_version,
      capabilities: parseJsonColumn<string[]>(row.capabilities),
      payload: parseJsonColumn<Record<string, unknown>>(row.payload),
      createdAt: toIsoInstant(row.created_at),
      updatedAt: toIsoInstant(row.updated_at),
    })
    if (!validated.ok) throw new SqlWorkerRepositoryError('CORRUPT_STORAGE', { path: validated.error.path })
    return validated.worker
  }

  async #select(workerId: string): Promise<Worker | null> {
    const { rows } = await this.#client.query<WorkerRow>(
      `SELECT ${WORKER_COLUMNS} FROM workers WHERE organization_id = $1 AND worker_id = $2`,
      [this.#organizationId, workerId]
    )
    const row = rows[0]
    return row ? this.#toWorker(row) : null
  }

  /** Compare-and-swap write: the row is only updated when `revision` still matches. */
  async #write(worker: Worker, expectedRevision: number): Promise<void> {
    const { rowCount } = await this.#client.query(
      `UPDATE workers
         SET worker_type = $1, status = $2, revision = $3, last_heartbeat_at = $4,
             protocol_version = $5, capabilities = $6::jsonb, payload = $7::jsonb, updated_at = $8
       WHERE organization_id = $9 AND worker_id = $10 AND revision = $11`,
      [
        worker.workerType,
        worker.status,
        worker.revision,
        worker.lastHeartbeatAt,
        worker.protocolVersion,
        JSON.stringify(worker.capabilities),
        JSON.stringify(worker.payload),
        worker.updatedAt,
        this.#organizationId,
        worker.workerId,
        expectedRevision,
      ]
    )
    if (!rowCount)
      throw new SqlWorkerRepositoryError('REVISION_CONFLICT', { workerId: worker.workerId, expectedRevision })
  }

  async get(workerId: string): Promise<Worker | null> {
    return this.#select(workerId)
  }

  async create(input: WorkerCreateInput): Promise<Worker> {
    const observedAt = new Date().toISOString()
    const validated = validateWorker({
      workerId: input.workerId,
      workerType: input.workerType,
      status: input.status ?? 'offline',
      revision: input.revision ?? 1,
      lastHeartbeatAt: input.lastHeartbeatAt ?? null,
      protocolVersion: input.protocolVersion ?? null,
      capabilities: input.capabilities ?? [],
      payload: input.payload ?? {},
      createdAt: observedAt,
      updatedAt: observedAt,
    })
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path })
    const existing = await this.#select(input.workerId)
    if (existing) throw new SqlWorkerRepositoryError('WORKER_ALREADY_EXISTS', { workerId: input.workerId })
    const worker = validated.worker
    await this.#client.query(
      `INSERT INTO workers
         (organization_id, worker_id, worker_type, status, revision, last_heartbeat_at, protocol_version,
          capabilities, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9::jsonb, $10, $11)`,
      [
        this.#organizationId,
        worker.workerId,
        worker.workerType,
        worker.status,
        worker.revision,
        worker.lastHeartbeatAt,
        worker.protocolVersion,
        JSON.stringify(worker.capabilities),
        JSON.stringify(worker.payload),
        worker.createdAt,
        worker.updatedAt,
      ]
    )
    return worker
  }

  async update(workerId: string, patch: Partial<Worker>, expectedRevision: number): Promise<Worker> {
    const current = await this.#select(workerId)
    if (!current) throw new SqlWorkerRepositoryError('NOT_FOUND', { workerId })
    if (current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError('REVISION_CONFLICT', {
        workerId,
        expectedRevision,
        actualRevision: current.revision,
      })
    if (patch.workerId !== undefined && patch.workerId !== current.workerId)
      throw new SqlWorkerRepositoryError('INVALID_WORKER', { path: 'workerId' })
    const status = patch.status ?? current.status
    if (!isWorkerState(status)) throw new SqlWorkerRepositoryError('INVALID_STATE', { path: 'status' })
    if (status !== current.status && !canTransitionWorker(current.status, status))
      throw new SqlWorkerRepositoryError('INVALID_TRANSITION', { from: current.status, to: status })
    const validated = validateWorker({
      workerId: current.workerId,
      workerType: patch.workerType ?? current.workerType,
      status,
      revision: current.revision + 1,
      lastHeartbeatAt: patch.lastHeartbeatAt === undefined ? current.lastHeartbeatAt : patch.lastHeartbeatAt,
      protocolVersion: patch.protocolVersion === undefined ? current.protocolVersion : patch.protocolVersion,
      capabilities: patch.capabilities ?? current.capabilities,
      payload: patch.payload ?? current.payload,
      createdAt: current.createdAt,
      updatedAt: new Date().toISOString(),
    })
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path })
    await this.#write(validated.worker, current.revision)
    return validated.worker
  }

  async transition(
    workerId: string,
    nextState: WorkerState,
    expectedRevision: number,
    payloadUpdate?: Record<string, unknown>
  ): Promise<Worker> {
    if (!isWorkerState(nextState)) throw new SqlWorkerRepositoryError('INVALID_STATE', { path: 'status' })
    const current = await this.#select(workerId)
    if (!current) throw new SqlWorkerRepositoryError('NOT_FOUND', { workerId })
    if (current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError('REVISION_CONFLICT', {
        workerId,
        expectedRevision,
        actualRevision: current.revision,
      })
    if (!canTransitionWorker(current.status, nextState))
      throw new SqlWorkerRepositoryError('INVALID_TRANSITION', { from: current.status, to: nextState })
    const validated = validateWorker({
      ...current,
      status: nextState,
      revision: current.revision + 1,
      payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
      updatedAt: new Date().toISOString(),
    })
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path })
    await this.#write(validated.worker, current.revision)
    return validated.worker
  }

  async heartbeat(workerId: string, heartbeatAt: string, expectedRevision?: number): Promise<Worker> {
    if (!isWorkerIsoInstant(heartbeatAt))
      throw new SqlWorkerRepositoryError('INVALID_WORKER', { path: 'lastHeartbeatAt' })
    const current = await this.#select(workerId)
    if (!current) throw new SqlWorkerRepositoryError('NOT_FOUND', { workerId })
    if (expectedRevision !== undefined && current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError('REVISION_CONFLICT', {
        workerId,
        expectedRevision,
        actualRevision: current.revision,
      })
    const validated = validateWorker({
      ...current,
      revision: current.revision + 1,
      lastHeartbeatAt: heartbeatAt,
      updatedAt: new Date().toISOString(),
    })
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path })
    await this.#write(validated.worker, current.revision)
    return validated.worker
  }

  async list(filter: WorkerListFilter = {}): Promise<Worker[]> {
    const { rows } = await this.#client.query<WorkerRow>(
      `SELECT ${WORKER_COLUMNS} FROM workers WHERE organization_id = $1`,
      [this.#organizationId]
    )
    const statuses: readonly WorkerState[] | null =
      filter.status === undefined ? null : Array.isArray(filter.status) ? filter.status : [filter.status]
    return rows
      .map((row) => this.#toWorker(row))
      .filter((worker) => statuses === null || statuses.includes(worker.status))
      .filter((worker) => filter.workerType === undefined || worker.workerType === filter.workerType)
      .sort((left, right) => left.workerId.localeCompare(right.workerId))
  }
}

/** Wires a SQL worker repository around a database client. */
export function createSqlWorkerRepository(
  client: SqlClient,
  options: SqlWorkerRepositoryOptions = {}
): SqlWorkerRepository {
  return new SqlWorkerRepository(client, options)
}

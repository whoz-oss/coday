import {
  canTransitionWorkUnit,
  isWorkUnitState,
  validateWorkUnit,
  type WorkUnit,
  type WorkUnitCreateInput,
  type WorkUnitState,
} from '../../../domain/work-unit.js'
import type {
  WorkUnitListFilter,
  WorkUnitRepository,
  WorkUnitRepositoryScope,
} from '../../../ports/persistence/work-unit-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'

/**
 * SQL work-unit repository adapter (V6 `work_units` + V7 scheduling columns).
 *
 * The durable surface is the V6 table extended by V7 (`priority`, `not_before`,
 * `attempt_count`). The lifecycle rules are the shared pure domain ones
 * (`canTransitionWorkUnit` / `validateWorkUnit`), so every adapter applies the
 * exact same state machine; `revision` is the optimistic-locking column
 * compared-and-swapped by the `UPDATE ... WHERE revision = $n` clause.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time: the
 * repository is bound to one tenant and every query carries its composite
 * identity. No lease, fencing token or heartbeat is implemented here (Jalon C).
 */

/** Error raised by the SQL work-unit adapter. */
export class SqlWorkUnitRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'SqlWorkUnitRepositoryError'
    this.code = code
    this.details = details
  }
}

export interface SqlWorkUnitRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface WorkUnitRow {
  work_unit_id: string
  unit_type: string
  status: string
  revision: number
  priority: number
  not_before: string | Date | null
  attempt_count: number
  payload: unknown
  created_at: string | Date
  updated_at: string | Date
}

const WORK_UNIT_COLUMNS = [
  'work_unit_id',
  'unit_type',
  'status',
  'revision',
  'priority',
  'not_before',
  'attempt_count',
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

/** Deterministic eligibility order: descending priority, then earlier `notBefore`, then id. */
function compareNotBefore(left: string | null, right: string | null): number {
  if (left === null && right === null) return 0
  if (left === null) return 1
  if (right === null) return -1
  return left.localeCompare(right)
}

export class SqlWorkUnitRepository implements WorkUnitRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlWorkUnitRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  get scope(): WorkUnitRepositoryScope {
    return { organizationId: this.#organizationId, workstreamId: this.#workstreamId }
  }

  #toWorkUnit(row: WorkUnitRow): WorkUnit {
    const validated = validateWorkUnit({
      workUnitId: row.work_unit_id,
      unitType: row.unit_type,
      status: row.status,
      revision: Number(row.revision),
      priority: Number(row.priority),
      notBefore: toIsoInstantOrNull(row.not_before),
      attemptCount: Number(row.attempt_count),
      payload: parseJsonColumn<Record<string, unknown>>(row.payload),
      createdAt: toIsoInstant(row.created_at),
      updatedAt: toIsoInstant(row.updated_at),
    })
    if (!validated.ok) throw new SqlWorkUnitRepositoryError('CORRUPT_STORAGE', { path: validated.error.path })
    return validated.workUnit
  }

  async #select(workUnitId: string): Promise<WorkUnit | null> {
    const { rows } = await this.#client.query<WorkUnitRow>(
      `SELECT ${WORK_UNIT_COLUMNS} FROM work_units
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`,
      [this.#organizationId, this.#workstreamId, workUnitId]
    )
    const row = rows[0]
    return row ? this.#toWorkUnit(row) : null
  }

  /** Compare-and-swap write: the row is only updated when `revision` still matches. */
  async #write(workUnit: WorkUnit, expectedRevision: number): Promise<void> {
    const { rowCount } = await this.#client.query(
      `UPDATE work_units
         SET unit_type = $1, status = $2, revision = $3, priority = $4, not_before = $5,
             attempt_count = $6, payload = $7::jsonb, updated_at = $8
       WHERE organization_id = $9 AND workstream_id = $10 AND work_unit_id = $11 AND revision = $12`,
      [
        workUnit.unitType,
        workUnit.status,
        workUnit.revision,
        workUnit.priority,
        workUnit.notBefore,
        workUnit.attemptCount,
        JSON.stringify(workUnit.payload),
        workUnit.updatedAt,
        this.#organizationId,
        this.#workstreamId,
        workUnit.workUnitId,
        expectedRevision,
      ]
    )
    if (!rowCount)
      throw new SqlWorkUnitRepositoryError('REVISION_CONFLICT', {
        workUnitId: workUnit.workUnitId,
        expectedRevision,
      })
  }

  async get(workUnitId: string): Promise<WorkUnit | null> {
    return this.#select(workUnitId)
  }

  async create(input: WorkUnitCreateInput): Promise<WorkUnit> {
    const observedAt = new Date().toISOString()
    const validated = validateWorkUnit({
      workUnitId: input.workUnitId,
      unitType: input.unitType,
      status: input.status ?? 'created',
      revision: input.revision ?? 1,
      priority: input.priority ?? 0,
      notBefore: input.notBefore ?? null,
      attemptCount: input.attemptCount ?? 0,
      payload: input.payload ?? {},
      createdAt: observedAt,
      updatedAt: observedAt,
    })
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path })
    const existing = await this.#select(input.workUnitId)
    if (existing) throw new SqlWorkUnitRepositoryError('WORK_UNIT_ALREADY_EXISTS', { workUnitId: input.workUnitId })
    const workUnit = validated.workUnit
    await this.#client.query(
      `INSERT INTO work_units
         (organization_id, workstream_id, work_unit_id, unit_type, status, revision, priority, not_before,
          attempt_count, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12)`,
      [
        this.#organizationId,
        this.#workstreamId,
        workUnit.workUnitId,
        workUnit.unitType,
        workUnit.status,
        workUnit.revision,
        workUnit.priority,
        workUnit.notBefore,
        workUnit.attemptCount,
        JSON.stringify(workUnit.payload),
        workUnit.createdAt,
        workUnit.updatedAt,
      ]
    )
    return workUnit
  }

  async update(workUnitId: string, patch: Partial<WorkUnit>, expectedRevision: number): Promise<WorkUnit> {
    const current = await this.#select(workUnitId)
    if (!current) throw new SqlWorkUnitRepositoryError('NOT_FOUND', { workUnitId })
    if (current.revision !== expectedRevision)
      throw new SqlWorkUnitRepositoryError('REVISION_CONFLICT', {
        workUnitId,
        expectedRevision,
        actualRevision: current.revision,
      })
    if (patch.workUnitId !== undefined && patch.workUnitId !== current.workUnitId)
      throw new SqlWorkUnitRepositoryError('INVALID_WORK_UNIT', { path: 'workUnitId' })
    const status = patch.status ?? current.status
    if (!isWorkUnitState(status)) throw new SqlWorkUnitRepositoryError('INVALID_STATE', { path: 'status' })
    if (status !== current.status && !canTransitionWorkUnit(current.status, status))
      throw new SqlWorkUnitRepositoryError('INVALID_TRANSITION', { from: current.status, to: status })
    const validated = validateWorkUnit({
      workUnitId: current.workUnitId,
      unitType: patch.unitType ?? current.unitType,
      status,
      revision: current.revision + 1,
      priority: patch.priority ?? current.priority,
      notBefore: patch.notBefore === undefined ? current.notBefore : patch.notBefore,
      attemptCount: patch.attemptCount ?? current.attemptCount,
      payload: patch.payload ?? current.payload,
      createdAt: current.createdAt,
      updatedAt: new Date().toISOString(),
    })
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path })
    await this.#write(validated.workUnit, current.revision)
    return validated.workUnit
  }

  async transition(
    workUnitId: string,
    nextState: WorkUnitState,
    expectedRevision: number,
    payloadUpdate?: Record<string, unknown>
  ): Promise<WorkUnit> {
    if (!isWorkUnitState(nextState)) throw new SqlWorkUnitRepositoryError('INVALID_STATE', { path: 'status' })
    const current = await this.#select(workUnitId)
    if (!current) throw new SqlWorkUnitRepositoryError('NOT_FOUND', { workUnitId })
    if (current.revision !== expectedRevision)
      throw new SqlWorkUnitRepositoryError('REVISION_CONFLICT', {
        workUnitId,
        expectedRevision,
        actualRevision: current.revision,
      })
    if (!canTransitionWorkUnit(current.status, nextState))
      throw new SqlWorkUnitRepositoryError('INVALID_TRANSITION', { from: current.status, to: nextState })
    const validated = validateWorkUnit({
      ...current,
      status: nextState,
      revision: current.revision + 1,
      payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
      updatedAt: new Date().toISOString(),
    })
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path })
    await this.#write(validated.workUnit, current.revision)
    return validated.workUnit
  }

  async list(filter: WorkUnitListFilter = {}): Promise<WorkUnit[]> {
    const { rows } = await this.#client.query<WorkUnitRow>(
      `SELECT ${WORK_UNIT_COLUMNS} FROM work_units WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    )
    const statuses: readonly WorkUnitState[] | null =
      filter.status === undefined ? null : Array.isArray(filter.status) ? filter.status : [filter.status]
    const units = rows
      .map((row) => this.#toWorkUnit(row))
      .filter((unit) => statuses === null || statuses.includes(unit.status))
      .filter((unit) => filter.priorityMin === undefined || unit.priority >= filter.priorityMin)
      .sort(
        (left, right) =>
          right.priority - left.priority ||
          compareNotBefore(left.notBefore, right.notBefore) ||
          left.workUnitId.localeCompare(right.workUnitId)
      )
    return filter.limit === undefined ? units : units.slice(0, Math.max(0, filter.limit))
  }
}

/** Wires a SQL work-unit repository around a database client. */
export function createSqlWorkUnitRepository(
  client: SqlClient,
  options: SqlWorkUnitRepositoryOptions = {}
): SqlWorkUnitRepository {
  return new SqlWorkUnitRepository(client, options)
}

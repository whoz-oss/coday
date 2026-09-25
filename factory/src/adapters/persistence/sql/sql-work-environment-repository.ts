import { createHash } from 'node:crypto'

import {
  validateNamespaceId,
  validateWorkUnitEnvironment,
  type ValidationFailure,
  type WorkUnitEnvironment,
  type WorkUnitEnvironmentState,
} from '../../../domain/environment/work-unit-environment.js'
import type { WorkEnvironmentRepository } from '../../../ports/persistence/work-environment-repository.js'
import type { EnvironmentSnapshot, StoreWriteResult, WorkUnitEnvironmentPaths } from '../work-unit-environment-store.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL work-environment repository adapter.
 *
 * The durable surface is the V6 `work_environments` table reserved by Amendment
 * 6: it carries the reserved environment identity, the lifecycle status (mapped
 * onto the table's `provisioning` / `ready` / `busy` / `decommissioned` CHECK
 * vocabulary) and the optimistic-locking `revision`, while the whole
 * `WorkUnitEnvironment` descriptor is kept verbatim in the JSONB `payload`.
 *
 * No scheduler, lease, fencing token or heartbeat is implemented here (Jalon C):
 * the adapter only reserves identifiers and relations, exactly like the
 * filesystem reference. Lifecycle rules, canonical hashing and error codes are
 * the shared domain ones, so the SQL and filesystem adapters are
 * behaviourally interchangeable.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time.
 */

/** Machine codes matching `ENVIRONMENT_STORE_ERROR_CODES` of the filesystem store. */
const ERROR_CODES = Object.freeze({
  INVALID_ENVIRONMENT: 'INVALID_ENVIRONMENT',
  INVALID_NAMESPACE: 'INVALID_NAMESPACE',
  NOT_FOUND: 'NOT_FOUND',
  REVISION_CONFLICT: 'REVISION_CONFLICT',
  INVALID_TRANSITION: 'INVALID_TRANSITION',
  CORRUPT_STORAGE: 'CORRUPT_STORAGE',
} as const)

/** Environment id shape accepted by the filesystem store (`SAFE`). */
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

/** Non-null `env_type` recorded on the V6 reservation skeleton. */
const ENV_TYPE = 'work-unit-environment'

/** Immutable descriptor fields a transition may never change. */
const IMMUTABLE_FIELDS = [
  'schemaVersion',
  'environmentId',
  'workUnitId',
  'workflowId',
  'namespaceId',
  'repoRoot',
  'integrationBranch',
  'branch',
  'worktreePath',
  'baseCommit',
  'createdAt',
  'createdBy',
] as const

/**
 * Error raised by the SQL work-environment adapter. Code-compatible with the
 * filesystem `WorkUnitEnvironmentStoreError` so callers keep their error
 * semantics across the two adapters.
 */
export class SqlWorkEnvironmentRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'SqlWorkEnvironmentRepositoryError'
    this.code = code
    this.details = details
  }
}

export interface SqlWorkEnvironmentRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface EnvironmentRow {
  environment_id: string
  status: string
  revision: number
  payload: unknown
}

/** Maps the domain lifecycle state onto the V6 `work_environments.status` CHECK vocabulary. */
function statusForState(state: WorkUnitEnvironmentState): string {
  switch (state) {
    case 'provisioning':
      return 'provisioning'
    case 'active':
      return 'ready'
    case 'completed':
    case 'abandoned':
    case 'error':
      return 'busy'
    case 'removed':
      return 'decommissioned'
  }
}

/** sha256 of the canonical descriptor JSON, identical to the filesystem store. */
function snapshotHash(environment: WorkUnitEnvironment): string {
  return createHash('sha256')
    .update(JSON.stringify(environment, Object.keys(environment).sort()))
    .digest('hex')
}

export class SqlWorkEnvironmentRepository implements WorkEnvironmentRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string
  readonly #locks: Map<string, Promise<unknown>>

  constructor(client: SqlClient, options: SqlWorkEnvironmentRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
    this.#locks = new Map()
  }

  #assertNamespace(namespaceId: unknown): asserts namespaceId is string {
    if (!validateNamespaceId(namespaceId).ok) throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.INVALID_NAMESPACE)
  }

  #assertEnvironmentId(environmentId: unknown): asserts environmentId is string {
    if (typeof environmentId !== 'string' || !SAFE_ID.test(environmentId))
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.INVALID_ENVIRONMENT)
  }

  /**
   * Virtual artifact paths. The SQL adapter does no filesystem I/O; the paths
   * follow the same digest algebra as the filesystem store (raw environment id
   * never a path segment) so callers can reason about them uniformly.
   */
  paths(namespaceId: string, environmentId: string): WorkUnitEnvironmentPaths {
    this.#assertNamespace(namespaceId)
    this.#assertEnvironmentId(environmentId)
    const digest = createHash('sha256').update(`${namespaceId}:${environmentId}`).digest('hex')
    const directory = `sql://work-environments/${this.#organizationId}/${this.#workstreamId}/${namespaceId}/${digest}`
    return {
      directory,
      snapshot: `${directory}/environment.json`,
      events: `${directory}/events.jsonl`,
      pending: `${directory}/pending.json`,
    }
  }

  #locked<Result>(namespaceId: string, environmentId: string, action: () => Promise<Result>): Promise<Result> {
    const key = `${namespaceId}\0${environmentId}`
    const prior = this.#locks.get(key) ?? Promise.resolve()
    const operation = prior.then(action)
    const tail = operation.catch(() => {})
    this.#locks.set(key, tail)
    return operation.finally(() => {
      if (this.#locks.get(key) === tail) this.#locks.delete(key)
    })
  }

  async #selectRow(client: SqlClient, environmentId: string): Promise<EnvironmentRow | null> {
    const { rows } = await client.query<EnvironmentRow>(
      `SELECT * FROM work_environments
       WHERE organization_id = $1 AND workstream_id = $2 AND environment_id = $3`,
      [this.#organizationId, this.#workstreamId, environmentId]
    )
    return rows[0] ?? null
  }

  #snapshotFromRow(row: EnvironmentRow): EnvironmentSnapshot {
    const environment = validateWorkUnitEnvironment(parseJsonColumn<unknown>(row.payload))
    if (!environment.ok)
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.CORRUPT_STORAGE, { artifact: 'snapshot' })
    if (!Number.isSafeInteger(row.revision) || row.revision < 1)
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.CORRUPT_STORAGE, { artifact: 'revision' })
    return {
      revision: row.revision,
      environmentHash: snapshotHash(environment.environment),
      environment: environment.environment,
    }
  }

  async #read(client: SqlClient, namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null> {
    this.#assertNamespace(namespaceId)
    this.#assertEnvironmentId(environmentId)
    const row = await this.#selectRow(client, environmentId)
    if (!row) return null
    const snapshot = this.#snapshotFromRow(row)
    // The V6 primary key omits the namespace, so a row of another namespace must
    // behave exactly like a missing directory for this scope.
    return snapshot.environment.namespaceId === namespaceId ? snapshot : null
  }

  async #write(
    client: SqlClient,
    current: EnvironmentSnapshot | null,
    environment: WorkUnitEnvironment
  ): Promise<StoreWriteResult> {
    const revision = (current?.revision ?? 0) + 1
    const environmentHash = snapshotHash(environment)
    const snapshot: EnvironmentSnapshot = { revision, environmentHash, environment }
    const status = statusForState(environment.lifecycleState)
    const observedAt = new Date().toISOString()
    const payload = JSON.stringify(environment)
    if (current) {
      const { rowCount } = await client.query(
        `UPDATE work_environments
           SET revision = $1, status = $2, payload = $3::jsonb, updated_at = $4
         WHERE organization_id = $5 AND workstream_id = $6 AND environment_id = $7 AND revision = $8`,
        [
          revision,
          status,
          payload,
          observedAt,
          this.#organizationId,
          this.#workstreamId,
          environment.environmentId,
          current.revision,
        ]
      )
      if (!rowCount) return { ok: false, error: { code: ERROR_CODES.REVISION_CONFLICT } }
    } else {
      await client.query(
        `INSERT INTO work_environments
           (organization_id, workstream_id, environment_id, env_type, status, revision, payload, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          environment.environmentId,
          ENV_TYPE,
          status,
          revision,
          payload,
          observedAt,
          observedAt,
        ]
      )
    }
    return { ok: true, changed: true, snapshot }
  }

  async read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null> {
    return this.#read(this.#client, namespaceId, environmentId)
  }

  async list(
    namespaceId: string,
    filter: { states?: readonly WorkUnitEnvironmentState[] } = {}
  ): Promise<EnvironmentSnapshot[]> {
    this.#assertNamespace(namespaceId)
    this.paths(namespaceId, 'list-probe')
    const { rows } = await this.#client.query<EnvironmentRow>(
      `SELECT * FROM work_environments WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    )
    return rows
      .map((row) => this.#snapshotFromRow(row))
      .filter(
        (snapshot) =>
          snapshot.environment.namespaceId === namespaceId &&
          (!filter.states || filter.states.includes(snapshot.environment.lifecycleState))
      )
      .sort((left, right) => left.environment.environmentId.localeCompare(right.environment.environmentId))
  }

  async reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure> {
    const validated = validateWorkUnitEnvironment(environment)
    if (!validated.ok) return validated
    this.#assertNamespace(validated.environment.namespaceId)
    return this.#locked(validated.environment.namespaceId, validated.environment.environmentId, () =>
      withTransaction(this.#client, async (tx) => {
        const row = await this.#selectRow(tx, validated.environment.environmentId)
        if (row) {
          const current = this.#snapshotFromRow(row)
          if (current.environment.namespaceId !== validated.environment.namespaceId)
            return { ok: false as const, error: { code: ERROR_CODES.INVALID_TRANSITION } }
          return JSON.stringify(current.environment) === JSON.stringify(validated.environment)
            ? { ok: true as const, changed: false, snapshot: current }
            : { ok: false as const, error: { code: ERROR_CODES.INVALID_TRANSITION } }
        }
        return this.#write(tx, null, validated.environment)
      })
    )
  }

  async transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options: { expectedRevision?: number; errorCode?: string } = {}
  ): Promise<StoreWriteResult | ValidationFailure> {
    this.#assertNamespace(namespaceId)
    void options.errorCode
    return this.#locked(namespaceId, environmentId, () =>
      withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, environmentId)
        if (!current) return { ok: false as const, error: { code: ERROR_CODES.NOT_FOUND } }
        if (options.expectedRevision !== undefined && options.expectedRevision !== current.revision)
          return { ok: false as const, error: { code: ERROR_CODES.REVISION_CONFLICT } }
        if (JSON.stringify(current.environment) === JSON.stringify(next))
          return { ok: true as const, changed: false, snapshot: current }
        for (const field of IMMUTABLE_FIELDS)
          if (current.environment[field] !== next[field])
            return { ok: false as const, error: { code: ERROR_CODES.INVALID_TRANSITION } }
        if (current.environment.parentCaseId && next.parentCaseId !== current.environment.parentCaseId)
          return { ok: false as const, error: { code: ERROR_CODES.INVALID_TRANSITION } }
        const from = current.environment.lifecycleState
        const to = next.lifecycleState
        const allowed =
          (from === 'provisioning' && ['provisioning', 'active', 'error'].includes(to)) ||
          (from === 'active' && ['completed', 'abandoned', 'error'].includes(to)) ||
          (['completed', 'abandoned', 'error'].includes(from) && to === 'removed')
        if (!allowed) return { ok: false as const, error: { code: ERROR_CODES.INVALID_TRANSITION } }
        const validated = validateWorkUnitEnvironment(next)
        if (!validated.ok) return validated
        return this.#write(tx, current, validated.environment)
      })
    )
  }
}

/** Wires a SQL work-environment repository around a database client. */
export function createSqlWorkEnvironmentRepository(
  client: SqlClient,
  options: SqlWorkEnvironmentRepositoryOptions = {}
): SqlWorkEnvironmentRepository {
  return new SqlWorkEnvironmentRepository(client, options)
}

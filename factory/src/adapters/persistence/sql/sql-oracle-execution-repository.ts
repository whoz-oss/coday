import { validateOracleDefinition, type OracleDefinition } from '../../../domain/oracle/oracle-definition.js'
import type { OracleExecutionRepository } from '../../../ports/persistence/oracle-execution-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL oracle-execution repository adapter (V6 `oracle_executions` + `artifacts`).
 *
 * The read port exposes the loaded oracle catalogue (`list`) and identity lookup
 * (`get`) by projecting the validated definition stored in the JSONB `payload`
 * of the execution rows. Tenant scoping is fixed at wiring time.
 *
 * Amendment 5 (upload-then-commit) is honoured by {@link terminalize}: the
 * execution transition and the linked artifact availability flip to `available`
 * are applied inside the same `withTransaction` unit of work, so a failure of
 * the artifact commit rolls the execution transition back too.
 */

export interface SqlOracleExecutionRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

/** Terminal statuses admitted by the V6 `oracle_executions.status` CHECK. */
export type OracleExecutionTerminalStatus = 'succeeded' | 'failed' | 'cancelled'

export interface SqlOracleTerminalizationInput {
  namespaceId: string
  workflowId: string
  executionId: string
  status: OracleExecutionTerminalStatus
  /** Artifact to commit; defaults to the execution's recorded `artifact_id`. */
  artifactId?: string
  updatedAt?: string
}

export interface SqlOracleTerminalizationResult {
  executionId: string
  status: OracleExecutionTerminalStatus
  revision: number
  artifactId: string | null
}

interface DefinitionRow {
  created_at: string
  payload: unknown
}

interface ExecutionRow {
  revision: number
  artifact_id: string | null
}

export class SqlOracleExecutionRepository implements OracleExecutionRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlOracleExecutionRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  #toDefinition(payload: unknown): OracleDefinition | null {
    try {
      return validateOracleDefinition(parseJsonColumn(payload))
    } catch {
      return null
    }
  }

  async list(): Promise<OracleDefinition[]> {
    const { rows } = await this.#client.query<DefinitionRow>(
      `SELECT created_at, payload FROM oracle_executions
       WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    )
    const byId = new Map<string, OracleDefinition>()
    for (const row of [...rows].sort((left, right) =>
      String(left.created_at).localeCompare(String(right.created_at))
    )) {
      const definition = this.#toDefinition(row.payload)
      if (definition && !byId.has(definition.id)) byId.set(definition.id, definition)
    }
    return [...byId.values()]
  }

  async get(id: string): Promise<OracleDefinition | null> {
    const { rows } = await this.#client.query<DefinitionRow>(
      `SELECT created_at, payload FROM oracle_executions
       WHERE organization_id = $1 AND workstream_id = $2 AND oracle_id = $3`,
      [this.#organizationId, this.#workstreamId, id]
    )
    for (const row of rows) {
      const definition = this.#toDefinition(row.payload)
      if (definition && definition.id === id) return definition
    }
    return null
  }

  /**
   * Terminalizes an oracle execution and, atomically, publishes its linked
   * artifact (Amendment 5: upload-then-commit).
   */
  async terminalize(input: SqlOracleTerminalizationInput): Promise<SqlOracleTerminalizationResult> {
    const updatedAt = input.updatedAt ?? new Date().toISOString()
    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query<ExecutionRow>(
        `SELECT revision, artifact_id FROM oracle_executions
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
           AND workflow_id = $4 AND execution_id = $5`,
        [this.#organizationId, this.#workstreamId, input.namespaceId, input.workflowId, input.executionId]
      )
      const existing = rows[0]
      const revision = (existing?.revision ?? 0) + 1
      const artifactId = input.artifactId ?? existing?.artifact_id ?? null
      await tx.query(
        `UPDATE oracle_executions
           SET status = $1, revision = $2, updated_at = $3
         WHERE organization_id = $4 AND workstream_id = $5 AND namespace_id = $6
           AND workflow_id = $7 AND execution_id = $8`,
        [
          input.status,
          revision,
          updatedAt,
          this.#organizationId,
          this.#workstreamId,
          input.namespaceId,
          input.workflowId,
          input.executionId,
        ]
      )
      if (artifactId)
        await tx.query(
          `UPDATE artifacts
             SET availability_status = $1, updated_at = $2
           WHERE organization_id = $3 AND workstream_id = $4 AND namespace_id = $5
             AND workflow_id = $6 AND artifact_id = $7`,
          [
            'available',
            updatedAt,
            this.#organizationId,
            this.#workstreamId,
            input.namespaceId,
            input.workflowId,
            artifactId,
          ]
        )
      return { executionId: input.executionId, status: input.status, revision, artifactId }
    })
  }
}

/** Wires a SQL oracle-execution repository around a database client. */
export function createSqlOracleExecutionRepository(
  client: SqlClient,
  options: SqlOracleExecutionRepositoryOptions = {}
): SqlOracleExecutionRepository {
  return new SqlOracleExecutionRepository(client, options)
}

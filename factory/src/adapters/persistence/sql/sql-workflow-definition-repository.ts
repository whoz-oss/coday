import type {
  WorkflowDefinitionRepository,
  WorkflowDefinitionWithHash,
} from '../../../ports/persistence/workflow-definition-repository.js'
import { WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES } from '../../../ports/persistence/workflow-definition-repository.js'
import { WorkflowDefinitionRepositoryError } from '../filesystem-workflow-definition-repository.js'
import { DEFAULT_ORGANIZATION_ID, parseJsonColumn, type SqlClient } from './db.js'

/**
 * SQL definition repository adapter.
 *
 * Read-only over `workflow_definitions`. Tenant scoping is fixed at wiring time:
 * the default scope is the platform scope (`organization_id = 'default'`,
 * `workstream_id IS NULL`), matching the port which carries no tenant arguments.
 */

export interface SqlWorkflowDefinitionRepositoryOptions {
  organizationId?: string
  /** `null` (or omitted) means platform/organization scope. */
  workstreamId?: string | null
}

interface DefinitionRow {
  organization_id: string
  workstream_id: string | null
  workflow_type: string
  version: string
  definition_hash: string
  definition_json: unknown
}

const SELECT_COLUMNS = 'organization_id, workstream_id, workflow_type, version, definition_hash, definition_json'

function compareVersions(left: string, right: string): number {
  const parse = (value: string): number[] => value.split('.').map((part) => Number.parseInt(part, 10) || 0)
  const a = parse(left)
  const b = parse(right)
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const difference = (a[index] ?? 0) - (b[index] ?? 0)
    if (difference !== 0) return difference
  }
  return left.localeCompare(right)
}

function toDefinition(row: DefinitionRow): WorkflowDefinitionWithHash {
  return {
    ...(parseJsonColumn<WorkflowDefinitionWithHash>(row.definition_json) as WorkflowDefinitionWithHash),
    definitionHash: row.definition_hash,
  }
}

export class SqlWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string | null

  constructor(client: SqlClient, options: SqlWorkflowDefinitionRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? null
  }

  #inScope(row: DefinitionRow): boolean {
    return this.#workstreamId === null
      ? row.workstream_id === null || row.workstream_id === undefined
      : row.workstream_id === this.#workstreamId
  }

  async list(): Promise<WorkflowDefinitionWithHash[]> {
    const { rows } = await this.#client.query<DefinitionRow>(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions WHERE organization_id = $1`,
      [this.#organizationId]
    )
    return rows
      .filter((row) => this.#inScope(row))
      .map(toDefinition)
      .sort((left, right) => {
        const byType = left.workflowType.localeCompare(right.workflowType)
        return byType !== 0 ? byType : compareVersions(left.version, right.version)
      })
  }

  async get(workflowType: string, version: string): Promise<WorkflowDefinitionWithHash | null> {
    const { rows } = await this.#client.query<DefinitionRow>(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions
       WHERE organization_id = $1 AND workflow_type = $2 AND version = $3`,
      [this.#organizationId, workflowType, version]
    )
    const row = rows.find((candidate) => this.#inScope(candidate))
    return row ? toDefinition(row) : null
  }

  async resolveUnique(workflowType: string): Promise<WorkflowDefinitionWithHash> {
    const { rows } = await this.#client.query<DefinitionRow>(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions
       WHERE organization_id = $1 AND workflow_type = $2`,
      [this.#organizationId, workflowType]
    )
    const scoped = rows.filter((row) => this.#inScope(row)).map(toDefinition)
    if (scoped.length === 0)
      throw new WorkflowDefinitionRepositoryError(
        WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES.WORKFLOW_DEFINITION_NOT_FOUND,
        { workflowType }
      )
    return scoped.reduce((latest, candidate) =>
      compareVersions(candidate.version, latest.version) > 0 ? candidate : latest
    )
  }
}

/** Wires a SQL definition repository around a database client. */
export function createSqlWorkflowDefinitionRepository(
  client: SqlClient,
  options: SqlWorkflowDefinitionRepositoryOptions = {}
): SqlWorkflowDefinitionRepository {
  return new SqlWorkflowDefinitionRepository(client, options)
}

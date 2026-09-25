import type {
  WorkflowInstanceRemoveActor,
  WorkflowInstanceRepository,
  WorkflowInstanceSnapshot,
} from '../../../ports/persistence/workflow-instance-repository.js'
import type {
  ControllerExecutionInput,
  WorkflowDefinitionInput,
  WorkflowProjection,
  WorkflowStartCommand,
} from '../../../domain/workflow/workflow-instance.js'
import { createWorkflowInstance, workflowStartCommandHash } from '../../../domain/workflow/workflow-instance.js'
import {
  applyWorkflowTransition,
  evaluateWorkflowTransition,
  type TransitionDecision,
  type WorkflowExecution,
  type WorkflowPolicyDefinition,
  type WorkflowPolicyEvidence,
  type WorkflowSnapshot,
  type WorkflowTransitionEvaluationInput,
  type WorkflowTransitionRequest,
} from '../../../domain/workflow/workflow-transition-policy.js'
import {
  WorkflowInstanceRepositoryError,
  type WorkflowInstanceTransitionInput,
} from '../filesystem-workflow-instance-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'

/**
 * SQL workflow-instance repository adapter.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time. `revision`
 * is the optimistic-locking column and `creation_command_hash` mirrors the
 * filesystem idempotency key. The transition business rules are the shared pure
 * domain ones (`evaluateWorkflowTransition` / `applyWorkflowTransition`), so the
 * SQL and filesystem adapters apply the exact same state machine.
 */

export interface SqlWorkflowInstanceRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface InstanceRow {
  organization_id: string
  workstream_id: string
  namespace_id: string
  workflow_id: string
  revision: number
  status: string
  instance_json: unknown
  projection_json: unknown
  creation_command_hash: string | null
}

const INSTANCE_COLUMNS = [
  'organization_id',
  'workstream_id',
  'namespace_id',
  'workflow_id',
  'revision',
  'status',
  'instance_json',
  'projection_json',
  'creation_command_hash',
].join(', ')
const INSTANCE_INSERT_COLUMNS = `${INSTANCE_COLUMNS}, created_at, updated_at`
const ACTIVE_STATUS = 'active'
const REMOVED_STATUS = 'removed'

function readSnapshot(row: InstanceRow): WorkflowInstanceSnapshot {
  return {
    instance: parseJsonColumn<WorkflowInstanceSnapshot['instance']>(row.instance_json),
    projection: parseJsonColumn<WorkflowProjection>(row.projection_json),
  }
}

export class SqlWorkflowInstanceRepository implements WorkflowInstanceRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlWorkflowInstanceRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  async #select(namespaceId: string, workflowId: string): Promise<InstanceRow | null> {
    const { rows } = await this.#client.query<InstanceRow>(
      `SELECT ${INSTANCE_COLUMNS} FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId]
    )
    return rows[0] ?? null
  }

  async list(namespaceId: string): Promise<WorkflowProjection[]> {
    const { rows } = await this.#client.query<InstanceRow>(
      `SELECT ${INSTANCE_COLUMNS} FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND status = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, ACTIVE_STATUS]
    )
    return rows
      .map((row) => parseJsonColumn<WorkflowProjection>(row.projection_json))
      .sort((left, right) => left.workflowId.localeCompare(right.workflowId))
  }

  async get(namespaceId: string, workflowId: string): Promise<WorkflowInstanceSnapshot | null> {
    const row = await this.#select(namespaceId, workflowId)
    if (!row || row.status !== ACTIVE_STATUS) return null
    return readSnapshot(row)
  }

  async create(
    namespaceId: string,
    command: WorkflowStartCommand,
    definition: WorkflowDefinitionInput,
    controllerExecution: ControllerExecutionInput
  ): Promise<WorkflowInstanceSnapshot> {
    const existing = await this.#select(namespaceId, command.workflowId)
    if (existing) {
      const commandHash = workflowStartCommandHash(command, definition)
      if (existing.status !== ACTIVE_STATUS)
        throw new WorkflowInstanceRepositoryError('WORKFLOW_REMOVED', { workflowId: command.workflowId })
      if (existing.creation_command_hash === commandHash) return readSnapshot(existing)
      throw new WorkflowInstanceRepositoryError('WORKFLOW_IDENTITY_CONFLICT', {
        workflowId: command.workflowId,
      })
    }
    const created = createWorkflowInstance(command, definition, controllerExecution)
    const observedAt = created.instance.createdAt
    await this.#client.query(
      `INSERT INTO workflow_instances
         (${INSTANCE_INSERT_COLUMNS})
       VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9, $10, $11)
       ON CONFLICT DO NOTHING`,
      [
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        command.workflowId,
        1,
        ACTIVE_STATUS,
        JSON.stringify(created.instance),
        JSON.stringify(created.projection),
        created.creationCommandHash,
        observedAt,
        observedAt,
      ]
    )
    const stored = await this.#select(namespaceId, command.workflowId)
    if (!stored)
      throw new WorkflowInstanceRepositoryError('WORKFLOW_INSTANCE_CREATE_FAILED', {
        workflowId: command.workflowId,
      })
    return readSnapshot(stored)
  }

  async transition(namespaceId: string, workflowId: string, transition: unknown): Promise<WorkflowInstanceSnapshot> {
    const input = (transition ?? {}) as WorkflowInstanceTransitionInput
    const request = input.request as WorkflowTransitionRequest
    const definition = input.definition as WorkflowPolicyDefinition
    const current = await this.#select(namespaceId, workflowId)
    if (!current || current.status !== ACTIVE_STATUS)
      throw new WorkflowInstanceRepositoryError('WORKFLOW_NOT_FOUND', { workflowId })
    const snapshot = readSnapshot(current)
    const evaluationSnapshot = {
      ...(snapshot.instance as unknown as Record<string, unknown>),
      instance: snapshot.instance,
      projection: snapshot.projection,
      revision: snapshot.instance.revision,
    } as unknown as WorkflowSnapshot
    const execution = {
      ...((input.execution ?? {}) as Record<string, unknown>),
      namespaceId,
    } as unknown as WorkflowExecution
    const evidence = (input.evidence ?? []) as WorkflowPolicyEvidence[]
    const policy =
      typeof input.policy === 'function'
        ? (input.policy as (value: WorkflowTransitionEvaluationInput) => TransitionDecision)
        : evaluateWorkflowTransition
    const decision = policy({ request, snapshot: evaluationSnapshot, definition, evidence, execution })
    if (!decision?.allowed)
      throw new WorkflowInstanceRepositoryError(
        decision?.code ?? 'TRANSITION_REJECTED',
        decision?.missingEvidence ? { missingEvidence: decision.missingEvidence } : {},
        decision
      )
    const observedAt = new Date().toISOString()
    const applied = applyWorkflowTransition(evaluationSnapshot, definition, request, observedAt)
    const expectedRevision =
      typeof request?.expectedRevision === 'number' ? request.expectedRevision : snapshot.instance.revision
    const { rowCount } = await this.#client.query(
      `UPDATE workflow_instances
         SET revision = $1, instance_json = $2::jsonb, projection_json = $3::jsonb, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND workflow_id = $8
         AND revision = $9 AND status = $10`,
      [
        applied.revision,
        JSON.stringify(applied.instance),
        JSON.stringify(applied.projection),
        observedAt,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        workflowId,
        expectedRevision,
        ACTIVE_STATUS,
      ]
    )
    if (!rowCount) throw new WorkflowInstanceRepositoryError('REVISION_CONFLICT', { workflowId, expectedRevision })
    return {
      instance: applied.instance as unknown as WorkflowInstanceSnapshot['instance'],
      projection: applied.projection as unknown as WorkflowProjection,
    }
  }

  async #setStatus(
    namespaceId: string,
    workflowId: string,
    from: string,
    to: string,
    actor: WorkflowInstanceRemoveActor | undefined,
    failureCode: string
  ): Promise<void> {
    // Actor attribution is accepted for port parity; the pilot schema records the
    // lifecycle status only (audit attribution is deferred to the B2 schema work).
    void actor
    const { rowCount } = await this.#client.query(
      `UPDATE workflow_instances
         SET status = $1, updated_at = $2
       WHERE organization_id = $3 AND workstream_id = $4 AND namespace_id = $5 AND workflow_id = $6 AND status = $7`,
      [to, new Date().toISOString(), this.#organizationId, this.#workstreamId, namespaceId, workflowId, from]
    )
    if (!rowCount) throw new WorkflowInstanceRepositoryError(failureCode, { workflowId })
  }

  async remove(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    await this.#setStatus(namespaceId, workflowId, ACTIVE_STATUS, REMOVED_STATUS, actor, 'WORKFLOW_NOT_FOUND')
  }

  async restore(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    await this.#setStatus(namespaceId, workflowId, REMOVED_STATUS, ACTIVE_STATUS, actor, 'WORKFLOW_NOT_FOUND')
  }

  async purge(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    void actor
    await this.#client.query(
      `DELETE FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId]
    )
  }
}

/** Wires a SQL instance repository around a database client. */
export function createSqlWorkflowInstanceRepository(
  client: SqlClient,
  options: SqlWorkflowInstanceRepositoryOptions = {}
): SqlWorkflowInstanceRepository {
  return new SqlWorkflowInstanceRepository(client, options)
}

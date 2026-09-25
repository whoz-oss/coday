import { randomUUID } from 'node:crypto'

import {
  AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS,
  AGENT_STEP_ATTEMPT_TRANSITIONS,
  validateAgentStepAttempt,
  type AgentStepAttempt,
  type AgentStepAttemptStatus,
} from '../../../domain/agent-attempt/agent-step-attempt.js'
import type { AgentStepAttemptRepository } from '../../../ports/persistence/agent-step-attempt-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL agent-step-attempt repository adapter (V6 aggregate).
 *
 * The durable attempt journal is modelled by the mutable `agent_step_attempts`
 * root (latest attempt state, optimistic `revision`) plus the append-only
 * `agent_step_attempt_events` log (one immutable row per append). The shared
 * pure domain rules (`validateAgentStepAttempt`, the immutable-field set and the
 * status-transition table) are applied here exactly like the filesystem store,
 * so both adapters raise the same error codes.
 *
 * Tenant scoping is fixed at wiring time; the domain `storageId` maps onto the
 * `step_id` column so the same `(namespaceId, storageId)` scope used by the
 * filesystem journal isolates SQL rows too.
 */

export interface SqlAgentStepAttemptRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface AttemptRow {
  revision: number
  payload: unknown
}

interface EventRow {
  created_at: string
  payload: unknown
}

/**
 * The V6 `agent_step_attempts.status` CHECK only allows the operational states
 * (`running`, `completed`, `failed`, `timed_out`, `cancelled`). The richer
 * domain lifecycle is persisted verbatim inside the JSONB `payload` (and the
 * event log); this maps it to the constrained column.
 */
const ATTEMPT_DB_STATUS: Readonly<Record<AgentStepAttemptStatus, string>> = Object.freeze({
  starting: 'running',
  running: 'running',
  succeeded: 'completed',
  failed: 'failed',
  indeterminate: 'timed_out',
  interrupted: 'cancelled',
})

export class SqlAgentStepAttemptRepository implements AgentStepAttemptRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlAgentStepAttemptRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  async #selectAttempt(
    client: SqlClient,
    namespaceId: string,
    workflowId: string,
    storageId: string,
    attemptId: string
  ): Promise<AttemptRow | null> {
    const { rows } = await client.query<AttemptRow>(
      `SELECT revision, payload FROM agent_step_attempts
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND workflow_id = $4 AND step_id = $5 AND attempt_id = $6`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId, storageId, attemptId]
    )
    return rows[0] ?? null
  }

  async list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]> {
    const { rows } = await this.#client.query<EventRow>(
      `SELECT created_at, payload FROM agent_step_attempt_events
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId]
    )
    return [...rows]
      .sort((left, right) => String(left.created_at).localeCompare(String(right.created_at)))
      .map((row) => parseJsonColumn<AgentStepAttempt>(row.payload))
  }

  async append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt> {
    const validated = validateAgentStepAttempt(attempt)
    if (validated.namespaceId !== namespaceId) throw new Error('AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH')
    const observedAt = new Date().toISOString()
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectAttempt(tx, namespaceId, validated.workflowId, storageId, validated.attemptId)
      if (existing) {
        const previous = parseJsonColumn<AgentStepAttempt>(existing.payload)
        if (AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS.some((field) => previous[field] !== validated[field]))
          throw new Error('AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT')
        const allowed = AGENT_STEP_ATTEMPT_TRANSITIONS[previous.status] ?? []
        if (!allowed.includes(validated.status)) throw new Error('INVALID_AGENT_STEP_ATTEMPT_TRANSITION')
        const nextRevision = existing.revision + 1
        const { rowCount } = await tx.query(
          `UPDATE agent_step_attempts
             SET status = $1, revision = $2, payload = $3::jsonb, updated_at = $4
           WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7
             AND workflow_id = $8 AND step_id = $9 AND attempt_id = $10 AND revision = $11`,
          [
            ATTEMPT_DB_STATUS[validated.status],
            nextRevision,
            JSON.stringify(validated),
            observedAt,
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            validated.workflowId,
            storageId,
            validated.attemptId,
            existing.revision,
          ]
        )
        if (!rowCount) throw new Error('AGENT_STEP_ATTEMPT_REVISION_CONFLICT')
      } else {
        if (validated.status !== 'starting') throw new Error('AGENT_STEP_ATTEMPT_MUST_START')
        await tx.query(
          `INSERT INTO agent_step_attempts
             (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, agent_id,
              status, revision, payload, created_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12)`,
          [
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            validated.workflowId,
            storageId,
            validated.attemptId,
            validated.agentName,
            ATTEMPT_DB_STATUS[validated.status],
            1,
            JSON.stringify(validated),
            observedAt,
            observedAt,
          ]
        )
      }
      await tx.query(
        `INSERT INTO agent_step_attempt_events
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            event_id, event_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          validated.workflowId,
          storageId,
          validated.attemptId,
          randomUUID(),
          validated.status,
          JSON.stringify(validated),
          observedAt,
        ]
      )
      return validated
    })
  }
}

/** Wires a SQL agent-step-attempt repository around a database client. */
export function createSqlAgentStepAttemptRepository(
  client: SqlClient,
  options: SqlAgentStepAttemptRepositoryOptions = {}
): SqlAgentStepAttemptRepository {
  return new SqlAgentStepAttemptRepository(client, options)
}

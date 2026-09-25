import { randomBytes, randomUUID } from 'node:crypto'

import {
  canonicalAgentStepResultJson,
  isSafeAgentStepResultId,
  safeEqual,
  sha256,
  validateAgentStepResultBusiness,
  type AgentStepResultCapabilityIdentity,
  type AgentStepResultCapabilityIssued,
  type AgentStepResultLedgerEvent,
  type AgentStepResultObservedIdentity,
  type AgentStepResultSubmitted,
} from '../../../domain/agent-attempt/agent-step-result.js'
import type {
  AgentStepResultIssueResult,
  AgentStepResultRepository,
  AgentStepResultSubmitResult,
} from '../../../ports/persistence/agent-step-result-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL agent-step-result repository adapter (V6 aggregate + V4 outbox).
 *
 * Capabilities are persisted in the append-only `result_capabilities` table (the
 * clear token is never stored, only its SHA-256 digest inside the JSONB
 * `payload`), submitted results in the append-only `agent_step_results` table.
 * On submission the attempt root is terminalized in the same `withTransaction`
 * unit of work and a `result_submitted` event is written to the V4
 * `outbox_events` table, so the result, the attempt status and the outbox event
 * commit or roll back atomically (Amendment 4).
 *
 * The submission state machine mirrors the filesystem `AgentStepResultStore`
 * exactly: schema validation, capability resolution by token digest, observed
 * identity check, idempotent replay of an identical payload, semantic-collision
 * detection and expiry.
 */

export interface SqlAgentStepResultRepositoryOptions {
  organizationId?: string
  workstreamId?: string
  clock?: () => Date
  ttlMs?: number
}

interface CapabilityRow {
  step_id: string
  payload: unknown
}

interface ResultRow {
  payload: unknown
}

interface LedgerRow {
  created_at: string
  payload: unknown
}

interface AttemptRevisionRow {
  revision: number
}

const IDENTITY_FIELDS = ['attemptId', 'workflowId', 'stepId', 'namespaceId', 'caseId', 'agentName'] as const
const CAPABILITY_MATCH_FIELDS = [...IDENTITY_FIELDS, 'briefHash'] as const
const BRIEF_HASH = /^sha256:[0-9a-f]{64}$/
const CAPABILITY_TYPE = 'agent_step_submit'
const DEFAULT_TTL_MS = 15 * 60 * 1000

/** V6 `agent_step_results.result_status` mapping (append-only result row). */
const RESULT_DB_STATUS: Readonly<Record<'PASS' | 'FAIL', string>> = Object.freeze({
  PASS: 'success',
  FAIL: 'failure',
})

/** V6 `agent_step_attempts.status` terminal mapping (Amendment 4). */
const ATTEMPT_TERMINAL_STATUS: Readonly<Record<'PASS' | 'FAIL', string>> = Object.freeze({
  PASS: 'completed',
  FAIL: 'failed',
})

export class SqlAgentStepResultRepository implements AgentStepResultRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string
  readonly #clock: () => Date
  readonly #ttlMs: number

  constructor(client: SqlClient, options: SqlAgentStepResultRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
    this.#clock = options.clock ?? (() => new Date())
    this.#ttlMs = options.ttlMs ?? DEFAULT_TTL_MS
  }

  async #selectCapability(
    client: SqlClient,
    namespaceId: string,
    storageId: string,
    attemptId: string
  ): Promise<{ row: CapabilityRow; record: AgentStepResultCapabilityIssued } | null> {
    const { rows } = await client.query<CapabilityRow>(
      `SELECT step_id, payload FROM result_capabilities
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND step_id = $4 AND attempt_id = $5 AND capability_type = $6`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId, attemptId, CAPABILITY_TYPE]
    )
    const row = rows[0]
    if (!row) return null
    return { row, record: parseJsonColumn<AgentStepResultCapabilityIssued>(row.payload) }
  }

  async #selectResult(
    client: SqlClient,
    namespaceId: string,
    storageId: string,
    attemptId: string
  ): Promise<AgentStepResultSubmitted | null> {
    const { rows } = await client.query<ResultRow>(
      `SELECT payload FROM agent_step_results
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND step_id = $4 AND attempt_id = $5`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId, attemptId]
    )
    const row = rows[0]
    return row ? parseJsonColumn<AgentStepResultSubmitted>(row.payload) : null
  }

  async #findByToken(
    tokenHash: string
  ): Promise<{ storageId: string; record: AgentStepResultCapabilityIssued } | null> {
    // The token digest lives inside the JSONB payload (no dedicated column), so
    // the lookup scans the submission capabilities and compares constant-time.
    const { rows } = await this.#client.query<CapabilityRow>(
      `SELECT step_id, payload FROM result_capabilities WHERE capability_type = $1`,
      [CAPABILITY_TYPE]
    )
    for (const row of rows) {
      const record = parseJsonColumn<Partial<AgentStepResultCapabilityIssued> | null>(row.payload)
      if (
        record?.type === 'capability-issued' &&
        typeof record.tokenHash === 'string' &&
        safeEqual(record.tokenHash, tokenHash)
      )
        return { storageId: row.step_id, record: record as AgentStepResultCapabilityIssued }
    }
    return null
  }

  async issue(
    namespaceId: string,
    storageId: string,
    identity: AgentStepResultCapabilityIdentity
  ): Promise<AgentStepResultIssueResult> {
    for (const key of IDENTITY_FIELDS)
      if (!isSafeAgentStepResultId(identity[key])) throw new Error('INVALID_RESULT_CAPABILITY_IDENTITY')
    if (identity.namespaceId !== namespaceId || !BRIEF_HASH.test(identity.briefHash ?? ''))
      throw new Error('INVALID_RESULT_CAPABILITY_IDENTITY')

    const token = randomBytes(32).toString('base64url')
    const now = this.#clock()
    const record: AgentStepResultCapabilityIssued = {
      type: 'capability-issued',
      capabilityId: randomUUID(),
      tokenHash: sha256(token),
      attemptId: identity.attemptId,
      workflowId: identity.workflowId,
      stepId: identity.stepId,
      namespaceId: identity.namespaceId,
      caseId: identity.caseId,
      agentName: identity.agentName,
      briefHash: identity.briefHash,
      issuedAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + this.#ttlMs).toISOString(),
      submissionBudget: 1,
    }
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectCapability(tx, namespaceId, storageId, identity.attemptId)
      if (existing) {
        const same = CAPABILITY_MATCH_FIELDS.every((field) => existing.record[field] === identity[field])
        throw new Error(same ? 'RESULT_CAPABILITY_ALREADY_ISSUED' : 'RESULT_CAPABILITY_IDENTITY_CONFLICT')
      }
      await tx.query(
        `INSERT INTO result_capabilities
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            result_id, capability_id, capability_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          identity.workflowId,
          storageId,
          identity.attemptId,
          '',
          record.capabilityId,
          CAPABILITY_TYPE,
          JSON.stringify(record),
          now.toISOString(),
        ]
      )
      return { token, expiresAt: record.expiresAt }
    })
  }

  async submit(
    token: string,
    business: unknown,
    observed: Partial<AgentStepResultObservedIdentity> = {}
  ): Promise<AgentStepResultSubmitResult> {
    if (!validateAgentStepResultBusiness(business)) return { ok: false, code: 'RESULT_SCHEMA_INVALID' }
    const located = await this.#findByToken(sha256(token))
    if (!located) return { ok: false, code: 'RESULT_CAPABILITY_INVALID' }
    const issued = located.record
    if (
      observed.attemptId !== issued.attemptId ||
      observed.caseId !== issued.caseId ||
      observed.agentName !== issued.agentName
    )
      return { ok: false, code: 'RESULT_IDENTITY_MISMATCH' }
    const resultHash = sha256(canonicalAgentStepResultJson(business))
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectResult(tx, issued.namespaceId, located.storageId, issued.attemptId)
      if (existing)
        return existing.resultHash === resultHash
          ? { ok: true, idempotent: true, result: existing }
          : { ok: false, code: 'RESULT_SEMANTIC_COLLISION' }
      if (this.#clock().getTime() > Date.parse(issued.expiresAt))
        return { ok: false, code: 'RESULT_CAPABILITY_EXPIRED' }

      const result: AgentStepResultSubmitted = {
        type: 'result-submitted',
        resultId: randomUUID(),
        attemptId: issued.attemptId,
        workflowId: issued.workflowId,
        stepId: issued.stepId,
        namespaceId: issued.namespaceId,
        caseId: issued.caseId,
        agentName: issued.agentName,
        briefHash: issued.briefHash,
        status: business.status,
        summary: business.summary,
        artifacts: business.artifacts ?? [],
        claims: business.claims,
        findings: business.findings ?? [],
        submittedAt: this.#clock().toISOString(),
        resultHash,
      }
      await tx.query(
        `INSERT INTO agent_step_results
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            result_id, result_status, semantic_signature, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11)`,
        [
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId,
          result.resultId,
          RESULT_DB_STATUS[result.status],
          resultHash,
          JSON.stringify(result),
          result.submittedAt,
        ]
      )
      const attemptRevision = await tx.query<AttemptRevisionRow>(
        `SELECT revision FROM agent_step_attempts
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
           AND workflow_id = $4 AND step_id = $5 AND attempt_id = $6`,
        [
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId,
        ]
      )
      await tx.query(
        `UPDATE agent_step_attempts
           SET status = $1, revision = $2, updated_at = $3
         WHERE organization_id = $4 AND workstream_id = $5 AND namespace_id = $6
           AND workflow_id = $7 AND step_id = $8 AND attempt_id = $9`,
        [
          ATTEMPT_TERMINAL_STATUS[result.status],
          (attemptRevision.rows[0]?.revision ?? 0) + 1,
          result.submittedAt,
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId,
        ]
      )
      await tx.query(
        `INSERT INTO outbox_events
           (organization_id, id, workstream_id, event_type, payload, status, created_at)
         VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7)`,
        [
          this.#organizationId,
          randomUUID(),
          this.#workstreamId,
          'result_submitted',
          JSON.stringify({
            aggregateType: 'agent_step_result',
            attemptId: issued.attemptId,
            resultId: result.resultId,
            status: result.status,
          }),
          'pending',
          result.submittedAt,
        ]
      )
      return { ok: true, idempotent: false, result }
    })
  }

  async getByAttempt(
    namespaceId: string,
    storageId: string,
    attemptId: string
  ): Promise<AgentStepResultSubmitted | null> {
    return this.#selectResult(this.#client, namespaceId, storageId, attemptId)
  }

  async list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]> {
    const [capabilities, results] = await Promise.all([
      this.#client.query<LedgerRow>(
        `SELECT created_at, payload FROM result_capabilities
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
        [this.#organizationId, this.#workstreamId, namespaceId, storageId]
      ),
      this.#client.query<LedgerRow>(
        `SELECT created_at, payload FROM agent_step_results
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
        [this.#organizationId, this.#workstreamId, namespaceId, storageId]
      ),
    ])
    return [...capabilities.rows, ...results.rows]
      .sort((left, right) => String(left.created_at).localeCompare(String(right.created_at)))
      .map((row) => parseJsonColumn<AgentStepResultLedgerEvent>(row.payload))
  }
}

/** Wires a SQL agent-step-result repository around a database client. */
export function createSqlAgentStepResultRepository(
  client: SqlClient,
  options: SqlAgentStepResultRepositoryOptions = {}
): SqlAgentStepResultRepository {
  return new SqlAgentStepResultRepository(client, options)
}

import { createHash } from 'node:crypto'

import {
  createWorkflowEvidence,
  type ValidatedWorkflowEvidence,
  type WorkflowEvidence,
  type WorkflowEvidenceInput,
  type WorkflowEvidenceSource,
} from '../../../domain/evidence/workflow-evidence.js'
import type {
  WorkflowEvidenceListFilter,
  WorkflowEvidenceRecordResult,
  WorkflowEvidenceRepository,
} from '../../../ports/persistence/workflow-evidence-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL workflow-evidence repository adapter.
 *
 * Evidence is strictly append-only (Jalon B Amendment 3): the adapter only ever
 * issues `INSERT` against `workflow_evidence`, never an `UPDATE` or `DELETE`.
 * The idempotency scope/fingerprint hashing and the collision code are exactly
 * the ones the filesystem store applies, so a replay returns the very same
 * record whichever backend served the first write.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time. The port's
 * opaque `storageId` is used as the relational `workflow_id` scope column on
 * reads; the domain `workflowId` carried by the record is preserved verbatim in
 * the JSONB payload.
 */

/**
 * Error raised by the evidence persistence boundary. Kernel/shape compatibility
 * with the `.mjs` `WorkflowEvidenceStoreError` (same `code` catalogue) so callers
 * keep their error semantics across the two adapters.
 */
export class WorkflowEvidenceStoreError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'WorkflowEvidenceStoreError'
    this.code = code
    this.details = details
  }
}

export interface SqlWorkflowEvidenceRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface EvidenceIdempotency {
  scopeHash: string
  semanticHash: string
}

/** Durable row shape: the domain record plus the optional idempotency metadata. */
type StoredWorkflowEvidence = WorkflowEvidence & { idempotency?: EvidenceIdempotency }

interface EvidenceRow {
  organization_id: string
  workstream_id: string
  namespace_id: string
  workflow_id: string
  evidence_id: string
  evidence_type: string
  source: string
  producer: string
  payload: unknown
  created_at: string
}

const EVIDENCE_COLUMNS = [
  'organization_id',
  'workstream_id',
  'namespace_id',
  'workflow_id',
  'evidence_id',
  'evidence_type',
  'source',
  'producer',
  'payload',
  'created_at',
].join(', ')

const EVIDENCE_INSERT_COLUMNS = `${EVIDENCE_COLUMNS}`

/** Plain sha256 over the canonical JSON string, matching the filesystem store. */
function semanticHash(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex')
}

async function selectEvidence(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  namespaceId: string,
  workflowId: string
): Promise<StoredWorkflowEvidence[]> {
  const { rows } = await client.query<EvidenceRow>(
    `SELECT ${EVIDENCE_COLUMNS} FROM workflow_evidence
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
    [organizationId, workstreamId, namespaceId, workflowId]
  )
  return rows.map((row) => parseJsonColumn<StoredWorkflowEvidence>(row.payload))
}

export class SqlWorkflowEvidenceRepository implements WorkflowEvidenceRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlWorkflowEvidenceRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  async list(namespaceId: string, storageId: string, filter?: WorkflowEvidenceListFilter): Promise<WorkflowEvidence[]> {
    const items = await selectEvidence(this.#client, this.#organizationId, this.#workstreamId, namespaceId, storageId)
    return items
      .filter((item) => !filter?.stepId || item.stepId === filter.stepId)
      .sort((left, right) => {
        const chronology = left.observedAt.localeCompare(right.observedAt)
        return chronology !== 0 ? chronology : left.evidenceId.localeCompare(right.evidenceId)
      })
  }

  async record(
    namespaceId: string,
    storageId: string,
    input: WorkflowEvidenceInput,
    source: WorkflowEvidenceSource
  ): Promise<WorkflowEvidenceRecordResult> {
    return withTransaction(this.#client, async (tx) => {
      const scope = {
        namespaceId,
        workflowId: input.workflowId,
        stepId: input.stepId,
        source,
        idempotencyKey: input.idempotencyKey,
      }
      const scopeHash = semanticHash(scope)
      const fingerprint = semanticHash({ ...input, idempotencyKey: undefined })
      const existing = await selectEvidence(tx, this.#organizationId, this.#workstreamId, namespaceId, storageId)
      if (input.idempotencyKey) {
        const prior = existing.find((item) => item.idempotency?.scopeHash === scopeHash)
        if (prior) {
          if (prior.idempotency?.semanticHash !== fingerprint)
            throw new WorkflowEvidenceStoreError('IDEMPOTENCY_KEY_COLLISION')
          return { created: false, idempotent: true, evidence: prior }
        }
      }
      const evidence = createWorkflowEvidence(input as unknown as ValidatedWorkflowEvidence, namespaceId, source)
      const stored: StoredWorkflowEvidence = input.idempotencyKey
        ? { ...evidence, idempotency: { scopeHash, semanticHash: fingerprint } }
        : { ...evidence }
      await tx.query(
        `INSERT INTO workflow_evidence (${EVIDENCE_INSERT_COLUMNS}) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          input.workflowId,
          evidence.evidenceId,
          input.kind,
          source.kind ?? source.runtimeId ?? 'unknown',
          source.agentId ?? source.actorId ?? 'system',
          JSON.stringify(stored),
          evidence.observedAt,
        ]
      )
      return { created: true, idempotent: false, evidence: stored }
    })
  }
}

/** Wires a SQL evidence repository around a database client. */
export function createSqlWorkflowEvidenceRepository(
  client: SqlClient,
  options: SqlWorkflowEvidenceRepositoryOptions = {}
): SqlWorkflowEvidenceRepository {
  return new SqlWorkflowEvidenceRepository(client, options)
}

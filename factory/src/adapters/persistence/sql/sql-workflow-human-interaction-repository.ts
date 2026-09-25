import { randomUUID } from 'node:crypto'

import type { WorkflowEvidence } from '../../../domain/evidence/workflow-evidence.js'
import {
  humanInteractionSemanticHash,
  openedInteractionRevision,
  validateHumanInteractionOpenInput,
  type HumanInteractionInput,
  type HumanInteractionRecord,
  type HumanInteractionReply,
  type WorkflowHumanInteractionEvent,
} from '../../../domain/interaction/workflow-human-interaction.js'
import type {
  WorkflowHumanInteractionListOptions,
  WorkflowHumanInteractionOpenOptions,
  WorkflowHumanInteractionRecordResult,
  WorkflowHumanInteractionReconcileOptions,
  WorkflowHumanInteractionRepository,
  WorkflowHumanInteractionTransitionOptions,
} from '../../../ports/persistence/workflow-human-interaction-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL workflow human-interaction repository adapter.
 *
 * The append-only `human_interaction_events` log is the source of truth; `list`
 * replays it with exactly the projection the filesystem store applies (so both
 * adapters materialise identical records and raise the same
 * `CORRUPT_INTERACTION_STORAGE` code). `human_interactions` mirrors the latest
 * projection for querying and carries the optimistic-locking `revision`.
 *
 * Opening a checkpoint and replying to it are atomic (Jalon B Amendment 2): the
 * interaction row, its event, the optional evidence row and the outbox event are
 * written inside ONE `withTransaction` unit of work, so there is never a durable
 * "decided but waiting" state. A failure of the injected authoritative
 * transition rolls the whole unit of work back.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time. The port's
 * opaque `storageId` is used as the relational `workflow_id` scope on reads; the
 * domain `workflowId` carried by each record is preserved in the JSONB payload.
 */

/** Domain-level interaction error (lifecycle/idempotency), kernel-compatible with the `.mjs` store. */
export class WorkflowHumanInteractionError extends Error {
  readonly code: string
  decision?: unknown

  constructor(code: string, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'WorkflowHumanInteractionError'
    this.code = code
  }
}

/**
 * Repository-contract error (missing injected callback / unrecoverable opening),
 * code-compatible with the filesystem adapter's identically named class.
 */
export class WorkflowHumanInteractionRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'WorkflowHumanInteractionRepositoryError'
    this.code = code
    this.details = details
  }
}

export interface SqlWorkflowHumanInteractionRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface InteractionRow {
  organization_id: string
  workstream_id: string
  namespace_id: string
  workflow_id: string
  interaction_id: string
  interaction_type: string
  status: string
  revision: number
  payload: unknown
  created_at: string
  updated_at: string
}

interface HumanInteractionEventRow {
  organization_id: string
  workstream_id: string
  namespace_id: string
  workflow_id: string
  interaction_id: string
  event_id: string
  event_type: string
  actor_id: string
  payload: unknown
  created_at: string
}

interface SnapshotStep {
  id?: string
  status?: string
}

interface RecoverySnapshot {
  revision?: unknown
  instance?: { steps?: SnapshotStep[] } | null
}

interface WorkflowFact {
  kind?: unknown
  revision?: unknown
  transitionDelta?: { steps?: Array<{ stepId?: unknown; status?: { from?: unknown; to?: unknown } }> } | null
}

interface OpenTransitionOutcome {
  ok?: boolean
  changed?: boolean
  idempotent?: boolean
  requestId?: string
  snapshot?: { revision?: number }
  error?: { code?: string }
  decision?: unknown
}

interface ReplyActionOutcome {
  reply?: HumanInteractionReply
  actorId?: string
  evidenceId?: string
  transition?: { ok?: boolean; requestId?: string; snapshot?: { revision?: number } }
  evidence?: unknown
}

const EVENT_COLUMNS = [
  'organization_id',
  'workstream_id',
  'namespace_id',
  'workflow_id',
  'interaction_id',
  'event_id',
  'event_type',
  'actor_id',
  'payload',
  'created_at',
].join(', ')

function dbStatus(status: HumanInteractionRecord['status']): string {
  return status === 'replied' ? 'answered' : 'waiting'
}

async function selectEvents(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  namespaceId: string,
  workflowId: string
): Promise<WorkflowHumanInteractionEvent[]> {
  const { rows } = await client.query<HumanInteractionEventRow>(
    `SELECT ${EVENT_COLUMNS} FROM human_interaction_events
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
    [organizationId, workstreamId, namespaceId, workflowId]
  )
  return rows
    .slice()
    .sort((left, right) => {
      if (left.created_at < right.created_at) return -1
      if (left.created_at > right.created_at) return 1
      return 0
    })
    .map((row) => parseJsonColumn<WorkflowHumanInteractionEvent>(row.payload))
}

/**
 * Replays the append-only log into interaction records. Byte-for-byte the same
 * state machine as `factory/lib/workflow-human-interaction-store.mjs` so parity
 * holds, including the `CORRUPT_INTERACTION_STORAGE` rejection of invalid logs.
 */
function projectEvents(events: WorkflowHumanInteractionEvent[]): HumanInteractionRecord[] {
  const projected = new Map<string, HumanInteractionRecord>()
  for (const event of events) {
    if (event.event === 'interaction_opening') {
      const interaction = event.interaction
      if (!interaction?.interactionId || projected.has(interaction.interactionId))
        throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
      projected.set(interaction.interactionId, { ...interaction, status: 'opening' })
    } else if (event.event === 'interaction_opened') {
      const interaction = event.interaction
      const current = interaction?.interactionId ? projected.get(interaction.interactionId) : undefined
      const revision = openedInteractionRevision(event)
      if (current?.status === 'opening') {
        const validRevision =
          current.interactionType === 'retry'
            ? revision === current.expectedRevision
            : (revision ?? 0) > current.expectedRevision
        if (!Number.isSafeInteger(revision) || !validRevision)
          throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
        projected.set(current.interactionId, { ...current, status: 'open', revision: revision as number })
      } else if (!current) {
        if (!interaction?.interactionId || !Number.isSafeInteger(revision) || (revision as number) < 1)
          throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
        projected.set(interaction.interactionId, { ...interaction, status: 'open', revision: revision as number })
      } else {
        throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
      }
    } else if (event.event === 'interaction_open_aborted') {
      const interactionId = event.interactionId
      const current = interactionId ? projected.get(interactionId) : undefined
      if (!current || current.status !== 'opening')
        throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
      const aborted: HumanInteractionRecord = { ...current, status: 'aborted' }
      if (event.errorCode !== undefined) aborted.errorCode = event.errorCode
      projected.set(interactionId as string, aborted)
    } else if (event.event === 'interaction_transitioned') {
      const interactionId = event.interactionId
      const current = interactionId ? projected.get(interactionId) : undefined
      if (!current || current.status !== 'open') throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
      const replied: HumanInteractionRecord = { ...current, status: 'replied' }
      if (event.reply !== undefined) replied.reply = event.reply
      if (event.actorId !== undefined) replied.actorId = event.actorId
      if (event.repliedAt !== undefined) replied.repliedAt = event.repliedAt
      if (event.evidenceId !== undefined) replied.evidenceId = event.evidenceId
      if (event.transitionRequestId !== undefined) replied.transitionRequestId = event.transitionRequestId
      if (event.revision !== undefined) replied.revision = event.revision
      projected.set(interactionId as string, replied)
    } else {
      throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
    }
  }
  return [...projected.values()]
}

function interactionIdOf(event: WorkflowHumanInteractionEvent): string {
  return event.interaction?.interactionId ?? event.interactionId ?? ''
}

async function insertEvent(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  namespaceId: string,
  workflowId: string,
  event: WorkflowHumanInteractionEvent,
  createdAt: string
): Promise<void> {
  await client.query(
    `INSERT INTO human_interaction_events (${EVENT_COLUMNS}) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
    [
      organizationId,
      workstreamId,
      namespaceId,
      workflowId,
      interactionIdOf(event),
      randomUUID(),
      event.event,
      event.actorId ?? 'system',
      JSON.stringify(event),
      createdAt,
    ]
  )
}

async function upsertInteractionRow(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  namespaceId: string,
  workflowId: string,
  record: HumanInteractionRecord,
  timestamp: string
): Promise<void> {
  const status = dbStatus(record.status)
  const revision =
    Number.isSafeInteger(record.revision) && (record.revision as number) >= 1 ? (record.revision as number) : 1
  const { rows } = await client.query<{ interaction_id: string }>(
    `SELECT interaction_id FROM human_interactions
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4 AND interaction_id = $5`,
    [organizationId, workstreamId, namespaceId, workflowId, record.interactionId]
  )
  if (rows.length > 0) {
    await client.query(
      `UPDATE human_interactions SET status = $1, revision = $2, payload = $3::jsonb, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND workflow_id = $8 AND interaction_id = $9`,
      [
        status,
        revision,
        JSON.stringify(record),
        timestamp,
        organizationId,
        workstreamId,
        namespaceId,
        workflowId,
        record.interactionId,
      ]
    )
    return
  }
  await client.query(
    `INSERT INTO human_interactions
       (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, interaction_type, status, revision, payload, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10, $11)`,
    [
      organizationId,
      workstreamId,
      namespaceId,
      workflowId,
      record.interactionId,
      record.interactionType ?? record.kind,
      status,
      revision,
      JSON.stringify(record),
      timestamp,
      timestamp,
    ]
  )
}

async function insertOutboxEvent(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  eventType: string,
  payload: Record<string, unknown>
): Promise<void> {
  await client.query(
    `INSERT INTO outbox_events (organization_id, id, workstream_id, event_type, payload, status, attempts, created_at)
     VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8)`,
    [
      organizationId,
      randomUUID(),
      workstreamId,
      eventType,
      JSON.stringify(payload),
      'pending',
      0,
      new Date().toISOString(),
    ]
  )
}

async function insertEvidenceRecord(
  client: SqlClient,
  organizationId: string,
  workstreamId: string,
  evidence: WorkflowEvidence
): Promise<void> {
  await client.query(
    `INSERT INTO workflow_evidence
       (organization_id, workstream_id, namespace_id, workflow_id, evidence_id, evidence_type, source, producer, payload, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
    [
      organizationId,
      workstreamId,
      evidence.namespaceId,
      evidence.workflowId,
      evidence.evidenceId,
      evidence.kind,
      evidence.source?.kind ?? evidence.source?.runtimeId ?? 'unknown',
      evidence.source?.agentId ?? evidence.source?.actorId ?? 'system',
      JSON.stringify(evidence),
      evidence.observedAt,
    ]
  )
}

export class SqlWorkflowHumanInteractionRepository implements WorkflowHumanInteractionRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string
  #lastMillis = 0

  constructor(client: SqlClient, options: SqlWorkflowHumanInteractionRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  /** Strictly increasing ISO timestamps so the append-only log keeps its insertion order. */
  #timestamp(): string {
    const now = Math.max(Date.now(), this.#lastMillis + 1)
    this.#lastMillis = now
    return new Date(now).toISOString()
  }

  async #events(client: SqlClient, namespaceId: string, workflowId: string): Promise<WorkflowHumanInteractionEvent[]> {
    return selectEvents(client, this.#organizationId, this.#workstreamId, namespaceId, workflowId)
  }

  async #project(client: SqlClient, namespaceId: string, workflowId: string): Promise<HumanInteractionRecord[]> {
    return projectEvents(await this.#events(client, namespaceId, workflowId))
  }

  async list(
    namespaceId: string,
    storageId: string,
    options?: WorkflowHumanInteractionListOptions
  ): Promise<HumanInteractionRecord[]> {
    const records = await this.#project(this.#client, namespaceId, storageId)
    return records
      .filter((record) => !options?.openOnly || record.status === 'open')
      .sort((left, right) => {
        const chronology = left.openedAt.localeCompare(right.openedAt)
        return chronology !== 0 ? chronology : left.interactionId.localeCompare(right.interactionId)
      })
  }

  async events(namespaceId: string, storageId: string): Promise<WorkflowHumanInteractionEvent[]> {
    return this.#events(this.#client, namespaceId, storageId)
  }

  async reconcileOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    snapshot: unknown,
    options?: WorkflowHumanInteractionReconcileOptions
  ): Promise<HumanInteractionRecord> {
    const workflowFacts = (options?.workflowFacts ?? []) as WorkflowFact[]
    const outcome = await withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId)
      const candidates = items.filter(
        (item) =>
          item.workflowId === input.workflowId &&
          item.stepId === input.stepId &&
          (item.status === 'opening' || item.status === 'aborted')
      )
      if (candidates.length === 0) throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_NOT_FOUND')
      if (candidates.length !== 1) throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_AMBIGUOUS')
      const opening = candidates[0] as HumanInteractionRecord
      if (opening.semanticHash !== humanInteractionSemanticHash(input))
        throw new WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')
      const view = (snapshot ?? undefined) as RecoverySnapshot | undefined
      const step = view?.instance?.steps?.find((candidate) => candidate.id === opening.stepId)
      if (!Number.isSafeInteger(view?.revision) || !step)
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_SNAPSHOT_INVALID')
      if (step.status === 'ready') {
        if (view?.revision !== opening.expectedRevision)
          throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_REVISION_DIVERGED')
        if (opening.status === 'opening') {
          await insertEvent(
            tx,
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            opening.workflowId,
            {
              event: 'interaction_open_aborted',
              interactionId: opening.interactionId,
              errorCode: 'RECOVERED_OPENING_WITH_READY_STEP',
              recoveredAt: new Date().toISOString(),
            },
            this.#timestamp()
          )
        }
        return { reopen: true as const, interaction: opening }
      }
      if (opening.status !== 'opening') throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_STATE_DIVERGED')
      if (step.status !== 'waiting_human')
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_STATE_DIVERGED')
      const revision = view?.revision as number
      if (revision <= opening.expectedRevision)
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_REVISION_DIVERGED')
      const provesTransition = workflowFacts.some(
        (fact) =>
          fact?.kind === 'transition_accepted' &&
          fact?.revision === revision &&
          fact?.transitionDelta?.steps?.some(
            (change) =>
              change.stepId === opening.stepId &&
              change.status?.from === 'ready' &&
              change.status?.to === 'waiting_human'
          )
      )
      if (!provesTransition) throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_TRANSITION_UNPROVEN')
      const opened: HumanInteractionRecord = { ...opening, status: 'open', revision }
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        opening.workflowId,
        {
          event: 'interaction_opened',
          interaction: { ...opening, revision },
          revision,
          recovery: 'authoritative-workflow-transition',
        },
        this.#timestamp()
      )
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        opening.workflowId,
        opened,
        this.#timestamp()
      )
      return { reopen: false as const, interaction: opened }
    })
    if (outcome.reopen)
      throw new WorkflowHumanInteractionRepositoryError('INTERACTION_RECOVERY_NOT_FOUND', { namespaceId, storageId })
    return outcome.interaction
  }

  async recordOpen(
    namespaceId: string,
    storageId: string,
    input: HumanInteractionInput,
    options?: WorkflowHumanInteractionOpenOptions
  ): Promise<WorkflowHumanInteractionRecordResult> {
    const transition = options?.transition
    if (!transition)
      throw new WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_TRANSITION_REQUIRED', {
        namespaceId,
        storageId,
      })
    const normalized = validateHumanInteractionOpenInput(input)
    if (!normalized) throw new WorkflowHumanInteractionError('INVALID_INTERACTION')
    const hash = humanInteractionSemanticHash(normalized)
    return withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId)
      const prior = items.find((item) => item.idempotencyKey === normalized.idempotencyKey)
      if (prior) {
        if (prior.semanticHash !== hash) throw new WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')
        if (prior.status === 'open') return { created: false, idempotent: true, interaction: prior }
        throw new WorkflowHumanInteractionError('INTERACTION_OPEN_INDETERMINATE')
      }
      if (
        items.some(
          (item) =>
            item.workflowId === normalized.workflowId &&
            item.stepId === normalized.stepId &&
            (item.status === 'opening' || item.status === 'open')
        )
      )
        throw new WorkflowHumanInteractionError('INTERACTION_ALREADY_OPEN')

      const interaction: HumanInteractionRecord = {
        interactionId: normalized.interactionId ?? randomUUID(),
        workflowId: normalized.workflowId,
        stepId: normalized.stepId,
        expectedRevision: normalized.expectedRevision,
        kind: normalized.kind,
        prompt: normalized.prompt,
        actions: normalized.actions,
        idempotencyKey: normalized.idempotencyKey,
        semanticHash: hash,
        openedAt: new Date().toISOString(),
      }
      if (normalized.interactionType !== undefined) interaction.interactionType = normalized.interactionType
      if (normalized.reasonCode !== undefined) interaction.reasonCode = normalized.reasonCode

      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        { event: 'interaction_opening', interaction },
        this.#timestamp()
      )
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        interaction,
        this.#timestamp()
      )

      const result = (await transition(interaction)) as OpenTransitionOutcome | undefined
      if (!result?.ok) {
        const error = new WorkflowHumanInteractionError(result?.error?.code ?? 'INVALID_INTERACTION_TRANSACTION')
        if (result?.decision !== undefined) error.decision = result.decision
        throw error
      }
      const revision = result.snapshot?.revision
      const opened: HumanInteractionRecord = { ...interaction, status: 'open' }
      if (typeof revision === 'number') opened.revision = revision
      const openedEvent: WorkflowHumanInteractionEvent = {
        event: 'interaction_opened',
        interaction: { ...interaction, ...(typeof revision === 'number' ? { revision } : {}) },
      }
      if (typeof revision === 'number') openedEvent.revision = revision
      if (result.requestId !== undefined) openedEvent.transitionRequestId = result.requestId
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        openedEvent,
        this.#timestamp()
      )
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        opened,
        this.#timestamp()
      )
      await insertOutboxEvent(tx, this.#organizationId, this.#workstreamId, 'human_interaction.opened', {
        namespaceId,
        workflowId: normalized.workflowId,
        interactionId: interaction.interactionId,
        revision,
        interaction: opened,
      })
      const idempotent = Boolean(result.idempotent)
      return { created: !idempotent, idempotent, interaction: opened }
    })
  }

  async recordTransition(
    namespaceId: string,
    storageId: string,
    interactionId: string,
    reply: HumanInteractionReply,
    actorId: string,
    evidenceId: string,
    transitionRequestId: string,
    options?: WorkflowHumanInteractionTransitionOptions
  ): Promise<HumanInteractionRecord> {
    const action = options?.action
    if (!action)
      throw new WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_ACTION_REQUIRED', {
        namespaceId,
        storageId,
        interactionId,
      })
    void reply
    void actorId
    void evidenceId
    void transitionRequestId
    return withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId)
      const interaction = items.find((item) => item.interactionId === interactionId)
      if (!interaction) throw new WorkflowHumanInteractionError('INTERACTION_NOT_FOUND')
      if (interaction.status !== 'open') throw new WorkflowHumanInteractionError('INTERACTION_CLOSED')

      const result = (await action(interaction)) as ReplyActionOutcome | undefined
      if (!result?.transition?.ok) throw new WorkflowHumanInteractionError('INVALID_INTERACTION_TRANSACTION')
      const revision = result.transition.snapshot?.revision
      const replied: HumanInteractionRecord = { ...interaction, status: 'replied' }
      if (result.reply !== undefined) replied.reply = result.reply
      if (result.actorId !== undefined) replied.actorId = result.actorId
      if (result.evidenceId !== undefined) replied.evidenceId = result.evidenceId
      if (typeof revision === 'number') replied.revision = revision

      const transitionedEvent: WorkflowHumanInteractionEvent = {
        event: 'interaction_transitioned',
        interactionId,
        expectedRevision: interaction.expectedRevision,
        repliedAt: new Date().toISOString(),
      }
      if (result.reply !== undefined) transitionedEvent.reply = result.reply
      if (result.actorId !== undefined) transitionedEvent.actorId = result.actorId
      if (result.evidenceId !== undefined) transitionedEvent.evidenceId = result.evidenceId
      if (result.transition.requestId !== undefined) transitionedEvent.transitionRequestId = result.transition.requestId
      if (typeof revision === 'number') transitionedEvent.revision = revision

      if (result.evidence)
        await insertEvidenceRecord(tx, this.#organizationId, this.#workstreamId, result.evidence as WorkflowEvidence)
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        interaction.workflowId,
        transitionedEvent,
        this.#timestamp()
      )
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        interaction.workflowId,
        replied,
        this.#timestamp()
      )
      await insertOutboxEvent(tx, this.#organizationId, this.#workstreamId, 'human_interaction.transitioned', {
        namespaceId,
        workflowId: interaction.workflowId,
        interactionId,
        revision,
        reply: result.reply,
        evidenceId: result.evidenceId,
        transitionRequestId: result.transition.requestId,
      })
      return replied
    })
  }
}

/** Wires a SQL human-interaction repository around a database client. */
export function createSqlWorkflowHumanInteractionRepository(
  client: SqlClient,
  options: SqlWorkflowHumanInteractionRepositoryOptions = {}
): SqlWorkflowHumanInteractionRepository {
  return new SqlWorkflowHumanInteractionRepository(client, options)
}

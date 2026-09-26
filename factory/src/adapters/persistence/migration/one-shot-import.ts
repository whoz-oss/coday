/**
 * One-shot filesystem → PostgreSQL import for the shared multi-user Factory.
 *
 * Milestone B4-T1. The runtime server keeps writing to the filesystem; this
 * module is an *offline migration utility* that copies every aggregate of the
 * nine migrated persistence contexts into PostgreSQL using the SQL adapters'
 * tables, then verifies fidelity (count equality + canonical hash equality).
 *
 * Design notes:
 *  - Aggregates are read through the filesystem repositories/adapters wired
 *    against the source `dataRoot`, exactly like the runtime does.
 *  - Writes go through the SQL adapters' tables inside `withTransaction`, using
 *    `INSERT … ON CONFLICT (pk) DO UPDATE` so re-running the import against an
 *    already populated database converges instead of duplicating rows or raising
 *    unique-constraint violations (idempotence).
 *  - The verification step compares, per context, the aggregate count and the
 *    canonical hash (`computeCanonicalHash` from the storage kernel) of every
 *    aggregate keyed by its identity, and returns a structured report.
 *
 * This module never touches the current runtime writers: it is read-only on the
 * filesystem and only ever issues its own idempotent upserts on the SQL side.
 */

import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'

import { computeCanonicalHash, readJsonLines } from '../storage-kernel.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, parseJsonColumn, type SqlClient } from '../sql/db.js'
import { withTransaction } from '../sql/unit-of-work.js'
import { AgentStepAttemptStore } from '../agent-step-attempt-store.js'
import { AgentStepResultStore } from '../agent-step-result-store.js'
import { FilesystemAgentStepAttemptRepository } from '../filesystem-agent-step-attempt-repository.js'
import { FilesystemAgentStepResultRepository } from '../filesystem-agent-step-result-repository.js'
import { FilesystemWorkflowDefinitionRepository } from '../filesystem-workflow-definition-repository.js'
import { FilesystemWorkflowEvidenceRepository } from '../filesystem-workflow-evidence-repository.js'
import { FilesystemWorkflowHumanInteractionRepository } from '../filesystem-workflow-human-interaction-repository.js'
import { FilesystemWorkflowInstanceRepository } from '../filesystem-workflow-instance-repository.js'
import { FilesystemWorkEnvironmentRepository } from '../filesystem-work-environment-repository.js'
import { DeliveryStore } from '../delivery-store.js'
import { FilesystemDeliveryRepository } from '../filesystem-delivery-repository.js'
import { WorkUnitEnvironmentStore } from '../work-unit-environment-store.js'
import { OracleDefinitionRegistry } from '../../../application/oracle/oracle-definition-registry.js'
import { FilesystemOracleExecutionRepository } from '../filesystem-oracle-execution-repository.js'
import { hashWorkflowDefinition, validateWorkflowDefinition } from '../../../domain/workflow/workflow-definition.js'
import { openedInteractionRevision } from '../../../domain/interaction/workflow-human-interaction.js'

/** Options accepted by {@link runOneShotImport} / {@link verifyImport}. */
export interface OneShotImportOptions {
  /** Filesystem root holding the source aggregates (usually `FACTORY_DATA_ROOT`). */
  dataRoot: string
  /** SQL client (a `pg` pool or the in-memory test client). */
  sqlClient: SqlClient
  organizationId?: string
  workstreamId?: string
  /** Root of the definition catalogue; defaults to `<dataRoot>/definitions`. */
  definitionsRoot?: string
  /** Root of the oracle definition catalogue; defaults to `<dataRoot>/oracles`. */
  oraclesRoot?: string
}

/** One aggregate-level mismatch detected by the verification step. */
export interface ContextDiscrepancy {
  key: string
  reason: 'MISSING_IN_SQL' | 'MISSING_IN_FILESYSTEM' | 'HASH_MISMATCH' | 'COUNT_MISMATCH'
  filesystemHash?: string
  sqlHash?: string
}

/** Verification outcome of a single persistence context. */
export interface ContextVerificationResult {
  context: string
  filesystemCount: number
  sqlCount: number
  ok: boolean
  discrepancies: ContextDiscrepancy[]
}

/** Structured report returned by the import / verification entrypoints. */
export interface VerificationReport {
  ok: boolean
  contexts: Record<string, ContextVerificationResult>
  totalFilesystemAggregates: number
  totalSqlAggregates: number
}

interface ResolvedOptions {
  dataRoot: string
  organizationId: string
  workstreamId: string
  definitionsRoot: string
  oraclesRoot: string
}

/** A single aggregate staged for import. */
interface ImportRecord {
  key: string
  /** Column name → value; JSON columns carry the object to be serialised. */
  columns: Record<string, unknown>
  /** Columns among {@link columns} serialised and cast to `jsonb`. */
  jsonColumns: string[]
  /** Aggregate compared by canonical hash during verification. */
  aggregate: unknown
}

/** Description of one persistence context: filesystem read + SQL read. */
interface ContextPlan {
  context: string
  table: string
  primaryKey: string[]
  /** SQL columns selected when reading the context back for verification. */
  selectColumns: string[]
  /** Whether the SQL read is scoped by `workstream_id`. */
  filterWorkstream: boolean
  /** Loads every aggregate of the context from the filesystem. */
  load(options: ResolvedOptions): Promise<ImportRecord[]>
  /** Rebuilds `{ key, aggregate }` from a SQL row. */
  fromRow(row: Record<string, unknown>): { key: string; aggregate: unknown }
}

// --------------------------------------------------------------------------
// Filesystem helpers
// --------------------------------------------------------------------------

async function listDirectoryNames(path: string): Promise<string[]> {
  try {
    const entries = await readdir(path, { withFileTypes: true })
    return entries
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((left, right) => left.localeCompare(right))
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') return []
    throw error
  }
}

async function readJsonIfExists<T = Record<string, unknown>>(path: string): Promise<T | null> {
  try {
    return JSON.parse(await readFile(path, 'utf8')) as T
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') return null
    throw error
  }
}

// --------------------------------------------------------------------------
// Read-only filesystem store adapters (definition / projection / evidence /
// interaction). The concrete `.mjs` stores are wired at the composition edge in
// the live runtime; during the offline import we express the same storage
// layouts through these minimal structural readers and wrap them with the real
// filesystem repository adapters, so the aggregates are read through the very
// same port implementations the server uses.
// --------------------------------------------------------------------------

/** Read-only `.mjs`-parity definition registry over `<root>/<type>/<version>.json`. */
class FilesystemDefinitionRegistry {
  private loaded: Map<string, Record<string, unknown>> | null = null

  constructor(private readonly root: string) {}

  private async load(): Promise<Map<string, Record<string, unknown>>> {
    if (this.loaded) return this.loaded
    const definitions = new Map<string, Record<string, unknown>>()
    for (const type of await listDirectoryNames(this.root)) {
      const typeRoot = join(this.root, type)
      const files = (await readdir(typeRoot, { withFileTypes: true })).filter(
        (entry) => entry.isFile() && entry.name.endsWith('.json')
      )
      for (const file of files) {
        const parsed = JSON.parse(await readFile(join(typeRoot, file.name), 'utf8'))
        const validated = validateWorkflowDefinition(parsed)
        if (!validated.ok) throw new Error(`INVALID_DEFINITION_FILE:${file.name}`)
        const expectedVersion = file.name.slice(0, -'.json'.length)
        if (validated.definition.workflowType !== type || validated.definition.version !== expectedVersion)
          throw new Error(`DEFINITION_PATH_MISMATCH:${file.name}`)
        const definitionHash = hashWorkflowDefinition(validated.definition)
        definitions.set(
          `${validated.definition.workflowType}@${validated.definition.version}`,
          Object.freeze({ ...validated.definition, definitionHash }) as Record<string, unknown>
        )
      }
    }
    this.loaded = definitions
    return definitions
  }

  async list(): Promise<Record<string, unknown>[]> {
    return [...(await this.load()).values()]
  }

  async get(workflowType: string, version: string): Promise<Record<string, unknown> | null> {
    return (await this.load()).get(`${workflowType}@${version}`) ?? null
  }

  async resolveUnique(workflowType: string): Promise<Record<string, unknown>> {
    const matches = [...(await this.load()).values()].filter((item) => item.workflowType === workflowType)
    if (matches.length === 0) throw new Error('WORKFLOW_DEFINITION_NOT_FOUND')
    return matches[0]
  }
}

/** Read-only projection store over `<dataRoot>/workflows/<ns>/<digest>/projection.json`. */
class FilesystemProjectionReader {
  constructor(private readonly dataRoot: string) {}

  async initialize(): Promise<void> {
    return undefined
  }

  private directory(namespaceId: string, workflowId: string): string {
    const digest = createHash('sha256').update(`${namespaceId}:${workflowId}`, 'utf8').digest('hex')
    return join(this.dataRoot, 'workflows', namespaceId, digest)
  }

  async read(namespaceId: string, workflowId: string): Promise<{ instance: unknown; projection: unknown } | null> {
    const snapshot = await readJsonIfExists<any>(join(this.directory(namespaceId, workflowId), 'projection.json'))
    if (!snapshot) return null
    return { instance: snapshot.instance, projection: snapshot.projection }
  }

  async list(namespaceId: string): Promise<Array<{ instance: unknown; projection: unknown }>> {
    const out: Array<{ instance: unknown; projection: unknown }> = []
    const namespaceRoot = join(this.dataRoot, 'workflows', namespaceId)
    for (const directory of await listDirectoryNames(namespaceRoot)) {
      const snapshot = await readJsonIfExists<any>(join(namespaceRoot, directory, 'projection.json'))
      if (snapshot) out.push({ instance: snapshot.instance, projection: snapshot.projection })
    }
    return out
  }

  async start(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async transition(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async remove(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async restore(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async purge(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
}

/** Read-only evidence journal over `<dataRoot>/workflows/<ns>/<id>/evidence.jsonl`. */
class FilesystemEvidenceReader {
  constructor(private readonly dataRoot: string) {}

  async list(namespaceId: string, storageId: string): Promise<unknown[]> {
    return readJsonLines(join(this.dataRoot, 'workflows', namespaceId, storageId, 'evidence.jsonl'))
  }

  async record(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
}

/** Read-only human-interaction journal + projection. */
class FilesystemInteractionReader {
  constructor(private readonly dataRoot: string) {}

  private path(namespaceId: string, storageId: string): string {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'human-interactions.jsonl')
  }

  async events(namespaceId: string, storageId: string): Promise<any[]> {
    return readJsonLines(join(this.path(namespaceId, storageId)))
  }

  async list(namespaceId: string, storageId: string): Promise<any[]> {
    return projectInteractionEvents(await this.events(namespaceId, storageId))
  }

  async reconcileOpen(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async open(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
  async transact(): Promise<never> {
    throw new Error('IMPORT_READ_ONLY')
  }
}

/** Replays the append-only interaction log into interaction records (store parity). */
function projectInteractionEvents(events: any[]): any[] {
  const projected = new Map<string, any>()
  for (const event of events) {
    if (event.event === 'interaction_opening') {
      const interactionId = event.interaction?.interactionId
      if (!interactionId || projected.has(interactionId)) throw new Error('CORRUPT_INTERACTION_STORAGE')
      projected.set(interactionId, { ...event.interaction, status: 'opening' })
    } else if (event.event === 'interaction_opened') {
      const interactionId = event.interaction?.interactionId
      const current = interactionId ? projected.get(interactionId) : undefined
      const revision = openedInteractionRevision(event)
      if (current?.status === 'opening') {
        const validRevision =
          current.interactionType === 'retry'
            ? revision === current.expectedRevision
            : (revision as number) > current.expectedRevision
        if (!Number.isSafeInteger(revision) || !validRevision) throw new Error('CORRUPT_INTERACTION_STORAGE')
        projected.set(interactionId as string, { ...current, status: 'open', revision })
      } else if (!current) {
        if (!interactionId || !Number.isSafeInteger(revision) || (revision as number) < 1)
          throw new Error('CORRUPT_INTERACTION_STORAGE')
        projected.set(interactionId, { ...event.interaction, status: 'open', revision })
      } else {
        throw new Error('CORRUPT_INTERACTION_STORAGE')
      }
    } else if (event.event === 'interaction_open_aborted') {
      const current = projected.get(event.interactionId)
      if (!current || current.status !== 'opening') throw new Error('CORRUPT_INTERACTION_STORAGE')
      projected.set(event.interactionId, { ...current, status: 'aborted', errorCode: event.errorCode })
    } else if (event.event === 'interaction_transitioned') {
      const current = projected.get(event.interactionId)
      if (!current || current.status !== 'open') throw new Error('CORRUPT_INTERACTION_STORAGE')
      projected.set(event.interactionId, {
        ...current,
        status: 'replied',
        reply: event.reply,
        actorId: event.actorId,
        repliedAt: event.repliedAt,
        evidenceId: event.evidenceId,
        transitionRequestId: event.transitionRequestId,
        revision: event.revision,
      })
    } else {
      throw new Error('CORRUPT_INTERACTION_STORAGE')
    }
  }
  return [...projected.values()].sort(
    (left, right) =>
      String(left.openedAt).localeCompare(String(right.openedAt)) ||
      String(left.interactionId).localeCompare(String(right.interactionId))
  )
}

// --------------------------------------------------------------------------
// Small mapping helpers (mirroring the SQL adapters' column mappings)
// --------------------------------------------------------------------------

const ATTEMPT_DB_STATUS: Readonly<Record<string, string>> = Object.freeze({
  starting: 'running',
  running: 'running',
  succeeded: 'completed',
  failed: 'failed',
  indeterminate: 'timed_out',
  interrupted: 'cancelled',
})

const RESULT_DB_STATUS: Readonly<Record<string, string>> = Object.freeze({
  PASS: 'success',
  FAIL: 'failure',
})

const ENVIRONMENT_DB_STATUS: Readonly<Record<string, string>> = Object.freeze({
  provisioning: 'busy',
  active: 'ready',
  completed: 'busy',
  abandoned: 'busy',
  error: 'busy',
  removed: 'decommissioned',
})

function interactionDbStatus(status: unknown): string {
  return status === 'replied' ? 'answered' : 'waiting'
}

function evidenceSourceOf(record: any): string {
  const source = record?.source
  if (typeof source === 'string') return source
  return source?.kind ?? source?.runtimeId ?? 'unknown'
}

function evidenceProducerOf(record: any): string {
  const source = record?.source
  return source?.agentId ?? source?.actorId ?? 'system'
}

// --------------------------------------------------------------------------
// Context plans
// --------------------------------------------------------------------------

function workflowDefinitionPlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemWorkflowDefinitionRepository(
    new FilesystemDefinitionRegistry(options.definitionsRoot) as never
  )
  return {
    context: 'workflow-definition',
    table: 'workflow_definitions',
    primaryKey: ['organization_id', 'workflow_type', 'version'],
    selectColumns: ['workflow_type', 'version', 'definition_hash', 'definition_json'],
    filterWorkstream: false,
    async load() {
      const list = (await repository.list()) as unknown as Array<Record<string, unknown>>
      return list.map((item) => {
        const { definitionHash, ...definition } = item as any
        return {
          key: `${item.workflowType}@${item.version}`,
          columns: {
            organization_id: options.organizationId,
            workstream_id: null,
            workflow_type: item.workflowType,
            version: item.version,
            definition_hash: definitionHash,
            definition_json: definition,
          },
          jsonColumns: ['definition_json'],
          aggregate: { ...definition, definitionHash },
        }
      })
    },
    fromRow(row) {
      const definition = parseJsonColumn<Record<string, unknown>>(row.definition_json)
      return {
        key: `${row.workflow_type}@${row.version}`,
        aggregate: { ...definition, definitionHash: row.definition_hash },
      }
    },
  }
}

function workflowInstancePlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemWorkflowInstanceRepository(new FilesystemProjectionReader(options.dataRoot) as never)
  return {
    context: 'workflow-instance',
    table: 'workflow_instances',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id'],
    selectColumns: ['namespace_id', 'workflow_id', 'instance_json', 'projection_json'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'workflows'))) {
        const snapshots = (await repository.list(namespaceId)) as unknown as Array<{ workflowId: string }>
        for (const projection of snapshots) {
          const snapshot = (await repository.get(namespaceId, projection.workflowId)) as any
          if (!snapshot) continue
          const createdAt = snapshot.instance?.createdAt ?? new Date(0).toISOString()
          records.push({
            key: `${namespaceId}/${projection.workflowId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              namespace_id: namespaceId,
              workflow_id: projection.workflowId,
              revision: Number.isSafeInteger(snapshot.instance?.revision) ? snapshot.instance.revision : 1,
              status: 'active',
              instance_json: snapshot.instance,
              projection_json: snapshot.projection,
              creation_command_hash: snapshot.instance?.creationCommandHash ?? null,
              created_at: createdAt,
              updated_at: createdAt,
            },
            jsonColumns: ['instance_json', 'projection_json'],
            aggregate: { instance: snapshot.instance, projection: snapshot.projection },
          })
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}`,
        aggregate: {
          instance: parseJsonColumn(row.instance_json),
          projection: parseJsonColumn(row.projection_json),
        },
      }
    },
  }
}

function workflowEvidencePlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemWorkflowEvidenceRepository(new FilesystemEvidenceReader(options.dataRoot) as never)
  return {
    context: 'workflow-evidence',
    table: 'workflow_evidence',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'evidence_id'],
    selectColumns: ['namespace_id', 'workflow_id', 'evidence_id', 'payload'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'workflows'))) {
        for (const storageId of await listDirectoryNames(join(options.dataRoot, 'workflows', namespaceId))) {
          const list = (await repository.list(namespaceId, storageId)) as unknown as any[]
          for (const record of list) {
            records.push({
              key: `${namespaceId}/${record.workflowId}/${record.evidenceId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: record.workflowId,
                evidence_id: record.evidenceId,
                evidence_type: record.kind ?? 'unknown',
                source: evidenceSourceOf(record),
                producer: evidenceProducerOf(record),
                payload: record,
                created_at: record.observedAt ?? new Date(0).toISOString(),
              },
              jsonColumns: ['payload'],
              aggregate: record,
            })
          }
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.evidence_id}`,
        aggregate: parseJsonColumn(row.payload),
      }
    },
  }
}

function workflowHumanInteractionPlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemWorkflowHumanInteractionRepository(
    new FilesystemInteractionReader(options.dataRoot) as never
  )
  return {
    context: 'workflow-human-interaction',
    table: 'human_interactions',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'interaction_id'],
    selectColumns: ['namespace_id', 'workflow_id', 'interaction_id', 'payload'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'workflows'))) {
        for (const storageId of await listDirectoryNames(join(options.dataRoot, 'workflows', namespaceId))) {
          const list = (await repository.list(namespaceId, storageId)) as unknown as any[]
          for (const record of list) {
            records.push({
              key: `${namespaceId}/${record.workflowId}/${record.interactionId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: record.workflowId,
                interaction_id: record.interactionId,
                interaction_type: record.interactionType ?? record.kind ?? 'unknown',
                status: interactionDbStatus(record.status),
                revision: Number.isSafeInteger(record.revision) && record.revision >= 1 ? record.revision : 1,
                payload: record,
              },
              jsonColumns: ['payload'],
              aggregate: record,
            })
          }
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.interaction_id}`,
        aggregate: parseJsonColumn(row.payload),
      }
    },
  }
}

function agentStepAttemptPlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemAgentStepAttemptRepository(new AgentStepAttemptStore(options.dataRoot))
  return {
    context: 'agent-step-attempt',
    table: 'agent_step_attempts',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'step_id', 'attempt_id'],
    selectColumns: ['namespace_id', 'workflow_id', 'step_id', 'attempt_id', 'payload'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'workflows'))) {
        for (const storageId of await listDirectoryNames(join(options.dataRoot, 'workflows', namespaceId))) {
          const events = (await repository.list(namespaceId, storageId)) as unknown as any[]
          if (events.length === 0) continue
          const latest = new Map<string, { attempt: any; revision: number }>()
          for (const attempt of events) {
            const previous = latest.get(attempt.attemptId)
            latest.set(attempt.attemptId, { attempt, revision: (previous?.revision ?? 0) + 1 })
          }
          for (const { attempt, revision } of latest.values()) {
            records.push({
              key: `${namespaceId}/${attempt.workflowId}/${storageId}/${attempt.attemptId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: attempt.workflowId,
                step_id: storageId,
                attempt_id: attempt.attemptId,
                agent_id: attempt.agentName ?? 'unknown',
                status: ATTEMPT_DB_STATUS[attempt.status] ?? 'running',
                revision,
                idempotency_key: null,
                payload: attempt,
              },
              jsonColumns: ['payload'],
              aggregate: attempt,
            })
          }
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.step_id}/${row.attempt_id}`,
        aggregate: parseJsonColumn(row.payload),
      }
    },
  }
}

function agentStepResultPlan(options: ResolvedOptions): ContextPlan {
  const repository = new FilesystemAgentStepResultRepository(new AgentStepResultStore(options.dataRoot))
  return {
    context: 'agent-step-result',
    table: 'agent_step_results',
    primaryKey: [
      'organization_id',
      'workstream_id',
      'namespace_id',
      'workflow_id',
      'step_id',
      'attempt_id',
      'result_id',
    ],
    selectColumns: ['namespace_id', 'workflow_id', 'step_id', 'result_id', 'payload'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'workflows'))) {
        for (const storageId of await listDirectoryNames(join(options.dataRoot, 'workflows', namespaceId))) {
          const events = (await repository.list(namespaceId, storageId)) as unknown as any[]
          for (const event of events) {
            if (event.type !== 'result-submitted') continue
            records.push({
              key: `${namespaceId}/${event.workflowId}/${storageId}/${event.resultId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: event.workflowId,
                step_id: storageId,
                attempt_id: event.attemptId,
                result_id: event.resultId,
                result_status: RESULT_DB_STATUS[event.status] ?? 'success',
                semantic_signature: event.resultHash ?? null,
                payload: event,
                created_at: event.submittedAt ?? new Date(0).toISOString(),
              },
              jsonColumns: ['payload'],
              aggregate: event,
            })
          }
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.step_id}/${row.result_id}`,
        aggregate: parseJsonColumn(row.payload),
      }
    },
  }
}

function oracleExecutionPlan(options: ResolvedOptions): ContextPlan {
  const registry = new OracleDefinitionRegistry(options.oraclesRoot)
  return {
    context: 'oracle-execution',
    table: 'oracle_executions',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'workflow_id', 'execution_id'],
    selectColumns: ['execution_id', 'payload'],
    filterWorkstream: true,
    async load() {
      try {
        await registry.initialize()
      } catch (error) {
        if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') return []
        throw error
      }
      const repository = new FilesystemOracleExecutionRepository(registry)
      const list = (await repository.list()) as unknown as any[]
      return list.map((definition) => ({
        key: `${definition.id}@${definition.version}`,
        columns: {
          organization_id: options.organizationId,
          workstream_id: options.workstreamId,
          namespace_id: 'oracle-registry',
          workflow_id: definition.id,
          execution_id: `${definition.id}@${definition.version}`,
          oracle_id: definition.id,
          status: 'succeeded',
          revision: 1,
          evidence_id: null,
          artifact_id: null,
          payload: definition,
        },
        jsonColumns: ['payload'],
        aggregate: definition,
      }))
    },
    fromRow(row) {
      return { key: String(row.execution_id), aggregate: parseJsonColumn(row.payload) }
    },
  }
}

function workEnvironmentPlan(options: ResolvedOptions): ContextPlan {
  const store = new WorkUnitEnvironmentStore(options.dataRoot)
  return {
    context: 'work-environment',
    table: 'work_environments',
    primaryKey: ['organization_id', 'workstream_id', 'environment_id'],
    selectColumns: ['environment_id', 'payload'],
    filterWorkstream: true,
    async load() {
      await store.initialize()
      const repository = new FilesystemWorkEnvironmentRepository(store)
      const records: ImportRecord[] = []
      for (const namespaceId of await listDirectoryNames(join(options.dataRoot, 'environments'))) {
        const snapshots = (await repository.list(namespaceId)) as unknown as any[]
        for (const snapshot of snapshots) {
          const environment = snapshot.environment
          records.push({
            key: `${environment.namespaceId}/${environment.environmentId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              environment_id: environment.environmentId,
              env_type: 'work-unit-environment',
              status: ENVIRONMENT_DB_STATUS[environment.lifecycleState] ?? 'busy',
              revision: snapshot.revision,
              payload: environment,
            },
            jsonColumns: ['payload'],
            aggregate: environment,
          })
        }
      }
      return records
    },
    fromRow(row) {
      const environment = parseJsonColumn<any>(row.payload)
      return { key: `${environment.namespaceId}/${row.environment_id}`, aggregate: environment }
    },
  }
}

function deliveryPlan(options: ResolvedOptions): ContextPlan {
  const store = new DeliveryStore(options.dataRoot)
  const repository = new FilesystemDeliveryRepository(store)
  return {
    context: 'delivery',
    table: 'deliveries',
    primaryKey: ['organization_id', 'workstream_id', 'namespace_id', 'delivery_id'],
    selectColumns: ['namespace_id', 'delivery_id', 'payload'],
    filterWorkstream: true,
    async load() {
      const records: ImportRecord[] = []
      const deliveriesRoot = join(options.dataRoot, 'deliveries')
      for (const namespaceId of await listDirectoryNames(deliveriesRoot)) {
        for (const directory of await listDirectoryNames(join(deliveriesRoot, namespaceId))) {
          const raw = await readJsonIfExists<any>(join(deliveriesRoot, namespaceId, directory, 'delivery.json'))
          if (!raw?.deliveryId) continue
          const snapshot = (await repository.read(namespaceId, raw.deliveryId)) as any
          if (!snapshot) continue
          records.push({
            key: `${namespaceId}/${snapshot.deliveryId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              namespace_id: namespaceId,
              delivery_id: snapshot.deliveryId,
              revision: Number.isSafeInteger(snapshot.revision) ? snapshot.revision : 1,
              stage: snapshot.stage ?? 'unknown',
              payload: snapshot,
            },
            jsonColumns: ['payload'],
            aggregate: snapshot,
          })
        }
      }
      return records
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.delivery_id}`,
        aggregate: parseJsonColumn(row.payload),
      }
    },
  }
}

/** Builds every context plan bound to the resolved options. */
function buildPlans(options: ResolvedOptions): ContextPlan[] {
  return [
    workflowDefinitionPlan(options),
    workflowInstancePlan(options),
    workflowEvidencePlan(options),
    workflowHumanInteractionPlan(options),
    agentStepAttemptPlan(options),
    agentStepResultPlan(options),
    oracleExecutionPlan(options),
    workEnvironmentPlan(options),
    deliveryPlan(options),
  ]
}

function resolveOptions(options: OneShotImportOptions): ResolvedOptions {
  const dataRoot = options.dataRoot
  if (typeof dataRoot !== 'string' || dataRoot.length === 0) throw new Error('ONE_SHOT_IMPORT_INVALID_DATA_ROOT')
  return {
    dataRoot,
    organizationId: options.organizationId ?? DEFAULT_ORGANIZATION_ID,
    workstreamId: options.workstreamId ?? DEFAULT_WORKSTREAM_ID,
    definitionsRoot: options.definitionsRoot ?? join(dataRoot, 'definitions'),
    oraclesRoot: options.oraclesRoot ?? join(dataRoot, 'oracles'),
  }
}

// --------------------------------------------------------------------------
// SQL write / read primitives
// --------------------------------------------------------------------------

/** Idempotent upsert of one aggregate into its context table. */
async function writeRecord(tx: SqlClient, plan: ContextPlan, record: ImportRecord): Promise<void> {
  const columns = Object.keys(record.columns)
  const placeholders = columns.map((column, index) =>
    record.jsonColumns.includes(column) ? `$${index + 1}::jsonb` : `$${index + 1}`
  )
  const params = columns.map((column) =>
    record.jsonColumns.includes(column) ? JSON.stringify(record.columns[column]) : record.columns[column]
  )
  const updates = columns.filter((column) => !plan.primaryKey.includes(column))
  const conflict = updates.length
    ? `ON CONFLICT (${plan.primaryKey.join(', ')}) DO UPDATE SET ${updates
        .map((column) => `${column} = EXCLUDED.${column}`)
        .join(', ')}`
    : 'ON CONFLICT DO NOTHING'
  await tx.query(
    `INSERT INTO ${plan.table} (${columns.join(', ')}) VALUES (${placeholders.join(', ')}) ${conflict}`,
    params
  )
}

async function readSql(
  client: SqlClient,
  plan: ContextPlan,
  options: ResolvedOptions
): Promise<Array<{ key: string; aggregate: unknown }>> {
  const where = plan.filterWorkstream
    ? 'WHERE organization_id = $1 AND workstream_id = $2'
    : 'WHERE organization_id = $1'
  const params = plan.filterWorkstream ? [options.organizationId, options.workstreamId] : [options.organizationId]
  const { rows } = await client.query<Record<string, unknown>>(
    `SELECT ${plan.selectColumns.join(', ')} FROM ${plan.table} ${where}`,
    params
  )
  return rows.map((row) => plan.fromRow(row))
}

// --------------------------------------------------------------------------
// Public API
// --------------------------------------------------------------------------

/**
 * Imports every filesystem aggregate into PostgreSQL (idempotently) and returns
 * a {@link VerificationReport} comparing the two sides.
 */
export async function runOneShotImport(options: OneShotImportOptions): Promise<VerificationReport> {
  const resolved = resolveOptions(options)
  for (const plan of buildPlans(resolved)) {
    const records = await plan.load(resolved)
    if (records.length === 0) continue
    await withTransaction(options.sqlClient, async (tx) => {
      for (const record of records) await writeRecord(tx, plan, record)
    })
  }
  return verifyImport(options)
}

/**
 * Compares the filesystem aggregates with the SQL rows, per context, by count
 * and canonical aggregate hash. Performs no writes.
 */
export async function verifyImport(options: OneShotImportOptions): Promise<VerificationReport> {
  const resolved = resolveOptions(options)
  const contexts: Record<string, ContextVerificationResult> = {}
  let totalFilesystemAggregates = 0
  let totalSqlAggregates = 0
  let ok = true

  for (const plan of buildPlans(resolved)) {
    const filesystem = await plan.load(resolved)
    const sql = await readSql(options.sqlClient, plan, resolved)

    const filesystemByKey = new Map(filesystem.map((record) => [record.key, record.aggregate]))
    const sqlByKey = new Map(sql.map((record) => [record.key, record.aggregate]))
    const discrepancies: ContextDiscrepancy[] = []

    for (const [key, aggregate] of filesystemByKey) {
      const filesystemHash = computeCanonicalHash(aggregate)
      if (!sqlByKey.has(key)) {
        discrepancies.push({ key, reason: 'MISSING_IN_SQL', filesystemHash })
        continue
      }
      const sqlHash = computeCanonicalHash(sqlByKey.get(key))
      if (sqlHash !== filesystemHash) discrepancies.push({ key, reason: 'HASH_MISMATCH', filesystemHash, sqlHash })
    }
    for (const [key, aggregate] of sqlByKey) {
      if (!filesystemByKey.has(key))
        discrepancies.push({ key, reason: 'MISSING_IN_FILESYSTEM', sqlHash: computeCanonicalHash(aggregate) })
    }
    if (filesystem.length !== sql.length && discrepancies.length === 0)
      discrepancies.push({
        key: plan.context,
        reason: 'COUNT_MISMATCH',
        filesystemHash: `count:${filesystem.length}`,
        sqlHash: `count:${sql.length}`,
      })

    const contextOk = filesystem.length === sql.length && discrepancies.length === 0
    contexts[plan.context] = {
      context: plan.context,
      filesystemCount: filesystem.length,
      sqlCount: sql.length,
      ok: contextOk,
      discrepancies,
    }
    totalFilesystemAggregates += filesystem.length
    totalSqlAggregates += sql.length
    if (!contextOk) ok = false
  }

  return { ok, contexts, totalFilesystemAggregates, totalSqlAggregates }
}

/** Deterministic sha256 of the report core, useful for dry-run comparisons. */
export function hashVerificationReport(report: VerificationReport): string {
  return createHash('sha256').update(JSON.stringify(report)).digest('hex')
}

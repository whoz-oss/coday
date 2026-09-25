import { randomBytes, randomUUID } from 'node:crypto'
import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { appendDurableJson, createKeyedLock, readJsonLines, type KeyedLock } from './storage-kernel.js'
import {
  agentStepAttemptKey,
  canonicalAgentStepResultJson,
  isSafeAgentStepResultId,
  safeEqual,
  sha256,
  validateAgentStepResultBusiness,
  type AgentStepResultBusiness,
  type AgentStepResultCapabilityIdentity,
  type AgentStepResultCapabilityIssued,
  type AgentStepResultLedgerEvent,
  type AgentStepResultObservedIdentity,
  type AgentStepResultSubmitted,
} from '../../domain/agent-attempt/agent-step-result.js'
import type {
  AgentStepResultIssueResult,
  AgentStepResultSubmitResult,
} from '../../ports/persistence/agent-step-result-repository.js'

/** Injection points of the result store. */
export interface AgentStepResultStoreOptions {
  clock?: () => Date
  ttlMs?: number
}

interface CapabilityEntry {
  namespaceId: string
  storageId: string
  event: AgentStepResultCapabilityIssued
  result: AgentStepResultSubmitted | null
}

const IDENTITY_FIELDS = ['attemptId', 'workflowId', 'stepId', 'namespaceId', 'caseId', 'agentName'] as const
const CAPABILITY_MATCH_FIELDS = [...IDENTITY_FIELDS, 'briefHash'] as const
const BRIEF_HASH = /^sha256:[0-9a-f]{64}$/

/**
 * Append-only, lock-serialized journal of structured agent step results, with
 * an in-memory capability/attempt index recovered by scanning the journals.
 *
 * The durable append, missing-file read and in-process keyed serialization are
 * the shared storage-kernel primitives; this adapter owns the ledger event
 * vocabulary, capability issuance/recovery and the submission state machine.
 */
export class AgentStepResultStore {
  private readonly clock: () => Date
  private readonly ttlMs: number
  private readonly locks: KeyedLock
  private readonly capabilityIndex = new Map<string, CapabilityEntry>()
  private readonly attemptIndex = new Map<string, CapabilityEntry>()
  private indexLoaded = false
  private initializePromise: Promise<void> | null = null

  constructor(
    readonly dataRoot: string,
    { clock = () => new Date(), ttlMs = 15 * 60 * 1000 }: AgentStepResultStoreOptions = {}
  ) {
    this.clock = clock
    this.ttlMs = ttlMs
    this.locks = createKeyedLock()
  }

  path(namespaceId: string, storageId: string): string {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'agent-step-results.jsonl')
  }

  async list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]> {
    return readJsonLines<AgentStepResultLedgerEvent>(this.path(namespaceId, storageId))
  }

  indexLedger(namespaceId: string, storageId: string, events: AgentStepResultLedgerEvent[]): void {
    for (const event of events) {
      if (event.type === 'capability-issued') {
        const entry: CapabilityEntry = { namespaceId, storageId, event, result: null }
        this.capabilityIndex.set(event.tokenHash, entry)
        this.attemptIndex.set(agentStepAttemptKey(namespaceId, storageId, event.attemptId), entry)
      } else if (event.type === 'result-submitted') {
        const entry = this.attemptIndex.get(agentStepAttemptKey(namespaceId, storageId, event.attemptId))
        if (entry && !entry.result) entry.result = event
      }
    }
  }

  async initialize(): Promise<void> {
    if (this.indexLoaded) return
    if (this.initializePromise) return this.initializePromise
    const promise = (async () => {
      const workflows = join(this.dataRoot, 'workflows')
      let namespaces
      try {
        namespaces = await readdir(workflows, { withFileTypes: true })
      } catch (error) {
        if ((error as { code?: string } | null)?.code === 'ENOENT') {
          this.indexLoaded = true
          return
        }
        throw error
      }
      for (const ns of namespaces.filter((entry) => entry.isDirectory())) {
        let stores
        try {
          stores = await readdir(join(workflows, ns.name), { withFileTypes: true })
        } catch {
          continue
        }
        for (const storage of stores.filter((entry) => entry.isDirectory()))
          this.indexLedger(ns.name, storage.name, await this.list(ns.name, storage.name))
      }
      this.indexLoaded = true
    })().finally(() => {
      this.initializePromise = null
    })
    this.initializePromise = promise
    return promise
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
    await this.initialize()
    const key = agentStepAttemptKey(namespaceId, storageId, identity.attemptId)
    return this.locks.run(key, async () => {
      const existing = this.attemptIndex.get(key)
      if (existing) {
        const same = CAPABILITY_MATCH_FIELDS.every((field) => existing.event[field] === identity[field])
        throw new Error(same ? 'RESULT_CAPABILITY_ALREADY_ISSUED' : 'RESULT_CAPABILITY_IDENTITY_CONFLICT')
      }
      const token = randomBytes(32).toString('base64url')
      const now = this.clock()
      const record: AgentStepResultCapabilityIssued = {
        type: 'capability-issued',
        capabilityId: randomUUID(),
        tokenHash: sha256(token),
        ...identity,
        issuedAt: now.toISOString(),
        expiresAt: new Date(now.getTime() + this.ttlMs).toISOString(),
        submissionBudget: 1,
      }
      const entry: CapabilityEntry = { namespaceId, storageId, event: record, result: null }
      await appendDurableJson(this.path(namespaceId, storageId), record, { ensureDirectory: true })
      this.capabilityIndex.set(record.tokenHash, entry)
      this.attemptIndex.set(key, entry)
      return { token, expiresAt: record.expiresAt }
    })
  }

  async resolve(token: string): Promise<CapabilityEntry | null> {
    if (typeof token !== 'string' || token.length < 32 || token.length > 256) return null
    await this.initialize()
    const digest = sha256(token)
    const located = this.capabilityIndex.get(digest)
    return located && safeEqual(located.event.tokenHash, digest) ? located : null
  }

  async submit(
    token: string,
    business: unknown,
    observed: Partial<AgentStepResultObservedIdentity> = {}
  ): Promise<AgentStepResultSubmitResult> {
    if (!validateAgentStepResultBusiness(business)) return { ok: false, code: 'RESULT_SCHEMA_INVALID' }
    const located = await this.resolve(token)
    if (!located) return { ok: false, code: 'RESULT_CAPABILITY_INVALID' }
    const { namespaceId, storageId, event: issued } = located
    if (
      observed.attemptId !== issued.attemptId ||
      observed.caseId !== issued.caseId ||
      observed.agentName !== issued.agentName
    )
      return { ok: false, code: 'RESULT_IDENTITY_MISMATCH' }
    const resultHash = sha256(canonicalAgentStepResultJson(business))
    const key = agentStepAttemptKey(namespaceId, storageId, issued.attemptId)
    return this.locks.run(key, async () => {
      const entry = this.attemptIndex.get(key) ?? located
      const existing = entry.result
      if (existing)
        return existing.resultHash === resultHash
          ? { ok: true, idempotent: true, result: existing }
          : { ok: false, code: 'RESULT_SEMANTIC_COLLISION' }
      if (this.clock().getTime() > Date.parse(issued.expiresAt)) return { ok: false, code: 'RESULT_CAPABILITY_EXPIRED' }
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
        submittedAt: this.clock().toISOString(),
        resultHash,
      }
      await appendDurableJson(this.path(namespaceId, storageId), result, { ensureDirectory: true })
      entry.result = result
      this.attemptIndex.set(key, entry)
      return { ok: true, idempotent: false, result }
    })
  }

  async getByAttempt(
    namespaceId: string,
    storageId: string,
    attemptId: string
  ): Promise<AgentStepResultSubmitted | null> {
    await this.initialize()
    return this.attemptIndex.get(agentStepAttemptKey(namespaceId, storageId, attemptId))?.result ?? null
  }
}

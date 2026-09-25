/**
 * File-backed delivery evidence store.
 *
 * Evidence facts are appended to a single `evidence.jsonl` journal per
 * delivery, addressed by a digest of `namespaceId:deliveryId` so raw ids never
 * appear as path segments. Idempotent recording is bound by a scope hash
 * (controlling execution plus idempotency key) and a semantic hash (the exact
 * fact content).
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-evidence-store.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 *
 * Persistence shape (`evidence.jsonl` field names and idempotency hashes) is
 * part of the on-disk contract and must not change.
 */

import { createHash, randomUUID } from 'node:crypto'
import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const HASH = /^sha256:[0-9a-f]{64}$/
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const ALLOWED = new Set([
  'deliveryId',
  'workflowId',
  'environmentHash',
  'caseId',
  'runtimeId',
  'headCommit',
  'kind',
  'outcome',
  'oracleId',
  'facts',
  'idempotencyKey',
])

/**
 * Canonical JSON shape: object keys sorted recursively, arrays preserved in
 * order. Two facts that differ only by key order hash identically.
 */
const canonical = (value: unknown): unknown =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonical((value as Record<string, unknown>)[key])])
        )
      : value
const digest = (value: unknown): string =>
  createHash('sha256')
    .update(JSON.stringify(canonical(value)))
    .digest('hex')
async function append(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { mode: 0o600 })
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}

/** Result of validating a raw delivery evidence fact. */
export type DeliveryEvidenceValidation =
  | { ok: true; value: Record<string, unknown> }
  | { ok: false; error: { code: 'INVALID_DELIVERY_EVIDENCE' } }

/** Validates a raw delivery evidence fact, returning its canonical form. */
export function validateDeliveryEvidence(input: unknown): DeliveryEvidenceValidation {
  if (
    !input ||
    typeof input !== 'object' ||
    Array.isArray(input) ||
    Object.keys(input).some((key) => !ALLOWED.has(key))
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  const candidate = input as Record<string, unknown>
  if (
    ![
      candidate.deliveryId,
      candidate.workflowId,
      candidate.runtimeId,
      candidate.kind,
      candidate.outcome,
      candidate.idempotencyKey,
    ].every((value) => SAFE.test((value ?? '') as string)) ||
    !HASH.test((candidate.environmentHash ?? '') as string) ||
    !UUID.test((candidate.caseId ?? '') as string) ||
    !SHA.test((candidate.headCommit ?? '') as string)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  if (
    !candidate.facts ||
    typeof candidate.facts !== 'object' ||
    Array.isArray(candidate.facts) ||
    Object.keys(candidate.facts).length > 32 ||
    JSON.stringify(candidate.facts).length > 4096
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  return { ok: true, value: canonical(candidate) as Record<string, unknown> }
}

/** One persisted evidence record. */
export type DeliveryEvidenceRecord = Record<string, unknown>

/** Result of recording an evidence fact. */
export type DeliveryEvidenceRecordResult =
  | { ok: true; created: boolean; evidence: DeliveryEvidenceRecord }
  | { ok: false; error: { code: string } }

export class DeliveryEvidenceStore {
  private readonly dataRoot: string
  private readonly locks: Map<string, Promise<unknown>>

  constructor(dataRoot: string) {
    if (!dataRoot || typeof dataRoot !== 'string') throw new Error('INVALID_DATA_ROOT')
    this.dataRoot = dataRoot
    this.locks = new Map()
  }
  path(namespaceId: string, deliveryId: string): string {
    // Validate inputs before constructing paths to prevent path traversal.
    if (!UUID.test(namespaceId ?? ''))
      throw Object.assign(new Error('INVALID_NAMESPACE_ID'), { code: 'INVALID_NAMESPACE_ID' })
    if (!SAFE.test(deliveryId ?? ''))
      throw Object.assign(new Error('INVALID_DELIVERY_ID'), { code: 'INVALID_DELIVERY_ID' })
    return join(
      this.dataRoot,
      'deliveries',
      namespaceId,
      createHash('sha256').update(`${namespaceId}:${deliveryId}`).digest('hex'),
      'evidence.jsonl'
    )
  }
  async list(namespaceId: string, deliveryId: string): Promise<DeliveryEvidenceRecord[]> {
    try {
      return (await readFile(this.path(namespaceId, deliveryId), 'utf8'))
        .split('\n')
        .filter(Boolean)
        .map((line) => JSON.parse(line) as DeliveryEvidenceRecord)
    } catch (error) {
      if ((error as { code?: string })?.code === 'ENOENT') return []
      throw error
    }
  }
  private _locked<T>(key: string, action: () => Promise<T>): Promise<T> {
    const prior = this.locks.get(key) ?? Promise.resolve(),
      operation = prior.then(action),
      tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
  async record(
    namespaceId: string,
    input: unknown,
    source: Record<string, unknown>
  ): Promise<DeliveryEvidenceRecordResult> {
    const validation = validateDeliveryEvidence(input)
    if (!validation.ok) return validation
    const value = validation.value
    return this._locked(`${namespaceId}\0${value.deliveryId}`, async () => {
      const existing = await this.list(namespaceId, value.deliveryId as string),
        scopeHash = digest({
          namespaceId,
          deliveryId: value.deliveryId,
          workflowId: value.workflowId,
          caseId: value.caseId,
          runtimeId: value.runtimeId,
          idempotencyKey: value.idempotencyKey,
        }),
        semanticHash = digest({ ...value, idempotencyKey: undefined })
      const prior = existing.find((item) => (item.idempotency as Record<string, unknown>).scopeHash === scopeHash)
      if (prior)
        return (prior.idempotency as Record<string, unknown>).semanticHash === semanticHash
          ? { ok: true, created: false, evidence: prior }
          : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      const evidence = {
        evidenceId: randomUUID(),
        namespaceId,
        ...value,
        source: { ...source },
        observedAt: new Date().toISOString(),
        idempotency: { scopeHash, semanticHash },
      }
      await append(this.path(namespaceId, value.deliveryId as string), evidence)
      return { ok: true, created: true, evidence }
    })
  }
}

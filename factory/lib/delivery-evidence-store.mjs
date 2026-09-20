import { createHash, randomUUID } from 'node:crypto'
import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const HASH = /^sha256:[0-9a-f]{64}$/
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const ALLOWED = new Set(['deliveryId', 'workflowId', 'environmentHash', 'caseId', 'runtimeId', 'headCommit', 'kind', 'outcome', 'oracleId', 'facts', 'idempotencyKey'])
const canonical = (value) => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])) : value
const digest = (value) => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex')
async function append(path, value) { await mkdir(dirname(path), { recursive: true }); await appendFile(path, `${JSON.stringify(value)}\n`, { mode: 0o600 }); const handle = await open(path, 'r'); try { await handle.sync() } finally { await handle.close() } }

export function validateDeliveryEvidence(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some((key) => !ALLOWED.has(key))) return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  if (![input.deliveryId, input.workflowId, input.runtimeId, input.kind, input.outcome, input.idempotencyKey].every((value) => SAFE.test(value ?? '')) || !HASH.test(input.environmentHash ?? '') || !UUID.test(input.caseId ?? '') || !SHA.test(input.headCommit ?? '')) return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  if (!input.facts || typeof input.facts !== 'object' || Array.isArray(input.facts) || Object.keys(input.facts).length > 32 || JSON.stringify(input.facts).length > 4096) return { ok: false, error: { code: 'INVALID_DELIVERY_EVIDENCE' } }
  return { ok: true, value: canonical(input) }
}

export class DeliveryEvidenceStore {
  constructor(dataRoot) {
    if (!dataRoot || typeof dataRoot !== 'string') throw new Error('INVALID_DATA_ROOT')
    this.dataRoot = dataRoot; this.locks = new Map()
  }
  path(namespaceId, deliveryId) {
    // Validate inputs before constructing paths to prevent path traversal.
    if (!UUID.test(namespaceId ?? '')) throw Object.assign(new Error('INVALID_NAMESPACE_ID'), { code: 'INVALID_NAMESPACE_ID' })
    if (!SAFE.test(deliveryId ?? '')) throw Object.assign(new Error('INVALID_DELIVERY_ID'), { code: 'INVALID_DELIVERY_ID' })
    return join(this.dataRoot, 'deliveries', namespaceId, createHash('sha256').update(`${namespaceId}:${deliveryId}`).digest('hex'), 'evidence.jsonl')
  }
  async list(namespaceId, deliveryId) { try { return (await readFile(this.path(namespaceId, deliveryId), 'utf8')).split('\n').filter(Boolean).map(JSON.parse) } catch (error) { if (error?.code === 'ENOENT') return []; throw error } }
  _locked(key, action) { const prior = this.locks.get(key) ?? Promise.resolve(), operation = prior.then(action), tail = operation.catch(() => {}); this.locks.set(key, tail); return operation.finally(() => { if (this.locks.get(key) === tail) this.locks.delete(key) }) }
  async record(namespaceId, input, source) { const validation = validateDeliveryEvidence(input); if (!validation.ok) return validation; return this._locked(`${namespaceId}\0${input.deliveryId}`, async () => { const existing = await this.list(namespaceId, input.deliveryId), scopeHash = digest({ namespaceId, deliveryId: input.deliveryId, workflowId: input.workflowId, caseId: input.caseId, runtimeId: input.runtimeId, idempotencyKey: input.idempotencyKey }), semanticHash = digest({ ...input, idempotencyKey: undefined }); const prior = existing.find((item) => item.idempotency.scopeHash === scopeHash); if (prior) return prior.idempotency.semanticHash === semanticHash ? { ok: true, created: false, evidence: prior } : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }; const evidence = { evidenceId: randomUUID(), namespaceId, ...validation.value, source: { ...source }, observedAt: new Date().toISOString(), idempotency: { scopeHash, semanticHash } }; await append(this.path(namespaceId, input.deliveryId), evidence); return { ok: true, created: true, evidence } }) }
}

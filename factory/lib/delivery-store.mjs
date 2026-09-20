import { createHash, randomBytes } from 'node:crypto'
import { appendFile, mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { dirname, isAbsolute, join } from 'node:path'
import { deliveryScopeHash, deliverySemanticHash, evaluateDeliveryPromotion, applyDeliveryPromotion } from './delivery-policy.mjs'
import { normalizeDeliveryOperationRequest, deriveDeliveryOperationIdentity, validateDeliveryOperationRecord, validateDeliveryOperationTransition } from './delivery-operation-definition.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const HASH = /^sha256:[0-9a-f]{64}$/
const canonical = (value) => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).filter((key) => value[key] !== undefined).sort().map((key) => [key, canonical(value[key])])) : value
const hash = (value) => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex')
async function sync(path) { const handle = await open(path, 'r'); try { await handle.sync() } finally { await handle.close() } }
async function atomic(path, value) { await mkdir(dirname(path), { recursive: true }); const temp = `${path}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`; const handle = await open(temp, 'wx', 0o600); try { await handle.writeFile(`${JSON.stringify(value)}\n`); await handle.sync() } finally { await handle.close() }; await rename(temp, path); await sync(dirname(path)) }
async function append(path, value) { await mkdir(dirname(path), { recursive: true }); await appendFile(path, `${JSON.stringify(value)}\n`, { mode: 0o600 }); await sync(path) }
const validSnapshot = (value) => value && value.schemaVersion === '1' && UUID.test(value.namespaceId ?? '') && SAFE.test(value.deliveryId ?? '') && SAFE.test(value.workflowId ?? '') && UUID.test(value.environmentId ?? '') && HASH.test(value.environmentHash ?? '') && UUID.test(value.parentCaseId ?? '') && SAFE.test(value.runtimeId ?? '') && SHA.test(value.baseCommit ?? '') && SHA.test(value.headCommit ?? '') && Number.isSafeInteger(value.revision) && value.revision > 0

export class DeliveryStore {
  constructor(dataRoot, { fault = async () => {} } = {}) { if (!isAbsolute(dataRoot)) throw new Error('INVALID_DATA_ROOT'); this.dataRoot = dataRoot; this.fault = fault; this.locks = new Map() }
  async initialize() { await mkdir(join(this.dataRoot, 'deliveries'), { recursive: true }) }
  paths(namespaceId, deliveryId) { if (!UUID.test(namespaceId ?? '') || !SAFE.test(deliveryId ?? '')) throw new Error('INVALID_DELIVERY_SCOPE'); const directory = join(this.dataRoot, 'deliveries', namespaceId, createHash('sha256').update(`${namespaceId}:${deliveryId}`).digest('hex')); return { directory, snapshot: join(directory, 'delivery.json'), journal: join(directory, 'operations.jsonl'), pending: join(directory, 'pending.json') } }
  _locked(namespaceId, deliveryId, action) { const key = `${namespaceId}\0${deliveryId}`, prior = this.locks.get(key) ?? Promise.resolve(), operation = prior.then(action), tail = operation.catch(() => {}); this.locks.set(key, tail); return operation.finally(() => { if (this.locks.get(key) === tail) this.locks.delete(key) }) }
  async _json(path) { try { return JSON.parse(await readFile(path, 'utf8')) } catch (error) { if (error?.code === 'ENOENT') return null; throw Object.assign(new Error('CORRUPT_DELIVERY_STORAGE'), { code: 'CORRUPT_DELIVERY_STORAGE' }) } }
  async journal(namespaceId, deliveryId) { try { return (await readFile(this.paths(namespaceId, deliveryId).journal, 'utf8')).split('\n').filter(Boolean).map(JSON.parse) } catch (error) { if (error?.code === 'ENOENT') return []; throw Object.assign(new Error('CORRUPT_DELIVERY_JOURNAL'), { code: 'CORRUPT_DELIVERY_JOURNAL' }) } }
  async _recover(paths) {
    const pending = await this._json(paths.pending)
    if (!pending) return
    const records = await this.journal(pending.snapshot.namespaceId, pending.snapshot.deliveryId)
    const matching = records.filter((item) => item.operationId === pending.operation.operationId)
    if (!matching.length) throw Object.assign(new Error('DELIVERY_OPERATION_INDETERMINATE'), { code: 'DELIVERY_OPERATION_INDETERMINATE' })
    const last = matching[matching.length - 1]
    if (last.state === 'succeeded' && pending.snapshotHash === hash(pending.snapshot)) {
      await atomic(paths.snapshot, pending.snapshot)
      await rm(paths.pending, { force: true })
      return
    }
    throw Object.assign(new Error('DELIVERY_OPERATION_INDETERMINATE'), { code: 'DELIVERY_OPERATION_INDETERMINATE' })
  }
  async read(namespaceId, deliveryId) { const paths = this.paths(namespaceId, deliveryId); await this._recover(paths); const snapshot = await this._json(paths.snapshot); if (!snapshot) return null; if (!validSnapshot(snapshot) || snapshot.snapshotHash !== hash({ ...snapshot, snapshotHash: undefined })) throw Object.assign(new Error('CORRUPT_DELIVERY_STORAGE'), { code: 'CORRUPT_DELIVERY_STORAGE' }); return snapshot }
  async create(input) { return this._locked(input.namespaceId, input.deliveryId, async () => { const current = await this.read(input.namespaceId, input.deliveryId); if (current) return JSON.stringify({ ...current, snapshotHash: undefined }) === JSON.stringify(input) ? { ok: true, changed: false, snapshot: current } : { ok: false, error: { code: 'DELIVERY_IDENTITY_CONFLICT' } }; if (!validSnapshot(input)) return { ok: false, error: { code: 'INVALID_DELIVERY_SNAPSHOT' } }; return this._write(null, input, { kind: 'delivery_created', idempotencyKey: `create:${input.deliveryId}` }) }) }
  async promote({ namespaceId, request, definition, evidence, execution }) { return this._locked(namespaceId, request.deliveryId, async () => { const current = await this.read(namespaceId, request.deliveryId); const records = await this.journal(namespaceId, request.deliveryId); const scopeHash = deliveryScopeHash(namespaceId, request, execution), semanticHash = deliverySemanticHash(request), prior = records.find((item) => item.scopeHash === scopeHash && item.state === 'succeeded'); if (prior) { if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }; return { ok: true, changed: false, idempotent: true, snapshot: current } } const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution }); if (!decision.allowed) return { ok: false, error: decision }; const next = applyDeliveryPromotion(current, request); return this._write(current, next, { kind: 'delivery_promoted', idempotencyKey: request.idempotencyKey, scopeHash, semanticHash, evidenceIds: request.evidenceIds }) }) }
  async _write(current, value, operationInput) { const paths = this.paths(value.namespaceId, value.deliveryId), operationId = createHash('sha256').update(`${value.namespaceId}:${value.deliveryId}:${operationInput.idempotencyKey}`).digest('hex'), operation = { schemaVersion: '1', operationId, deliveryId: value.deliveryId, revision: (current?.revision ?? 0) + (current ? 1 : 0), kind: operationInput.kind, state: 'pending', timestamp: new Date().toISOString(), ...(operationInput.scopeHash ? { scopeHash: operationInput.scopeHash, semanticHash: operationInput.semanticHash } : {}), ...(operationInput.evidenceIds ? { evidenceIds: [...operationInput.evidenceIds].sort() } : {}) }; const clean = { ...value }; delete clean.snapshotHash; const snapshot = { ...clean, snapshotHash: hash(clean) }; await atomic(paths.pending, { operation, snapshot, snapshotHash: hash(snapshot) }); await append(paths.journal, operation); await this.fault('after-pending-journal'); const running = { ...operation, state: 'running', timestamp: new Date().toISOString() }; await append(paths.journal, running); await this.fault('after-running'); const succeeded = { ...operation, state: 'succeeded', timestamp: new Date().toISOString(), resultHash: snapshot.snapshotHash }; await append(paths.journal, succeeded); await this.fault('after-success'); await atomic(paths.snapshot, snapshot); await this.fault('after-snapshot'); await rm(paths.pending, { force: true }); return { ok: true, changed: true, idempotent: false, snapshot } }
  async recordOperation(namespaceId, deliveryId, input) { return this._locked(namespaceId, deliveryId, async () => { const records = await this.journal(namespaceId, deliveryId), operationId = createHash('sha256').update(`${namespaceId}:${deliveryId}:${input.idempotencyKey}`).digest('hex'), prior = records.filter((item) => item.operationId === operationId).at(-1); const semanticHash = hash(input.facts); if (prior) { if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }; return { ok: true, changed: false, operation: prior } } const operation = { schemaVersion: '1', operationId, deliveryId, kind: input.kind, state: input.state, semanticHash, facts: input.facts, timestamp: new Date().toISOString() }; await append(this.paths(namespaceId, deliveryId).journal, operation); return { ok: true, changed: true, operation } }) }

  _deliveryOperationProjection(records) {
    const history = records.filter((record) => record.recordType === 'delivery-operation')
    const current = new Map()
    const resolved = new Set(history.filter((record) => record.resolvedOperationId && ['succeeded', 'failed'].includes(record.state)).map((record) => record.resolvedOperationId))
    for (const record of history) current.set(record.operationId, record)
    const rollbackHistory = records.filter((record) => record.recordType === 'rollback-request')
    const rollbackCurrent = new Map()
    for (const record of rollbackHistory) rollbackCurrent.set(record.rollbackRequestId, record)
    return { history, operations: [...current.values()], rollbackRequests: [...rollbackCurrent.values()], rollbackRequestHistory: rollbackHistory, unresolvedIndeterminate: [...current.values()].filter((record) => record.state === 'indeterminate' && !resolved.has(record.operationId)) }
  }
  async readWithOperations(namespaceId, deliveryId) { const snapshot = await this.read(namespaceId, deliveryId); if (!snapshot) return null; const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)); return { ...snapshot, deliveryOperations: projection.operations, rollbackRequests: projection.rollbackRequests } }
  async inspectDeliveryOperations(namespaceId, deliveryId) { return this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)) }
  async createRollbackRequest({ namespaceId, deliveryId, workflowId, caseId, runtimeId, request, execution }) {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId); if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      if (snapshot.workflowId !== workflowId || snapshot.parentCaseId !== caseId || snapshot.runtimeId !== runtimeId) return { ok: false, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), prior = projection.rollbackRequestHistory.find((record) => record.scopeHash === request.scopeHash)
      if (prior) return prior.semanticHash === request.semanticHash ? { ok: true, changed: false, idempotent: true, request: projection.rollbackRequests.find((item) => item.rollbackRequestId === prior.rollbackRequestId) ?? prior } : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== request.expectedRevision) return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      const record = { recordType: 'rollback-request', schemaVersion: '1', rollbackRequestId: request.rollbackRequestId, deliveryId, workflowId, namespaceId, caseId, runtimeId, status: 'requested', expectedRevision: request.expectedRevision, idempotencyKey: request.idempotencyKey, scopeHash: request.scopeHash, semanticHash: request.semanticHash, targetId: request.targetId, targetHash: request.targetHash, deploymentRef: canonical(request.deploymentRef), priorArtifactRef: canonical(request.priorArtifactRef), priorReleaseRef: canonical(request.priorReleaseRef), reasonCode: request.reasonCode, ...(request.reason ? { reason: request.reason } : {}), requestedAt: new Date().toISOString(), requestedBy: canonical(execution) }
      await append(this.paths(namespaceId, deliveryId).journal, record); return { ok: true, changed: true, idempotent: false, request: record }
    })
  }
  async approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval) {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId); if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId)
      if (!current) return { ok: false, error: { code: 'ROLLBACK_REQUEST_NOT_FOUND' } }
      const scopeHash = `sha256:${hash({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`, semanticHash = `sha256:${hash({ rollbackRequestId, expectedRevision: approval.expectedRevision, actorId: approval.execution.actorId })}`, prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash)
      if (prior) return prior.approvalSemanticHash === semanticHash ? { ok: true, changed: false, idempotent: true, request: prior } : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision) return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      if (current.status !== 'requested') return { ok: false, error: { code: 'ROLLBACK_REQUEST_ALREADY_DECIDED' } }
      const record = { ...current, status: 'approved', approvedAt: new Date().toISOString(), approvedBy: canonical(approval.execution), approvalScopeHash: scopeHash, approvalSemanticHash: semanticHash, approvalIdempotencyKey: approval.idempotencyKey }
      await append(this.paths(namespaceId, deliveryId).journal, record); return { ok: true, changed: true, idempotent: false, request: record }
    })
  }
  async createDeliveryOperation({ namespaceId, workflowId, deliveryId, caseId, runtimeId, request, targetRef, execution }) {
    return this._locked(namespaceId, deliveryId, async () => {
      const normalized = normalizeDeliveryOperationRequest(request); if (!normalized.ok) return normalized
      const snapshot = await this.read(namespaceId, deliveryId); if (!snapshot) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      const targetHash = targetRef?.targetHash; const identity = deriveDeliveryOperationIdentity({ namespaceId, workflowId, deliveryId, caseId, runtimeId }, normalized.value, targetHash); if (!identity.ok) return identity
      const records = await this.journal(namespaceId, deliveryId), projection = this._deliveryOperationProjection(records), existing = projection.history.find((record) => record.scopeHash === identity.value.scopeHash)
      if (existing) return existing.semanticHash === identity.value.semanticHash ? { ok: true, changed: false, idempotent: true, operation: projection.operations.find((record) => record.operationId === existing.operationId) ?? existing } : { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
      if (snapshot.revision !== normalized.value.expectedRevision) return { ok: false, error: { code: 'REVISION_CONFLICT' } }
      if (projection.unresolvedIndeterminate.length) return { ok: false, error: { code: 'DELIVERY_OPERATION_INDETERMINATE' } }
      const now = new Date().toISOString(), source = normalized.value.artifactRef ?? normalized.value.priorArtifactRef, operation = { recordType: 'delivery-operation', operationId: identity.value.operationId, kind: normalized.value.kind, expectedRevision: normalized.value.expectedRevision, targetRef: canonical(targetRef), artifactRef: normalized.value.artifactRef ?? normalized.value.priorArtifactRef, releaseRef: normalized.value.releaseRef ?? normalized.value.priorReleaseRef, deploymentRef: normalized.value.deploymentRef, rollbackRef: normalized.value.rollbackRef, state: 'pending', attempt: 0, requestedAt: now, startedAt: undefined, completedAt: undefined, execution: canonical(execution), adapterCorrelation: undefined, scopeHash: identity.value.scopeHash, semanticHash: identity.value.semanticHash, result: undefined, error: undefined, resolvedOperationId: undefined, sourceCommit: source?.sourceCommit, artifactDigest: source?.digest, rollbackRequestId: normalized.value.rollbackRequestId, approvedEvidenceId: normalized.value.approvedEvidenceId }
      const persisted = Object.fromEntries(Object.entries(operation).filter(([, value]) => value !== undefined)); const contract = validateDeliveryOperationRecord(persisted); if (!contract.ok) return { ok: false, error: contract.error }
      await append(this.paths(namespaceId, deliveryId).journal, persisted); await this.fault('after-delivery-operation-write'); return { ok: true, changed: true, idempotent: false, operation: persisted }
    })
  }
  async recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options = {}) {
    return this._locked(namespaceId, deliveryId, async () => {
      await this.read(namespaceId, deliveryId)
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), previous = projection.operations.find((record) => record.operationId === operationId)
      if (!previous) return { ok: false, error: { code: 'DELIVERY_OPERATION_NOT_FOUND' } }
      const now = new Date().toISOString(), state = transition.state, next = { ...previous, state, attempt: state === 'running' ? previous.attempt + 1 : previous.attempt, startedAt: state === 'running' ? now : previous.startedAt, completedAt: ['succeeded', 'failed'].includes(state) ? now : undefined, adapterCorrelation: transition.adapterCorrelation ?? previous.adapterCorrelation, result: transition.result, error: transition.error, resolvedOperationId: transition.resolvedOperationId }
      const clean = Object.fromEntries(Object.entries(next).filter(([, value]) => value !== undefined)), valid = validateDeliveryOperationTransition(previous, clean, options); if (!valid.ok) return valid
      const contract = validateDeliveryOperationRecord(clean); if (!contract.ok) return contract
      await append(this.paths(namespaceId, deliveryId).journal, clean); await this.fault(`after-delivery-operation-${state}`); return { ok: true, changed: true, operation: clean }
    })
  }
  async startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation) { return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, { state: 'running', adapterCorrelation }) }
  async reconcileDeliveryOperation(namespaceId, deliveryId, operationId, observation) { return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, { state: observation.state, result: observation.result, error: observation.error, adapterCorrelation: observation.adapterCorrelation, resolvedOperationId: operationId }, { inspectedObservation: { ...observation, operationId } }) }

  /** Only an indeterminate logical operation without a later terminal reconciliation blocks. */
  async hasIndeterminateOperation(namespaceId, deliveryId) { try { return (await this.inspectDeliveryOperations(namespaceId, deliveryId)).unresolvedIndeterminate.length > 0 } catch { return true } }

  /**
   * Atomically patch specific fields in the delivery snapshot and append a journal record.
   * Supports dot-notation keys like 'git.checkpoint' to set nested properties.
   * Used after checkpoint/push/PR to persist the new state without a full promote cycle.
   */
  async updateSnapshot(namespaceId, deliveryId, patch, operationInput) {
    return this._locked(namespaceId, deliveryId, async () => {
      const current = await this.read(namespaceId, deliveryId)
      if (!current) return { ok: false, error: { code: 'DELIVERY_NOT_FOUND' } }
      // Apply patch: support dot-notation for nested git.* fields.
      const updated = { ...current }
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.')
        if (parts.length === 1) { updated[key] = value }
        else if (parts.length === 2) { updated[parts[0]] = { ...(updated[parts[0]] ?? {}), [parts[1]]: value } }
        else { updated[key] = value } // fallback for unexpected depth
      }
      updated.updatedAt = patch.updatedAt ?? new Date().toISOString()
      return this._write(current, updated, operationInput)
    })
  }
}

import { createHash } from 'node:crypto'
import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { createWorkflowEvidence } from './workflow-evidence.mjs'

export class WorkflowEvidenceStoreError extends Error { constructor(code, details = {}, cause) { super(code, cause ? { cause } : undefined); this.code = code; this.details = details } }
const semanticHash = (value) => createHash('sha256').update(JSON.stringify(value)).digest('hex')
async function durableAppend(path, value) { await mkdir(dirname(path), { recursive: true }); await appendFile(path, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 }); const handle = await open(path, 'r'); try { await handle.sync() } finally { await handle.close() } }

/** Append-only evidence journal. Locks serialize writers only inside this Node process. */
export class WorkflowEvidenceStore {
  constructor(dataRoot) { this.dataRoot = dataRoot; this.locks = new Map() }
  path(namespaceId, storageId) { return join(this.dataRoot, 'workflows', namespaceId, storageId, 'evidence.jsonl') }
  _locked(key, action) { const prior = this.locks.get(key) ?? Promise.resolve(); const operation = prior.then(action); const tail = operation.catch(() => {}); this.locks.set(key, tail); return operation.finally(() => { if (this.locks.get(key) === tail) this.locks.delete(key) }) }
  async list(namespaceId, storageId, { stepId } = {}) { let text=''; try { text=await readFile(this.path(namespaceId, storageId),'utf8') } catch(error) { if(error?.code==='ENOENT') return []; throw new WorkflowEvidenceStoreError('EVIDENCE_STORAGE_FAILURE',{},error) } try { return text.split('\n').filter(Boolean).map(JSON.parse).filter((item)=>!stepId||item.stepId===stepId).sort((a,b)=>a.observedAt.localeCompare(b.observedAt)||a.evidenceId.localeCompare(b.evidenceId)) } catch(error) { throw new WorkflowEvidenceStoreError('CORRUPT_EVIDENCE_STORAGE',{},error) } }
  async record(namespaceId, storageId, input, source) { const key=`${namespaceId}\0${storageId}`; return this._locked(key, async()=>{ const existing=await this.list(namespaceId,storageId); const scope={ namespaceId, workflowId:input.workflowId, stepId:input.stepId, source, idempotencyKey:input.idempotencyKey }; const fingerprint=semanticHash({ ...input, idempotencyKey:undefined }); if(input.idempotencyKey){ const prior=existing.find((item)=>item.idempotency?.scopeHash===semanticHash(scope)); if(prior){ if(prior.idempotency.semanticHash!==fingerprint) throw new WorkflowEvidenceStoreError('IDEMPOTENCY_KEY_COLLISION'); return { created:false,idempotent:true,evidence:prior } } } const evidence=createWorkflowEvidence(input,namespaceId,source); const stored={...evidence,...(input.idempotencyKey?{idempotency:{scopeHash:semanticHash(scope),semanticHash:fingerprint}}:{})}; await durableAppend(this.path(namespaceId,storageId),stored); return {created:true,idempotent:false,evidence:stored} }) }
}

import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import { appendFile, mkdir, open, readFile, readdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const sha = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
const canonicalize = (value) =>
  Array.isArray(value)
    ? value.map(canonicalize)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonicalize(value[key])])
        )
      : value
const canonical = (value) => JSON.stringify(canonicalize(value))
const attemptKey = (namespaceId, storageId, attemptId) => `${namespaceId}\0${storageId}\0${attemptId}`
async function durable(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}
function safeEqual(a, b) {
  try {
    const x = Buffer.from(a),
      y = Buffer.from(b)
    return x.length === y.length && timingSafeEqual(x, y)
  } catch {
    return false
  }
}
function validBusiness(value) {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !['status', 'summary', 'artifacts', 'claims', 'findings'].includes(key))
  )
    return false
  if (
    !['PASS', 'FAIL'].includes(value.status) ||
    typeof value.summary !== 'string' ||
    value.summary.length === 0 ||
    value.summary.length > 2000
  )
    return false
  if (
    !value.claims ||
    typeof value.claims !== 'object' ||
    Array.isArray(value.claims) ||
    Object.keys(value.claims).some((key) => key !== 'modifiedFiles') ||
    !Array.isArray(value.claims.modifiedFiles) ||
    value.claims.modifiedFiles.length > 1000 ||
    value.claims.modifiedFiles.some((file) => typeof file !== 'string' || file.length === 0 || file.length > 1024)
  )
    return false
  if (
    value.artifacts !== undefined &&
    (!Array.isArray(value.artifacts) ||
      value.artifacts.length > 8 ||
      value.artifacts.some(
        (artifact) =>
          !artifact ||
          typeof artifact !== 'object' ||
          Array.isArray(artifact) ||
          Object.keys(artifact).some((key) => !['kind', 'encoding', 'content'].includes(key)) ||
          typeof artifact.kind !== 'string' ||
          artifact.kind.length === 0 ||
          artifact.kind.length > 128 ||
          artifact.encoding !== 'markdown' ||
          typeof artifact.content !== 'string' ||
          artifact.content.length === 0 ||
          Buffer.byteLength(artifact.content, 'utf8') > 262144
      ))
  )
    return false
  if (
    value.findings !== undefined &&
    (!Array.isArray(value.findings) ||
      value.findings.length > 100 ||
      value.findings.some(
        (finding) =>
          !finding ||
          typeof finding !== 'object' ||
          Array.isArray(finding) ||
          Object.keys(finding).some((key) => !['severity', 'code', 'summary', 'file', 'line'].includes(key)) ||
          !['info', 'warning', 'error', 'blocking'].includes(finding.severity) ||
          typeof finding.code !== 'string' ||
          finding.code.length === 0 ||
          finding.code.length > 128 ||
          typeof finding.summary !== 'string' ||
          finding.summary.length === 0 ||
          finding.summary.length > 1000 ||
          (finding.file !== undefined &&
            (typeof finding.file !== 'string' || finding.file.length === 0 || finding.file.length > 1024)) ||
          (finding.line !== undefined && (!Number.isSafeInteger(finding.line) || finding.line < 1))
      ))
  )
    return false
  return true
}

export class AgentStepResultStore {
  constructor(dataRoot, { clock = () => new Date(), ttlMs = 15 * 60 * 1000 } = {}) {
    this.dataRoot = dataRoot
    this.clock = clock
    this.ttlMs = ttlMs
    this.locks = new Map()
    this.capabilityIndex = new Map()
    this.attemptIndex = new Map()
    this.indexLoaded = false
    this.initializePromise = null
  }
  path(namespaceId, storageId) {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'agent-step-results.jsonl')
  }
  async list(namespaceId, storageId) {
    try {
      return (await readFile(this.path(namespaceId, storageId), 'utf8')).split('\n').filter(Boolean).map(JSON.parse)
    } catch (error) {
      if (error?.code === 'ENOENT') return []
      throw error
    }
  }
  indexLedger(namespaceId, storageId, events) {
    for (const event of events) {
      if (event.type === 'capability-issued') {
        const entry = { namespaceId, storageId, event, result: null }
        this.capabilityIndex.set(event.tokenHash, entry)
        this.attemptIndex.set(attemptKey(namespaceId, storageId, event.attemptId), entry)
      } else if (event.type === 'result-submitted') {
        const entry = this.attemptIndex.get(attemptKey(namespaceId, storageId, event.attemptId))
        if (entry && !entry.result) entry.result = event
      }
    }
  }
  async initialize() {
    if (this.indexLoaded) return
    if (this.initializePromise) return this.initializePromise
    this.initializePromise = (async () => {
      const workflows = join(this.dataRoot, 'workflows')
      let namespaces = []
      try {
        namespaces = await readdir(workflows, { withFileTypes: true })
      } catch (error) {
        if (error?.code === 'ENOENT') {
          this.indexLoaded = true
          return
        }
        throw error
      }
      for (const ns of namespaces.filter((entry) => entry.isDirectory())) {
        let stores = []
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
    return this.initializePromise
  }
  async locked(key, work) {
    const prior = this.locks.get(key) ?? Promise.resolve(),
      operation = prior.then(work),
      tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
  async issue(namespaceId, storageId, identity) {
    for (const key of ['attemptId', 'workflowId', 'stepId', 'namespaceId', 'caseId', 'agentName'])
      if (!SAFE.test(identity[key] ?? '')) throw new Error('INVALID_RESULT_CAPABILITY_IDENTITY')
    if (identity.namespaceId !== namespaceId || !/^sha256:[0-9a-f]{64}$/.test(identity.briefHash ?? ''))
      throw new Error('INVALID_RESULT_CAPABILITY_IDENTITY')
    await this.initialize()
    const key = attemptKey(namespaceId, storageId, identity.attemptId)
    return this.locked(key, async () => {
      const existing = this.attemptIndex.get(key)
      if (existing) {
        const same = ['attemptId', 'workflowId', 'stepId', 'namespaceId', 'caseId', 'agentName', 'briefHash'].every(
          (field) => existing.event[field] === identity[field]
        )
        throw new Error(same ? 'RESULT_CAPABILITY_ALREADY_ISSUED' : 'RESULT_CAPABILITY_IDENTITY_CONFLICT')
      }
      const token = randomBytes(32).toString('base64url'),
        now = this.clock(),
        record = {
          type: 'capability-issued',
          capabilityId: randomUUID(),
          tokenHash: sha(token),
          ...identity,
          issuedAt: now.toISOString(),
          expiresAt: new Date(now.getTime() + this.ttlMs).toISOString(),
          submissionBudget: 1,
        },
        entry = { namespaceId, storageId, event: record, result: null }
      await durable(this.path(namespaceId, storageId), record)
      this.capabilityIndex.set(record.tokenHash, entry)
      this.attemptIndex.set(key, entry)
      return { token, expiresAt: record.expiresAt }
    })
  }
  async resolve(token) {
    if (typeof token !== 'string' || token.length < 32 || token.length > 256) return null
    await this.initialize()
    const digest = sha(token),
      located = this.capabilityIndex.get(digest)
    return located && safeEqual(located.event.tokenHash, digest) ? located : null
  }
  async submit(token, business, observed = {}) {
    if (!validBusiness(business)) return { ok: false, code: 'RESULT_SCHEMA_INVALID' }
    const located = await this.resolve(token)
    if (!located) return { ok: false, code: 'RESULT_CAPABILITY_INVALID' }
    const { namespaceId, storageId, event: issued } = located
    if (
      observed.attemptId !== issued.attemptId ||
      observed.caseId !== issued.caseId ||
      observed.agentName !== issued.agentName
    )
      return { ok: false, code: 'RESULT_IDENTITY_MISMATCH' }
    const resultHash = sha(canonical(business)),
      key = attemptKey(namespaceId, storageId, issued.attemptId)
    return this.locked(key, async () => {
      const entry = this.attemptIndex.get(key) ?? located,
        existing = entry.result
      if (existing)
        return existing.resultHash === resultHash
          ? { ok: true, idempotent: true, result: existing }
          : { ok: false, code: 'RESULT_SEMANTIC_COLLISION' }
      if (this.clock().getTime() > Date.parse(issued.expiresAt)) return { ok: false, code: 'RESULT_CAPABILITY_EXPIRED' }
      const result = {
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
      await durable(this.path(namespaceId, storageId), result)
      entry.result = result
      this.attemptIndex.set(key, entry)
      return { ok: true, idempotent: false, result }
    })
  }
  async getByAttempt(namespaceId, storageId, attemptId) {
    await this.initialize()
    return this.attemptIndex.get(attemptKey(namespaceId, storageId, attemptId))?.result ?? null
  }
}
export const hashAgentStepResult = (value) => sha(canonical(value))

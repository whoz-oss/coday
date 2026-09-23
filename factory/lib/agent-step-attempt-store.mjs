import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

export const AGENT_STEP_ATTEMPT_STATUSES = Object.freeze([
  'starting',
  'running',
  'succeeded',
  'failed',
  'indeterminate',
  'interrupted',
])
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

async function appendDurable(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}
function validInstant(value) {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value))
}
function validate(attempt) {
  if (
    !attempt ||
    typeof attempt !== 'object' ||
    !SAFE_ID.test(attempt.attemptId ?? '') ||
    !SAFE_ID.test(attempt.workflowId ?? '') ||
    !SAFE_ID.test(attempt.stepId ?? '') ||
    !SAFE_ID.test(attempt.namespaceId ?? '') ||
    typeof attempt.runtimeId !== 'string' ||
    !attempt.runtimeId ||
    typeof attempt.agentName !== 'string' ||
    !attempt.agentName ||
    !/^sha256:[0-9a-f]{64}$/.test(attempt.briefHash ?? '') ||
    !Number.isSafeInteger(attempt.workflowRevisionAtStart) ||
    attempt.workflowRevisionAtStart < 1 ||
    !Number.isSafeInteger(attempt.attemptNumber) ||
    attempt.attemptNumber < 1 ||
    !AGENT_STEP_ATTEMPT_STATUSES.includes(attempt.status) ||
    !validInstant(attempt.startedAt)
  )
    throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (attempt.caseId !== null && typeof attempt.caseId !== 'string') throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  const terminal = ['succeeded', 'failed', 'indeterminate', 'interrupted'].includes(attempt.status)
  if (terminal !== validInstant(attempt.finishedAt) || (!terminal && attempt.finishedAt !== null))
    throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (attempt.status === 'starting' && attempt.caseId !== null) throw new Error('INVALID_AGENT_STEP_ATTEMPT')
  if (attempt.status !== 'starting' && !attempt.caseId) throw new Error('INVALID_AGENT_STEP_ATTEMPT')
}

export class AgentStepAttemptStore {
  constructor(dataRoot) {
    this.dataRoot = dataRoot
    this.locks = new Map()
  }
  path(namespaceId, storageId) {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'agent-step-attempts.jsonl')
  }
  async list(namespaceId, storageId) {
    try {
      return (await readFile(this.path(namespaceId, storageId), 'utf8')).split('\n').filter(Boolean).map(JSON.parse)
    } catch (error) {
      if (error?.code === 'ENOENT') return []
      throw error
    }
  }
  async append(namespaceId, storageId, attempt) {
    validate(attempt)
    if (attempt.namespaceId !== namespaceId) throw new Error('AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH')
    const key = `${namespaceId}\0${storageId}`
    const prior = this.locks.get(key) ?? Promise.resolve()
    const operation = prior.then(async () => {
      const events = await this.list(namespaceId, storageId),
        previous = events.filter((event) => event.attemptId === attempt.attemptId).at(-1)
      if (previous) {
        const immutable = [
          'workflowId',
          'workflowRevisionAtStart',
          'stepId',
          'attemptNumber',
          'namespaceId',
          'runtimeId',
          'agentName',
          'briefHash',
          'startedAt',
        ]
        if (immutable.some((field) => previous[field] !== attempt[field]))
          throw new Error('AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT')
        const allowed =
          {
            starting: ['running', 'failed', 'interrupted'],
            running: ['succeeded', 'failed', 'indeterminate', 'interrupted'],
          }[previous.status] ?? []
        if (!allowed.includes(attempt.status)) throw new Error('INVALID_AGENT_STEP_ATTEMPT_TRANSITION')
      } else if (attempt.status !== 'starting') throw new Error('AGENT_STEP_ATTEMPT_MUST_START')
      await appendDurable(this.path(namespaceId, storageId), attempt)
      return attempt
    })
    const tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
}

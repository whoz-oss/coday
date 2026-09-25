import { join } from 'node:path'
import { appendDurableJson, createKeyedLock, readJsonLines, type KeyedLock } from './storage-kernel.js'
import {
  AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS,
  AGENT_STEP_ATTEMPT_TRANSITIONS,
  validateAgentStepAttempt,
  type AgentStepAttempt,
} from '../../domain/agent-attempt/agent-step-attempt.js'

/**
 * Append-only, lock-serialized journal of agent step attempts.
 *
 * The durable append, missing-file read and in-process keyed serialization are
 * the shared storage-kernel primitives; this adapter only owns the journal
 * layout, the identity-conflict guard and the status-transition guard. Locks
 * serialize writers only inside this Node process.
 */
export class AgentStepAttemptStore {
  private readonly locks: KeyedLock

  constructor(readonly dataRoot: string) {
    this.locks = createKeyedLock()
  }

  path(namespaceId: string, storageId: string): string {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'agent-step-attempts.jsonl')
  }

  async list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]> {
    return readJsonLines<AgentStepAttempt>(this.path(namespaceId, storageId))
  }

  async append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt> {
    validateAgentStepAttempt(attempt)
    if (attempt.namespaceId !== namespaceId) throw new Error('AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH')
    const key = `${namespaceId}\0${storageId}`
    return this.locks.run(key, async () => {
      const events = await this.list(namespaceId, storageId)
      const previous = events.filter((event) => event.attemptId === attempt.attemptId).at(-1)
      if (previous) {
        if (AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS.some((field) => previous[field] !== attempt[field]))
          throw new Error('AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT')
        const allowed = AGENT_STEP_ATTEMPT_TRANSITIONS[previous.status] ?? []
        if (!allowed.includes(attempt.status)) throw new Error('INVALID_AGENT_STEP_ATTEMPT_TRANSITION')
      } else if (attempt.status !== 'starting') throw new Error('AGENT_STEP_ATTEMPT_MUST_START')
      await appendDurableJson(this.path(namespaceId, storageId), attempt, { ensureDirectory: true })
      return attempt
    })
  }
}

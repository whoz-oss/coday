import type { AgentStepAttempt } from '../../domain/agent-attempt/agent-step-attempt.js'

/**
 * Persistence port for the agent-step-attempt domain context.
 *
 * The attempt journal is append-only and lock-serialized: a transition appends
 * a new record rather than mutating the previous one. The port exposes exactly
 * those two capabilities and no filesystem detail.
 */

export interface AgentStepAttemptRepository {
  /** Attempts of one workflow storage scope, in append order. */
  list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]>
  /** Durably appends a validated attempt (starting record or status transition). */
  append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt>
}

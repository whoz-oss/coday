import type { AgentStepAttemptRepository } from '../../ports/persistence/agent-step-attempt-repository.js'
import type { AgentStepAttempt } from '../../domain/agent-attempt/agent-step-attempt.js'

/**
 * Filesystem agent-step-attempt repository adapter.
 *
 * The append-only JSONL journal, identity-conflict guard and status-transition
 * guard live in the injected attempt store; this adapter expresses them through
 * the port. `factory/lib/agent-step-attempt-store.mjs` wires the concrete store.
 */

export interface AgentStepAttemptStoreLike {
  list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]>
  append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt>
}

export class FilesystemAgentStepAttemptRepository implements AgentStepAttemptRepository {
  constructor(private readonly store: AgentStepAttemptStoreLike) {}

  list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]> {
    return this.store.list(namespaceId, storageId)
  }

  append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt> {
    return this.store.append(namespaceId, storageId, attempt)
  }
}

/** Wires a filesystem agent-step-attempt repository around a concrete store. */
export function createFilesystemAgentStepAttemptRepository(
  store: AgentStepAttemptStoreLike
): FilesystemAgentStepAttemptRepository {
  return new FilesystemAgentStepAttemptRepository(store)
}

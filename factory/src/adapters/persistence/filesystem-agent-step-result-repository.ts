import type {
  AgentStepResultIssueResult,
  AgentStepResultRepository,
  AgentStepResultSubmitResult,
} from '../../ports/persistence/agent-step-result-repository.js'
import type {
  AgentStepResultCapabilityIdentity,
  AgentStepResultLedgerEvent,
  AgentStepResultObservedIdentity,
  AgentStepResultSubmitted,
} from '../../domain/agent-attempt/agent-step-result.js'

/**
 * Filesystem agent-step-result repository adapter.
 *
 * The capability ledger, in-memory attempt/capability index and submission
 * state machine live in the injected result store; this adapter expresses them
 * through the port. `factory/lib/agent-step-result-store.mjs` wires the concrete
 * store.
 */

export interface AgentStepResultStoreLike {
  issue(
    namespaceId: string,
    storageId: string,
    identity: AgentStepResultCapabilityIdentity
  ): Promise<AgentStepResultIssueResult>
  submit(
    token: string,
    business: unknown,
    observed?: Partial<AgentStepResultObservedIdentity>
  ): Promise<AgentStepResultSubmitResult>
  getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null>
  list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]>
}

export class FilesystemAgentStepResultRepository implements AgentStepResultRepository {
  constructor(private readonly store: AgentStepResultStoreLike) {}

  issue(
    namespaceId: string,
    storageId: string,
    identity: AgentStepResultCapabilityIdentity
  ): Promise<AgentStepResultIssueResult> {
    return this.store.issue(namespaceId, storageId, identity)
  }

  submit(
    token: string,
    business: unknown,
    observed?: Partial<AgentStepResultObservedIdentity>
  ): Promise<AgentStepResultSubmitResult> {
    return this.store.submit(token, business, observed)
  }

  getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null> {
    return this.store.getByAttempt(namespaceId, storageId, attemptId)
  }

  list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]> {
    return this.store.list(namespaceId, storageId)
  }
}

/** Wires a filesystem agent-step-result repository around a concrete store. */
export function createFilesystemAgentStepResultRepository(
  store: AgentStepResultStoreLike
): FilesystemAgentStepResultRepository {
  return new FilesystemAgentStepResultRepository(store)
}

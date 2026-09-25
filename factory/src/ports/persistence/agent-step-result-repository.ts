import type {
  AgentStepResultCapabilityIdentity,
  AgentStepResultLedgerEvent,
  AgentStepResultObservedIdentity,
  AgentStepResultSubmitted,
} from '../../domain/agent-attempt/agent-step-result.js'

/**
 * Persistence port for the agent-step-result domain context.
 *
 * A submission capability is issued for an attempt identity, then redeemed by a
 * worker that declares its observed identity and a structured business result.
 * The ledger is append-only; the port also exposes the projected submitted
 * result of an attempt and no capability/token hashing detail.
 */

/** A freshly issued submission capability (the clear token is never persisted). */
export interface AgentStepResultIssueResult {
  token: string
  expiresAt: string
}

/** Outcome of a capability-backed business result submission. */
export type AgentStepResultSubmitResult =
  | { ok: false; code: string }
  | { ok: true; idempotent: true; result: AgentStepResultSubmitted }
  | { ok: true; idempotent: false; result: AgentStepResultSubmitted }

export interface AgentStepResultRepository {
  /** Issues a single-use capability bound to an attempt identity. */
  issue(
    namespaceId: string,
    storageId: string,
    identity: AgentStepResultCapabilityIdentity
  ): Promise<AgentStepResultIssueResult>
  /** Redeems a capability with a structured business result; replays are idempotent. */
  submit(
    token: string,
    business: unknown,
    observed?: Partial<AgentStepResultObservedIdentity>
  ): Promise<AgentStepResultSubmitResult>
  /** The submitted result of one attempt, or `null` when none was recorded. */
  getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null>
  /** The raw capability/result ledger events of one workflow storage scope. */
  list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]>
}

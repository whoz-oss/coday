/**
 * Application service for the G1 human decision.
 *
 * The pure policy, canonical serialization and evidence hash live in
 * `domain/forge-bmad/forge-human-decision.ts`; the append-only ledger access
 * lives in `adapters/forge/forge-ledger-store.ts`.
 *
 * Identity and authority are verified outside the payload: the dashboard
 * supplies the `identityPort` adapter.
 */

import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import {
  G1_OUTCOMES,
  G1_POLICY_VERSION,
  G1_REASON_CODES,
  canonicalG1,
  computeG1EvidenceSetHash,
} from '../../domain/forge-bmad/forge-human-decision.js'
import type { ForgeLedgerEvent } from '../../domain/forge-bmad/types.js'
import { ensureForgeRunStore } from '../../adapters/forge/forge-roots-resolver.js'
import { appendForgeLedgerEvent, parseForgeLedger } from '../../adapters/forge/forge-ledger-store.js'

/** Identity/authority port supplied by the composition root. */
export interface HumanDecisionIdentityPort {
  actorId: () => Promise<string>
  authorize: (request: {
    actorId: string
    runId: string
    gate: string
    attempt: number
    policyVersion: string
  }) => Promise<{ authorityId: string } | null>
}

/** The current G1 gate event of a run, if any. */
function currentGate(events: readonly ForgeLedgerEvent[], runId: string): ForgeLedgerEvent | undefined {
  return events.filter((event) => event.event === 'gate_started' && event.runId === runId && event.gate === 'G1').at(-1)
}

/**
 * Record a human G1 decision, enforcing idempotence and conflict detection.
 *
 * `authorize({ actorId, runId, gate, attempt, policyVersion })` must return a
 * verified authority object or null.
 */
export async function recordHumanDecision({
  roots,
  runId,
  decision,
  identityPort,
  now = () => new Date().toISOString(),
}: {
  roots: { runStoreRoot: string }
  runId: string
  decision: any
  identityPort: HumanDecisionIdentityPort
  now?: () => string
}): Promise<{ status: 'recorded' | 'idempotent'; event: ForgeLedgerEvent }> {
  if (!identityPort || typeof identityPort.authorize !== 'function')
    throw new Error('an identity authorization port is required')
  if (!decision || typeof decision !== 'object') throw new Error('decision is required')
  if (!G1_OUTCOMES.has(decision.outcome)) throw new Error('decision.outcome must be approved or rejected')
  if (!G1_REASON_CODES.has(decision.reasonCode)) throw new Error('decision.reasonCode is invalid')
  if (decision.actorId !== undefined || decision.actorRole !== undefined)
    throw new Error('actor identity and role must not be declared by the decision payload')

  const filePath = join(ensureForgeRunStore(roots), `${runId}.jsonl`)
  const events = parseForgeLedger(filePath)
  const gate = currentGate(events, runId)
  if (!gate || gate.status !== 'waiting_human') throw new Error('G1 is not waiting for a human decision')
  if (decision.gate !== 'G1' || decision.attempt !== gate.attempt || decision.policyVersion !== G1_POLICY_VERSION)
    throw new Error('decision does not match the active G1 attempt or policy')
  const evidenceSetHash = computeG1EvidenceSetHash(events, runId, gate.attempt, G1_POLICY_VERSION)
  if (decision.evidenceSetHash !== evidenceSetHash) throw new Error('decision evidenceSetHash is stale or invalid')

  const actorId = await identityPort.actorId()
  if (typeof actorId !== 'string' || !actorId) throw new Error('verified actor identity is required')
  const authority = await identityPort.authorize({
    actorId,
    runId,
    gate: 'G1',
    attempt: gate.attempt,
    policyVersion: G1_POLICY_VERSION,
  })
  if (!authority || typeof authority.authorityId !== 'string') throw new Error('actor is not authorized to decide G1')

  const existing = events.find(
    (event) =>
      event.event === 'human_decision_recorded' &&
      event.runId === runId &&
      event.gate === 'G1' &&
      event.attempt === gate.attempt
  )
  const fingerprint = canonicalG1({
    outcome: decision.outcome,
    reasonCode: decision.reasonCode,
    evidenceSetHash,
    actorId,
    authorityId: authority.authorityId,
  })
  if (existing) {
    if (existing.idempotencyKey === fingerprint) return { status: 'idempotent', event: existing }
    throw new Error('a conflicting G1 decision already exists')
  }

  const event: ForgeLedgerEvent = {
    schemaVersion: 1,
    event: 'human_decision_recorded',
    decisionId: `decision_${randomUUID()}`,
    runId,
    gate: 'G1',
    attempt: gate.attempt,
    policyVersion: G1_POLICY_VERSION,
    evidenceSetHash,
    decision: {
      actorId,
      authorityId: authority.authorityId,
      outcome: decision.outcome,
      reasonCode: decision.reasonCode,
    },
    idempotencyKey: fingerprint,
    at: now(),
  }
  appendForgeLedgerEvent(filePath, event)
  return { status: 'recorded', event }
}

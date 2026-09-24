import { createHash, randomUUID } from 'node:crypto'
import { appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'

export const G1_POLICY_VERSION = 'forge-g1-human-v1'
const OUTCOMES = new Set(['approved', 'rejected'])
const REASON_CODES = new Set(['intent_confirmed', 'intent_rejected', 'scope_unclear', 'risk_not_accepted'])

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(',')}}`
  return JSON.stringify(value)
}

export function computeG1EvidenceSetHash(events, runId, attempt = 1, policyVersion = G1_POLICY_VERSION) {
  const evidence = events.filter(
    (event) =>
      (event.event === 'run_started' && event.runId === runId) ||
      (event.event === 'story_run_created' && event.parentRunId === runId) ||
      (event.event === 'gate_started' && event.runId === runId && event.gate === 'G1' && event.attempt === attempt)
  )
  return `sha256:${createHash('sha256').update(canonical({ policyVersion, evidence })).digest('hex')}`
}

function currentGate(events, runId) {
  return events.filter((event) => event.event === 'gate_started' && event.runId === runId && event.gate === 'G1').at(-1)
}

/**
 * Port boundary: identity and authority are verified outside the payload.
 * `authorize({ actorId, runId, gate, attempt, policyVersion })` must return a
 * verified authority object or null. The dashboard supplies the adapter.
 */
export async function recordHumanDecision({
  roots,
  runId,
  decision,
  identityPort,
  now = () => new Date().toISOString(),
}) {
  if (!identityPort || typeof identityPort.authorize !== 'function')
    throw new Error('an identity authorization port is required')
  if (!decision || typeof decision !== 'object') throw new Error('decision is required')
  if (!OUTCOMES.has(decision.outcome)) throw new Error('decision.outcome must be approved or rejected')
  if (!REASON_CODES.has(decision.reasonCode)) throw new Error('decision.reasonCode is invalid')
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
  const fingerprint = canonical({
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

  const event = {
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
  appendFileSync(filePath, `${JSON.stringify(event)}\n`, 'utf8')
  return { status: 'recorded', event }
}

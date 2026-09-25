/**
 * Pure G1 human-decision domain: the gate policy version, the accepted
 * outcome/reason vocabulary, canonical serialization and the deterministic
 * evidence-set hash.
 *
 * The append-only recording of a decision (identity port, ledger I/O) lives in
 * `application/forge-bmad/forge-human-decision.ts`.
 *
 * Domain purity: only `node:crypto` is used (deterministic hashing); no
 * `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

import { createHash } from 'node:crypto'
import type { ForgeLedgerEvent } from './types.js'

/** Policy version of the G1 human gate. */
export const G1_POLICY_VERSION = 'forge-g1-human-v1'

/** Accepted G1 decision outcomes. */
export const G1_OUTCOMES: ReadonlySet<string> = new Set(['approved', 'rejected'])

/** Accepted G1 decision reason codes. */
export const G1_REASON_CODES: ReadonlySet<string> = new Set([
  'intent_confirmed',
  'intent_rejected',
  'scope_unclear',
  'risk_not_accepted',
])

/**
 * Canonical JSON serialization: object keys sorted recursively, arrays
 * preserved in order. Two payloads that differ only by key order serialize
 * identically.
 */
export function canonicalG1(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalG1).join(',')}]`
  if (value && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalG1((value as Record<string, unknown>)[key])}`)
      .join(',')}}`
  return JSON.stringify(value) as string
}

/** Deterministic hash of the G1 evidence set for a run attempt. */
export function computeG1EvidenceSetHash(
  events: readonly ForgeLedgerEvent[],
  runId: string,
  attempt = 1,
  policyVersion = G1_POLICY_VERSION
): string {
  const evidence = events.filter(
    (event) =>
      (event.event === 'run_started' && event.runId === runId) ||
      (event.event === 'story_run_created' && event.parentRunId === runId) ||
      (event.event === 'gate_started' && event.runId === runId && event.gate === 'G1' && event.attempt === attempt)
  )
  return `sha256:${createHash('sha256').update(canonicalG1({ policyVersion, evidence })).digest('hex')}`
}

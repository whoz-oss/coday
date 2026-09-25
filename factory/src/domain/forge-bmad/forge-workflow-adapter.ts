/**
 * Pure Forge workflow adapter: deterministically transform one normalized
 * `readForgeRunYaml()` record into the generic workflow projection understood
 * by the dashboard and the workflow-projection store.
 *
 * The projection validator is the legacy pure module
 * `factory/lib/workflow-projection.mjs` (stateless, `node:crypto` only); the
 * adapter only calls it, it does not import any I/O.
 *
 * Domain purity: no `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

import { validateWorkflowProjection } from '../../../lib/workflow-projection.mjs'

/** Machine codes returned by the Forge workflow adapter. */
export const FORGE_WORKFLOW_ERROR_CODES = Object.freeze({
  INVALID_RUN: 'INVALID_FORGE_RUN',
  UNKNOWN_DECISION: 'UNKNOWN_FORGE_DECISION',
  UNKNOWN_OUTCOME: 'UNKNOWN_FORGE_OUTCOME',
  IMPOSSIBLE_GATE_ORDER: 'IMPOSSIBLE_FORGE_GATE_ORDER',
  INVALID_PROJECTION: 'INVALID_FORGE_PROJECTION',
})

const DECISIONS = new Map([
  ['approved', 'completed'],
  ['approved-with-changes', 'completed'],
  ['rejected', 'failed'],
])
const GATES = Object.freeze([
  ['gate_1', 'gate-1', 'Ticket'],
  ['gate_2', 'gate-2', 'Spec'],
  ['gate_3', 'gate-3', 'Tech Review'],
  ['gate_4', 'gate-4', 'Func Review'],
] as const)
const TICKET = /^[A-Z][A-Z0-9]+-\d+$/

function failure(code: string, path: string, details: Record<string, unknown> = {}): any {
  return { ok: false, error: { code, path, details } }
}

function validInstant(value: unknown): boolean {
  return typeof value === 'string' && value.trim().length > 0 && !Number.isNaN(Date.parse(value))
}

interface GateState {
  ok: boolean
  status?: string
  started?: boolean
  terminal?: boolean
  error?: { code: string; path: string; details: Record<string, unknown> }
}

function gateStatus(gate: any, path: string): GateState {
  if (!gate || typeof gate !== 'object') return { ok: true, status: 'pending', started: false, terminal: false }
  if (gate.startedAt !== null && gate.startedAt !== undefined && !validInstant(gate.startedAt))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.startedAt`, { reason: 'invalid_timestamp' })
  if (gate.decidedAt !== null && gate.decidedAt !== undefined && !validInstant(gate.decidedAt))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: 'invalid_timestamp' })
  if (gate.decidedAt && !gate.startedAt)
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: 'decided_without_start' })
  if (gate.startedAt && gate.decidedAt && Date.parse(gate.decidedAt) < Date.parse(gate.startedAt))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: 'decision_before_start' })
  const decision = gate.humanDecision
  if (decision !== null && decision !== undefined) {
    const status = DECISIONS.get(decision)
    if (!status)
      return failure(FORGE_WORKFLOW_ERROR_CODES.UNKNOWN_DECISION, `${path}.humanDecision`, { value: decision })
    if (!gate.startedAt || !gate.decidedAt)
      return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, path, { reason: 'decision_without_complete_timestamps' })
    return { ok: true, status, started: true, terminal: true }
  }
  if (gate.decidedAt)
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, {
      reason: 'decision_timestamp_without_decision',
    })
  return { ok: true, status: gate.startedAt ? 'running' : 'pending', started: !!gate.startedAt, terminal: false }
}

/** Deterministically adapt one normalized readForgeRunYaml() record. */
export function adaptForgeRunToWorkflowProjection(run: any): Record<string, any> {
  if (!run || typeof run !== 'object' || !TICKET.test(run.ticketId ?? ''))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, 'ticketId')
  const states: GateState[] = []
  for (const [source] of GATES) {
    const state = gateStatus(run.gates?.[source], `gates.${source}`)
    if (!state.ok) return state as Record<string, any>
    states.push(state)
  }
  for (let index = 1; index < states.length; index++) {
    if (states[index]!.started && states[index - 1]!.status !== 'completed') {
      return failure(FORGE_WORKFLOW_ERROR_CODES.IMPOSSIBLE_GATE_ORDER, `gates.${GATES[index]![0]}`, {
        precedingGate: GATES[index - 1]![0],
      })
    }
  }
  const outcome = run.runOutcome?.status
  if (!['in-progress', 'completed', 'abandoned'].includes(outcome))
    return failure(FORGE_WORKFLOW_ERROR_CODES.UNKNOWN_OUTCOME, 'runOutcome.status', { value: outcome })
  if (outcome === 'completed' && states.some((state) => state.status !== 'completed'))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, 'runOutcome.status', {
      reason: 'completed_before_all_gates_approved',
    })
  if (outcome === 'in-progress' && states.every((state) => state.status === 'completed'))
    return failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, 'runOutcome.status', {
      reason: 'all_gates_complete_but_run_in_progress',
    })

  let status: string
  if (outcome === 'completed') status = 'completed'
  else if (outcome === 'abandoned') status = 'cancelled'
  else if (states.some((state) => state.status === 'failed')) status = 'failed'
  else if (states.some((state) => state.status === 'running')) status = 'running'
  else if (states.some((state) => state.status === 'completed')) status = 'ready'
  else status = 'pending'

  const candidate = {
    schemaVersion: '1',
    workflowId: `forge-run-${run.ticketId}`,
    workflowType: 'forge-ticket-v1',
    title: run.ticketSummary?.trim() || run.ticketId,
    status,
    steps: GATES.map(([, id, name], index) => ({
      id,
      name,
      status: outcome === 'abandoned' && !states[index]!.terminal ? 'cancelled' : states[index]!.status,
      dependsOn: index === 0 ? [] : [GATES[index - 1]![1]],
    })),
  }
  const validated = validateWorkflowProjection(candidate) as any
  return validated.ok
    ? { ok: true, projection: validated.projection }
    : failure(FORGE_WORKFLOW_ERROR_CODES.INVALID_PROJECTION, validated.error.path, { validation: validated.error })
}

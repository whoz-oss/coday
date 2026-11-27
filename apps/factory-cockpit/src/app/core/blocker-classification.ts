import { WorkflowBlocker } from './models'

export type BlockerKind = 'waiting' | 'research' | 'failure' | 'consequence' | 'cost' | 'indeterminate' | 'unknown'

/**
 * Blockers bucketed by kind, preserving ALL root failures (never arbitrarily
 * picking a single first one). Each bucket keeps the input order.
 */
export interface BlockerGroups {
  failures: WorkflowBlocker[]
  consequences: WorkflowBlocker[]
  waiting: WorkflowBlocker[]
  research: WorkflowBlocker[]
  cost: WorkflowBlocker[]
  indeterminate: WorkflowBlocker[]
  unknown: WorkflowBlocker[]
}

/**
 * Classify a blocker into a severity/kind bucket.
 *
 * - `failure`: real execution failures (ATTEMPT_FAILED, VERIFICATION_FAILED,
 *   WORKSPACE_PRECHECK_FAILED) — the step or the run actually failed; operator attention is required.
 * - `consequence`: downstream blocks (STEP_BLOCKED) — a step is blocked as a
 *   consequence of an upstream failure; they are grouped as mitigated.
 * - `waiting`: human gate (WAITING_HUMAN_INTERACTION).
 * - `research`: the agent needs information or access (NEEDS_RESEARCH).
 * - `cost`: budget pause (REAL_COST_PAUSED).
 * - `indeterminate`: unknown runtime state (UNKNOWN_RUNTIME).
 * - `unknown`: anything else.
 */
export function blockerKind(blocker: WorkflowBlocker): BlockerKind {
  switch (blocker.code) {
    case 'WAITING_HUMAN_INTERACTION':
      return 'waiting'
    case 'NEEDS_RESEARCH':
      return 'research'
    case 'ATTEMPT_FAILED':
    case 'VERIFICATION_FAILED':
    case 'WORKSPACE_PRECHECK_FAILED':
      return 'failure'
    case 'STEP_BLOCKED':
      return 'consequence'
    case 'REAL_COST_PAUSED':
      return 'cost'
    case 'UNKNOWN_RUNTIME':
      return 'indeterminate'
    default:
      return 'unknown'
  }
}

/** Group blockers by kind, keeping the input order inside each group. */
export function classifyBlockers(blockers: readonly WorkflowBlocker[]): BlockerGroups {
  return {
    failures: blockers.filter((b) => blockerKind(b) === 'failure'),
    consequences: blockers.filter((b) => blockerKind(b) === 'consequence'),
    waiting: blockers.filter((b) => blockerKind(b) === 'waiting'),
    research: blockers.filter((b) => blockerKind(b) === 'research'),
    cost: blockers.filter((b) => blockerKind(b) === 'cost'),
    indeterminate: blockers.filter((b) => blockerKind(b) === 'indeterminate'),
    unknown: blockers.filter((b) => blockerKind(b) === 'unknown'),
  }
}

export function blockerIcon(blocker: WorkflowBlocker): string {
  switch (blockerKind(blocker)) {
    case 'waiting':
      return 'person_alert'
    case 'research':
      return 'info'
    case 'failure':
      return 'error'
    case 'consequence':
      return 'link_off'
    case 'cost':
      return 'paid'
    case 'indeterminate':
      return 'help'
    default:
      return 'warning'
  }
}

/** Title for a blocker group header. */
export function blockerGroupTitle(kind: Exclude<BlockerKind, 'research'>): string {
  switch (kind) {
    case 'failure':
      return 'Root failures'
    case 'consequence':
      return 'Blocked steps (consequence)'
    case 'waiting':
      return 'Waiting for human'
    case 'cost':
      return 'Budget pause'
    case 'indeterminate':
      return 'Indeterminate state'
    default:
      return 'Other blockers'
  }
}

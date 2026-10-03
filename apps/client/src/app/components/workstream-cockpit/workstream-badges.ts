import { AttemptStatus, BlockerCode } from '../../core/models/workstream.model'

/**
 * Pure badge helpers for the Workstream Cockpit.
 *
 * Enforce the visual-distinction rule across all views:
 * - `waiting_human` / `WAITING_HUMAN_INTERACTION` → warning (amber)
 * - `blocked` / `failed` / `STEP_BLOCKED` / `ATTEMPT_FAILED` / `VERIFICATION_FAILED` → error (red)
 * - `indeterminate` / `UNKNOWN_RUNTIME` → neutral (purple-grey)
 * - `succeeded` / `completed` → success (green)
 * - in-flight statuses (`pending`, `claiming`, `starting`, `running`) → info (blue-grey)
 * - `archived` / `runtime-closed` / `removed` / `purged` → muted (grey, Phase 10)
 *
 * The returned strings are BEM-style CSS classes (`ws-badge ws-badge--<tone>`)
 * styled locally by each component's SCSS.
 */

/** CSS classes for a durable attempt status badge. */
export function attemptBadgeClass(status: AttemptStatus): string {
  return `ws-badge ws-badge--${attemptBadgeTone(status)}`
}

/** CSS classes for a workflow blocker badge. */
export function blockerBadgeClass(code: BlockerCode): string {
  return `ws-badge ws-badge--${blockerBadgeTone(code)}`
}

/** Human-readable label for a blocker code. */
export function blockerLabel(code: BlockerCode): string {
  const labels: Record<BlockerCode, string> = {
    WAITING_HUMAN_INTERACTION: 'Waiting human',
    STEP_BLOCKED: 'Blocked',
    ATTEMPT_FAILED: 'Attempt failed',
    REAL_COST_PAUSED: 'Cost paused',
    VERIFICATION_FAILED: 'Verification failed',
    UNKNOWN_RUNTIME: 'Indeterminate',
  }
  return labels[code]
}

/** CSS classes for a workflow step / workflow status badge (statuses are plain strings in the DTO). */
export function stepBadgeClass(status: string): string {
  const toneByStatus: Record<string, string> = {
    // Phase 10 terminal / sealing vocabulary — completed is sealed success; archived
    // and runtime-closed are retired states, visually distinct from completed.
    completed: 'success',
    succeeded: 'success',
    archived: 'muted',
    'runtime-closed': 'muted',
    removed: 'muted',
    purged: 'muted',
    absent: 'muted',
    // In-flight / pending.
    running: 'info',
    ready: 'info',
    pending: 'info',
    claiming: 'info',
    starting: 'info',
    // Awaiting a human decision.
    waiting_human: 'warning',
    // Prevented / failed.
    blocked: 'error',
    failed: 'error',
    cancelled: 'muted',
    // Unknown runtime state must never look like a success or a hard failure.
    indeterminate: 'neutral',
  }
  return `ws-badge ws-badge--${toneByStatus[status] ?? 'neutral'}`
}

function attemptBadgeTone(status: AttemptStatus): string {
  switch (status) {
    case 'succeeded':
      return 'success'
    case 'failed':
    case 'interrupted':
      return 'error'
    case 'waiting_human':
      return 'warning'
    case 'indeterminate':
      return 'neutral'
    default:
      return 'info'
  }
}

function blockerBadgeTone(code: BlockerCode): string {
  switch (code) {
    case 'WAITING_HUMAN_INTERACTION':
      return 'warning'
    case 'STEP_BLOCKED':
    case 'ATTEMPT_FAILED':
    case 'VERIFICATION_FAILED':
      return 'error'
    case 'REAL_COST_PAUSED':
      return 'warning'
    case 'UNKNOWN_RUNTIME':
      return 'neutral'
    default:
      return 'neutral'
  }
}

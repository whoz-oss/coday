import { FactoryRun, RunCardState } from '../../core/models'

export type RunStatusKey = RunCardState | 'destroyed'
export type RunStatusFilter = 'all' | RunStatusKey

/** Display order and labels of the filter chips (English UI, #1710). */
export const RUN_STATUS_OPTIONS: ReadonlyArray<{ value: RunStatusKey; label: string }> = [
  { value: 'running', label: 'Running' },
  { value: 'pending', label: 'Waiting' },
  { value: 'waiting_human', label: 'Waiting for human' },
  { value: 'blocked', label: 'Blocked' },
  { value: 'failed', label: 'Failed' },
  { value: 'succeeded', label: 'Completed' },
  { value: 'destroyed', label: 'Destroyed' },
]

/**
 * Effective status bucket of a run. Same precedence as the run card chip
 * (`run-card.component.html`): destroyed first, then `cardState`, default pending ("waiting").
 */
export function runStatusKey(run: FactoryRun): RunStatusKey {
  if (run.status === 'destroyed') return 'destroyed'
  return run.run?.cardState ?? 'pending'
}

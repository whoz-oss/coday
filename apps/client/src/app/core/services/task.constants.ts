import { TaskStatus } from './task-status.service'

/** Threads modified within this many ms are considered potentially in-progress */
export const IN_PROGRESS_THRESHOLD_MS = 5 * 60 * 1000

/** Sort priority for task statuses — lower value = higher priority */
export const TASK_STATUS_PRIORITY: Record<TaskStatus, number> = {
  'waiting-you': 0,
  'in-progress': 1,
  done: 2,
  paused: 3,
  error: 4,
}

import { MissionStatus } from './mission-status.service'

/** Threads modified within this many ms are considered potentially in-progress */
export const IN_PROGRESS_THRESHOLD_MS = 5 * 60 * 1000

/** Threads older than this are considered done even without a summary or price */
export const DONE_THRESHOLD_MS = 24 * 60 * 60 * 1000 // 24 hours

/** Sort priority for mission statuses — lower value = higher priority */
export const MISSION_STATUS_PRIORITY: Record<MissionStatus, number> = {
  'waiting-you': 0,
  'in-progress': 1,
  done: 2,
  paused: 3,
  error: 4,
}

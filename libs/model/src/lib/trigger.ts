/**
 * Trigger model - Scheduled execution of webhooks using interval-based scheduling
 */

export interface IntervalSchedule {
  startTimestamp: string // ISO 8601 UTC
  interval: string // '2min', '5h', '14d', '1M' (min=minutes, h=hours, d=days, M=months)
  daysOfWeek?: number[] // 0-6 (0=Sunday, 6=Saturday), optional
  endCondition?: {
    type: 'occurrences' | 'endTimestamp'
    value: number | string // number for occurrences, ISO 8601 for timestamp
  }
}

export interface Trigger {
  id: string
  name: string
  enabled: boolean
  webhookUuid: string // Reference to existing webhook
  schedule: IntervalSchedule // Interval-based schedule
  parameters?: Record<string, unknown> // Optional parameters to override webhook defaults
  createdBy: string
  createdAt: string // ISO 8601
  lastRun?: string // ISO 8601
  nextRun?: string | null // ISO 8601 - calculated (null if no more occurrences)
  occurrenceCount?: number // Internal counter for occurrence-based limits
}

export interface TriggerInfo {
  id: string
  name: string
  enabled: boolean
  webhookUuid: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
  createdBy: string // Owner of the trigger
}

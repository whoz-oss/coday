/**
 * Trigger model - Scheduled execution of webhooks
 */

export interface Trigger {
  id: string
  name: string
  enabled: boolean
  webhookUuid: string // Reference to existing webhook
  schedule: string // Cron expression
  parameters?: Record<string, unknown> // Optional parameters to override webhook defaults
  createdBy: string
  createdAt: string // ISO 8601
  lastRun?: string // ISO 8601
  nextRun?: string // ISO 8601 - calculated
}

export interface TriggerInfo {
  id: string
  name: string
  enabled: boolean
  webhookUuid: string
  schedule: string
  lastRun?: string
  nextRun?: string
}

/**
 * Result of cron parsing
 */
export interface CronSchedule {
  minute: number | '*' | number[] // Specific minute, wildcard, or array of minutes
  hour: number | '*' | number[] // Specific hour, wildcard, or array of hours
  dayOfMonth: number | '*' // Day of month (not used in MVP)
  month: number | '*' // Month (not used in MVP)
  dayOfWeek: number | '*' // Day of week (0 = Sunday)
}

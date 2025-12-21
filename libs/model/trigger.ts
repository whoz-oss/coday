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

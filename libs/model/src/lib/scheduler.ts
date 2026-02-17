/**
 * Scheduler model - Automated execution of prompts
 *
 * Schedulers define when and how prompts should be executed automatically.
 * Each scheduler is owned by a user (createdBy), and executions run with that user's identity.
 */

/**
 * Interval-based schedule configuration
 */
export interface IntervalSchedule {
  /**
   * Start timestamp for the schedule (ISO 8601 UTC)
   */
  startTimestamp: string

  /**
   * Interval between executions
   * Format: '2min', '5h', '14d', '1M'
   * - min = minutes
   * - h = hours
   * - d = days
   * - M = months
   */
  interval: string

  /**
   * Optional days of week restriction (0-6, 0=Sunday, 6=Saturday)
   * If specified, scheduler only runs on these days
   */
  daysOfWeek?: number[]

  /**
   * Optional end condition to stop the scheduler
   */
  endCondition?: {
    /**
     * Type of end condition
     * - 'occurrences': Stop after N executions
     * - 'endTimestamp': Stop after a specific date/time
     */
    type: 'occurrences' | 'endTimestamp'

    /**
     * Value for the end condition
     * - number for 'occurrences' type
     * - ISO 8601 string for 'endTimestamp' type
     */
    value: number | string
  }
}

/**
 * Scheduler definition
 */
export interface Scheduler {
  /**
   * Unique identifier (UUID v4)
   */
  id: string

  /**
   * Human-readable name
   */
  name: string

  /**
   * Whether this scheduler is active
   */
  enabled: boolean

  /**
   * Reference to the prompt to execute
   */
  promptId: string

  /**
   * Schedule configuration
   */
  schedule: IntervalSchedule

  /**
   * Optional parameters to pass to the prompt execution
   * These override or provide values for template placeholders
   *
   * Parameter format:
   * - For structured placeholders ({{key}}): Use object with matching keys
   *   Example: { prNumber: "123", project: "coday" }
   *
   * - For simple trailing parameter ({{PARAMETERS}} or no placeholders):
   *   You can use a simple object with a single PARAMETERS key:
   *   Example: { PARAMETERS: "my value" }
   *   This will be automatically converted to the string "my value" during execution
   */
  parameters?: Record<string, unknown>

  /**
   * User who created this scheduler (and owns the executions)
   * All scheduled executions run with this user's identity and permissions
   */
  createdBy: string

  /**
   * Creation timestamp (ISO 8601)
   */
  createdAt: string

  /**
   * Last execution timestamp (ISO 8601)
   */
  lastRun?: string

  /**
   * Next scheduled execution timestamp (ISO 8601)
   * Null if scheduler has expired (no more occurrences)
   */
  nextRun?: string | null

  /**
   * Internal counter for occurrence-based limits
   */
  occurrenceCount?: number
}

/**
 * Scheduler info for listing (subset of Scheduler)
 */
export interface SchedulerInfo {
  id: string
  name: string
  enabled: boolean
  promptId: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
  createdBy: string
}

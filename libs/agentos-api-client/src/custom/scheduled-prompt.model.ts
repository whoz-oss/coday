/**
 * ScheduledPrompt — hand-written model for the Agentic Scheduler feature.
 *
 * Matches the backend ScheduledPromptDto at /api/scheduled-prompts.
 * agentConfigId references an existing AgentConfig entity.
 * promptContent holds the opening message sent to the agent when the case fires;
 * the backend manages the lifecycle of the underlying Prompt entity transparently.
 *
 * Scope is determined by (namespaceId, userId):
 *   (null, null)   → platform
 *   (ns,   null)   → namespace-shared
 *   (null, user)   → user-global
 *   (ns,   user)   → user × namespace
 *
 * This lives in src/custom/ to survive OpenAPI client regeneration.
 */

// ---------------------------------------------------------------------------
// Scheduler enums — shared across scheduling UI
// ---------------------------------------------------------------------------

export enum SchedulerUnit {
  DAY = 'DAY',
  WEEK = 'WEEK',
  MONTH = 'MONTH',
}

export enum SchedulerEndType {
  NEVER = 'NEVER',
  ON_DATE = 'ON_DATE',
  OCCURRENCES = 'OCCURRENCES',
}

/** ISO day-of-week values, Monday-first. */
export enum DayOfWeek {
  MON = 'MON',
  TUE = 'TUE',
  WED = 'WED',
  THU = 'THU',
  FRI = 'FRI',
  SAT = 'SAT',
  SUN = 'SUN',
}

// ---------------------------------------------------------------------------
// Scheduler sub-structures
// ---------------------------------------------------------------------------

export interface SchedulerRecurrence {
  /** Interval value, e.g. 3 (every 3 days). Min 1. */
  every: number
  /** Recurrence unit: DAY | WEEK | MONTH */
  unit: SchedulerUnit
  /** Additional day-of-week filter. Empty array = no filter. Meaningful when unit is WEEK. */
  days: DayOfWeek[]
  /** Time of day in UTC, format HH:mm (e.g. "09:30"). */
  timeUtc: string
}

export interface SchedulerPlanning {
  /** ISO date string (UTC) when the schedule starts. */
  startDate: string
  /** When the schedule ends: NEVER | ON_DATE | OCCURRENCES */
  endType: SchedulerEndType
  /** ISO date string (UTC). Only present when endType === ON_DATE. */
  endDate?: string | null
  /** Number of occurrences before the schedule stops. Only present when endType === OCCURRENCES. */
  occurrenceCount?: number | null
}

// ---------------------------------------------------------------------------
// ScheduledPrompt DTO
// ---------------------------------------------------------------------------

export interface ScheduledPrompt {
  id?: string
  namespaceId?: string | null
  /** userId — reserved for per-user scope. Usually null for namespace/platform scopes. */
  userId?: string | null
  /** Reference to an AgentConfig entity. Always required. */
  agentConfigId: string
  /** Opening message sent to the agent when the scheduled case fires. The backend manages the underlying Prompt entity transparently. */
  promptContent: string
  /** Slug name: ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ — validated on create, not on update. */
  name: string
  description?: string
  recurrence: SchedulerRecurrence
  planning: SchedulerPlanning
  enabled: boolean
  /**
   * Read-only server timestamps — present in GET responses, never sent in POST/PUT payloads.
   * Declared readonly to prevent accidental inclusion in request bodies.
   */
  readonly createdAt?: string
  readonly updatedAt?: string
  /** ISO instant of the next scheduled execution. Always present once the entity is persisted. */
  readonly nextRunAt?: string
  /** ISO instant of the last execution. Null/absent if the prompt has never run. */
  readonly lastRunAt?: string | null
}

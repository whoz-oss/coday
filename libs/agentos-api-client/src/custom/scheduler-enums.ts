/**
 * Scheduler enums — stable aliases over the generated OpenAPI enums.
 *
 * Lives in src/custom/ so it survives client regeneration.
 * Consuming code imports from here rather than from the generated model files,
 * which avoids breakage when the generator renames inline enums.
 */
import { RecurrenceDaysEnum, RecurrenceUnitEnum } from '../lib/model/recurrence'
import { PlanningEndTypeEnum } from '../lib/model/planning'

/** Recurrence unit: DAY | WEEK | MONTH */
export const SchedulerUnit = RecurrenceUnitEnum
export type SchedulerUnit = RecurrenceUnitEnum

/** Schedule end condition: NEVER | ON_DATE | OCCURRENCES */
export const SchedulerEndType = PlanningEndTypeEnum
export type SchedulerEndType = PlanningEndTypeEnum

/**
 * Day-of-week filter for weekly recurrences.
 * Note: the generated enum uses full names (MONDAY…SUNDAY).
 */
export const DayOfWeek = RecurrenceDaysEnum
export type DayOfWeek = RecurrenceDaysEnum

/**
 * Human-readable short labels for each DayOfWeek value.
 * Used by form and item components.
 */
export const DAY_OF_WEEK_LABELS: Record<DayOfWeek, string> = {
  [RecurrenceDaysEnum.MONDAY]: 'Mon',
  [RecurrenceDaysEnum.TUESDAY]: 'Tue',
  [RecurrenceDaysEnum.WEDNESDAY]: 'Wed',
  [RecurrenceDaysEnum.THURSDAY]: 'Thu',
  [RecurrenceDaysEnum.FRIDAY]: 'Fri',
  [RecurrenceDaysEnum.SATURDAY]: 'Sat',
  [RecurrenceDaysEnum.SUNDAY]: 'Sun',
}

/**
 * Human-readable full names for each DayOfWeek value.
 * Used by the item component schedule label.
 */
export const DAY_OF_WEEK_FULL_LABELS: Record<DayOfWeek, string> = {
  [RecurrenceDaysEnum.MONDAY]: 'Monday',
  [RecurrenceDaysEnum.TUESDAY]: 'Tuesday',
  [RecurrenceDaysEnum.WEDNESDAY]: 'Wednesday',
  [RecurrenceDaysEnum.THURSDAY]: 'Thursday',
  [RecurrenceDaysEnum.FRIDAY]: 'Friday',
  [RecurrenceDaysEnum.SATURDAY]: 'Saturday',
  [RecurrenceDaysEnum.SUNDAY]: 'Sunday',
}

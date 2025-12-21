/**
 * Cron utilities for parsing and calculating schedules
 *
 * Supports MVP patterns:
 * - Every X minutes: star-slash-X (e.g., for every 15 minutes)
 * - Every X hours: 0 star-slash-X (e.g., for every 2 hours)
 * - Daily at midnight (UTC): 0 0 star star star
 * - Weekly on Sunday (UTC): 0 0 star star 0
 */

export interface CronSchedule {
  minute: number | '*' | number[] // Specific minute, wildcard, or array of minutes
  hour: number | '*' | number[] // Specific hour, wildcard, or array of hours
  dayOfMonth: number | '*' // Day of month (not used in MVP)
  month: number | '*' // Month (not used in MVP)
  dayOfWeek: number | '*' // Day of week (0 = Sunday)
}

/**
 * Parse a cron expression into a CronSchedule object
 *
 * Supported patterns (MVP):
 * - Every X minutes (e.g. star-slash-15 for every 15 min)
 * - Every X hours (e.g. 0 star-slash-2 for every 2 hours)
 * - Daily at midnight: 0 0 star star star
 * - Weekly on Sunday: 0 0 star star 0
 *
 * @param cronExpression - Cron expression string (5 fields: minute hour day month weekday)
 * @returns Parsed schedule or null if invalid
 *
 * @example
 * // Every 15 minutes: parseCronExpression('star-slash-15 star star star star')
 * // Every 2 hours: parseCronExpression('0 star-slash-2 star star star')
 * parseCronExpression('0 0 * * *')    // Daily at midnight
 * parseCronExpression('0 0 * * 0')    // Weekly on Sunday
 */
export function parseCronExpression(cronExpression: string): CronSchedule | null {
  const parts = cronExpression.trim().split(/\s+/)

  if (parts.length !== 5) {
    return null // Invalid format
  }

  const [minute, hour, dayOfMonth, month, dayOfWeek] = parts

  try {
    return {
      minute: parseCronField(minute ?? '*', 0, 59),
      hour: parseCronField(hour ?? '*', 0, 23),
      dayOfMonth: (dayOfMonth ?? '*') === '*' ? '*' : parseInt(dayOfMonth ?? '1', 10),
      month: (month ?? '*') === '*' ? '*' : parseInt(month ?? '1', 10),
      dayOfWeek: (dayOfWeek ?? '*') === '*' ? '*' : parseInt(dayOfWeek ?? '0', 10),
    }
  } catch {
    return null
  }
}

/**
 * Parse a single cron field and return its representation
 *
 * Handles three formats:
 * - star : Wildcard, matches any value
 * - star-slash-X : Interval pattern, matches every X units
 * - N : Single number, matches exactly that value
 *
 * @param field - The cron field string to parse
 * @param min - Minimum valid value for this field
 * @param max - Maximum valid value for this field
 * @returns Parsed field as wildcard, single number, or array of numbers
 * @throws Error if field is invalid or out of range
 *
 * @example
 * parseCronField('*', 0, 59)      // returns wildcard
 * // parseCronField('star-slash-15', 0, 59) returns [0, 15, 30, 45]
 * parseCronField('30', 0, 59)     // returns 30
 * parseCronField('99', 0, 59)     // throws Error
 */
function parseCronField(field: string, min: number, max: number): number | '*' | number[] {
  if (field === '*') return '*'

  // Handle */X pattern (every X units)
  if (field.startsWith('*/')) {
    const interval = parseInt(field.substring(2), 10)
    if (isNaN(interval) || interval < 1) throw new Error('Invalid interval')

    const values: number[] = []
    for (let i = min; i <= max; i += interval) {
      values.push(i)
    }
    return values
  }

  // Handle single number
  const num = parseInt(field, 10)
  if (isNaN(num) || num < min || num > max) {
    throw new Error(`Value out of range: ${field}`)
  }
  return num
}

/**
 * Calculate the next run time based on cron schedule
 *
 * @param cronExpression - Cron expression string
 * @param fromDate - Starting date (default: now)
 * @returns ISO 8601 timestamp of next run
 * @throws Error if expression is invalid or next run cannot be calculated
 *
 * @example
 * // calculateNextRun('star-slash-15 star star star star') // Next 15-minute mark
 * calculateNextRun('0 0 * * *')              // Next midnight UTC
 * calculateNextRun('0 0 * * 0')              // Next Sunday midnight UTC
 * // calculateNextRun('star-slash-5 star star star star', new Date()) // Next 5-minute mark
 */
export function calculateNextRun(cronExpression: string, fromDate: Date = new Date()): string {
  const schedule = parseCronExpression(cronExpression)

  if (!schedule) {
    throw new Error(`Invalid cron expression: ${cronExpression}`)
  }

  // Start from next minute (round up)
  const next = new Date(fromDate)
  next.setSeconds(0, 0)
  next.setMinutes(next.getMinutes() + 1)

  // Find next matching time (max 1 year ahead to avoid infinite loop)
  const maxIterations = 525600 // minutes in a year
  let iterations = 0

  while (iterations < maxIterations) {
    if (matchesSchedule(next, schedule)) {
      return next.toISOString()
    }

    next.setMinutes(next.getMinutes() + 1)
    iterations++
  }

  throw new Error('Could not calculate next run time within reasonable timeframe')
}

/**
 * Check if a date matches a cron schedule
 *
 * @param date - Date to check
 * @param schedule - Parsed cron schedule
 * @returns true if date matches the schedule
 *
 * @example
 * const schedule = parseCronExpression('0 0 * * 0')
 * const sunday = new Date('2024-01-07T00:00:00Z') // Sunday
 * matchesSchedule(sunday, schedule) // true
 */
export function matchesSchedule(date: Date, schedule: CronSchedule): boolean {
  // Check minute
  if (!matchesField(date.getUTCMinutes(), schedule.minute)) return false

  // Check hour
  if (!matchesField(date.getUTCHours(), schedule.hour)) return false

  // Check day of week (0 = Sunday)
  if (schedule.dayOfWeek !== '*') {
    if (!matchesField(date.getUTCDay(), schedule.dayOfWeek)) return false
  }

  // For MVP, we ignore dayOfMonth and month (always match)

  return true
}

/**
 * Check if a value matches a cron field
 *
 * @param value - The actual value to check (e.g., current minute)
 * @param field - The cron field pattern (wildcard, number, or array)
 * @returns true if value matches the field pattern
 *
 * @example
 * matchesField(15, '*')           // true (wildcard matches everything)
 * matchesField(15, 15)            // true (exact match)
 * matchesField(15, [0, 15, 30])   // true (value in array)
 * matchesField(15, 30)            // false (no match)
 */
function matchesField(value: number, field: number | '*' | number[]): boolean {
  if (field === '*') return true
  if (typeof field === 'number') return value === field
  return field.includes(value)
}

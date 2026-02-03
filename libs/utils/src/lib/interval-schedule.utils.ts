/**
 * Interval-based scheduling utilities
 *
 * Supports flexible scheduling with:
 * - Start timestamp (UTC)
 * - Interval: 1h-24h (hours), 1d-31d (days), 1m-12m (months)
 * - Optional days of week constraint (0=Sunday, 6=Saturday)
 * - Optional end condition (occurrences or end timestamp)
 */

import type { IntervalSchedule } from '@coday/model'

interface ParsedInterval {
  value: number
  unit: 'h' | 'd' | 'm'
}

/**
 * Validate an interval string
 *
 * Rules:
 * - Hours: 1h to 24h (then use days)
 * - Days: 1d to 31d (then use months)
 * - Months: 1m to 12m (max one year)
 *
 * @param interval - Interval string like '5h', '14d', '1m'
 * @returns true if valid
 *
 * @example
 * validateInterval('5h')   // true
 * validateInterval('14d')  // true
 * validateInterval('1m')   // true
 * validateInterval('25h')  // false (use days)
 * validateInterval('32d')  // false (use months)
 * validateInterval('13m')  // false (max 12 months)
 */
export function validateInterval(interval: string): boolean {
  const parsed = parseInterval(interval)
  if (!parsed) return false

  const { value, unit } = parsed

  switch (unit) {
    case 'h':
      return value >= 1 && value <= 24
    case 'd':
      return value >= 1 && value <= 31
    case 'm':
      return value >= 1 && value <= 12
    default:
      return false
  }
}

/**
 * Parse an interval string into value and unit
 *
 * @param interval - Interval string
 * @returns Parsed interval or null if invalid format
 */
function parseInterval(interval: string): ParsedInterval | null {
  const match = interval.match(/^(\d+)(h|d|m)$/)
  if (!match || !match[1] || !match[2]) return null

  const value = parseInt(match[1], 10)
  const unit = match[2] as 'h' | 'd' | 'm'

  if (isNaN(value) || value < 1) return null

  return { value, unit }
}

/**
 * Validate days of week array
 *
 * @param daysOfWeek - Array of day numbers (0-6)
 * @returns true if valid
 */
export function validateDaysOfWeek(daysOfWeek?: number[]): boolean {
  if (!daysOfWeek) return true // Optional field
  if (!Array.isArray(daysOfWeek)) return false
  if (daysOfWeek.length === 0) return false

  return daysOfWeek.every((day) => Number.isInteger(day) && day >= 0 && day <= 6)
}

/**
 * Validate an interval schedule configuration
 *
 * @param schedule - Schedule configuration to validate
 * @returns true if valid, error message if invalid
 */
export function validateIntervalSchedule(schedule: IntervalSchedule): { valid: boolean; error?: string } {
  // Validate startTimestamp
  try {
    const start = new Date(schedule.startTimestamp)
    if (isNaN(start.getTime())) {
      return { valid: false, error: 'Invalid startTimestamp format' }
    }
  } catch {
    return { valid: false, error: 'Invalid startTimestamp format' }
  }

  // Validate interval
  if (!validateInterval(schedule.interval)) {
    return {
      valid: false,
      error: 'Invalid interval. Use 1h-24h, 1d-31d, or 1m-12m',
    }
  }

  // Validate daysOfWeek
  if (!validateDaysOfWeek(schedule.daysOfWeek)) {
    return {
      valid: false,
      error: 'Invalid daysOfWeek. Must be array of integers 0-6',
    }
  }

  // Validate endCondition
  if (schedule.endCondition) {
    const { type, value } = schedule.endCondition

    if (type === 'occurrences') {
      if (typeof value !== 'number' || value < 1 || !Number.isInteger(value)) {
        return { valid: false, error: 'Occurrences must be a positive integer' }
      }
    } else if (type === 'endTimestamp') {
      try {
        const end = new Date(value as string)
        if (isNaN(end.getTime())) {
          return { valid: false, error: 'Invalid endTimestamp format' }
        }
        const start = new Date(schedule.startTimestamp)
        if (end <= start) {
          return { valid: false, error: 'endTimestamp must be after startTimestamp' }
        }
      } catch {
        return { valid: false, error: 'Invalid endTimestamp format' }
      }
    } else {
      return { valid: false, error: 'endCondition type must be "occurrences" or "endTimestamp"' }
    }
  }

  return { valid: true }
}

/**
 * Calculate the next run time based on interval schedule
 *
 * Takes into account:
 * - Current occurrence count (for occurrences limit)
 * - Days of week constraint (skips non-matching days)
 * - End timestamp (stops if exceeded)
 *
 * @param schedule - Interval schedule configuration
 * @param fromDate - Starting date (default: now)
 * @param currentOccurrences - Current occurrence count (default: 0)
 * @returns ISO 8601 timestamp of next run, or null if no more occurrences
 *
 * @example
 * // Every 14 days on Friday
 * calculateNextRun({
 *   startTimestamp: '2025-01-03T09:00:00Z',
 *   interval: '14d',
 *   daysOfWeek: [5]
 * })
 */
export function calculateNextRun(
  schedule: IntervalSchedule,
  fromDate: Date = new Date(),
  currentOccurrences: number = 0
): string | null {
  const parsed = parseInterval(schedule.interval)
  if (!parsed) throw new Error(`Invalid interval: ${schedule.interval}`)

  const start = new Date(schedule.startTimestamp)
  let next = new Date(fromDate)

  // If we're before start, next run is start time
  if (next < start) {
    next = new Date(start)
  } else {
    // Calculate next occurrence based on interval
    next = addInterval(next, parsed)
  }

  // Check end conditions before searching for valid day
  if (schedule.endCondition) {
    if (schedule.endCondition.type === 'occurrences') {
      const maxOccurrences = schedule.endCondition.value as number
      if (currentOccurrences >= maxOccurrences) {
        return null // Max occurrences reached
      }
    } else if (schedule.endCondition.type === 'endTimestamp') {
      const endDate = new Date(schedule.endCondition.value as string)
      if (next > endDate) {
        return null // Past end date
      }
    }
  }

  // If daysOfWeek constraint, find next matching day
  if (schedule.daysOfWeek && schedule.daysOfWeek.length > 0) {
    const maxIterations = 365 // Prevent infinite loop (1 year max search)
    let iterations = 0

    while (iterations < maxIterations) {
      const dayOfWeek = next.getUTCDay()

      if (schedule.daysOfWeek.includes(dayOfWeek)) {
        // Check end conditions again after finding valid day
        if (schedule.endCondition?.type === 'endTimestamp') {
          const endDate = new Date(schedule.endCondition.value as string)
          if (next > endDate) {
            return null
          }
        }
        return next.toISOString()
      }

      // Move to next day to find matching day of week
      next.setUTCDate(next.getUTCDate() + 1)
      iterations++
    }

    throw new Error('Could not find next valid day within reasonable timeframe')
  }

  return next.toISOString()
}

/**
 * Add an interval to a date
 *
 * @param date - Base date
 * @param interval - Parsed interval
 * @returns New date with interval added
 */
function addInterval(date: Date, interval: ParsedInterval): Date {
  const result = new Date(date)

  switch (interval.unit) {
    case 'h':
      result.setUTCHours(result.getUTCHours() + interval.value)
      break
    case 'd':
      result.setUTCDate(result.getUTCDate() + interval.value)
      break
    case 'm':
      // Add months, handling day overflow (e.g., Jan 31 -> Feb 28/29)
      const currentDay = result.getUTCDate()
      result.setUTCMonth(result.getUTCMonth() + interval.value)

      // If day changed due to month overflow, skip this occurrence
      // (e.g., Jan 31 + 1m would become Mar 3, we detect this and handle in calculateNextRun)
      if (result.getUTCDate() !== currentDay) {
        // Date rolled over (e.g., Jan 31 -> Mar 3), reset to last day of target month
        result.setUTCDate(0) // Go to last day of previous month
      }
      break
  }

  return result
}

/**
 * Check if a trigger should execute now based on its schedule
 *
 * @param schedule - Interval schedule
 * @param nextRun - Next scheduled run time
 * @param currentOccurrences - Current occurrence count
 * @returns true if should execute
 */
export function shouldExecuteNow(
  schedule: IntervalSchedule,
  nextRun: string | null,
  currentOccurrences: number = 0
): boolean {
  if (!nextRun) return false // No more occurrences

  const now = new Date()
  const nextRunDate = new Date(nextRun)

  // Execute if nextRun is in the past or now
  if (nextRunDate > now) return false

  // Check if we've hit occurrence limit
  if (schedule.endCondition?.type === 'occurrences') {
    const maxOccurrences = schedule.endCondition.value as number
    if (currentOccurrences >= maxOccurrences) return false
  }

  // Check if we've passed end timestamp
  if (schedule.endCondition?.type === 'endTimestamp') {
    const endDate = new Date(schedule.endCondition.value as string)
    if (now > endDate) return false
  }

  return true
}

import { validateInterval, validateIntervalSchedule, calculateNextRun } from './interval-schedule.utils'
import type { IntervalSchedule } from '@coday/model'

describe('interval-schedule.utils', () => {
  describe('validateInterval', () => {
    it('should validate minutes intervals (1-59)', () => {
      expect(validateInterval('1min')).toBe(true)
      expect(validateInterval('30min')).toBe(true)
      expect(validateInterval('59min')).toBe(true)
      expect(validateInterval('60min')).toBe(false) // Use hours
      expect(validateInterval('0min')).toBe(false)
    })

    it('should validate hours intervals (1-24)', () => {
      expect(validateInterval('1h')).toBe(true)
      expect(validateInterval('12h')).toBe(true)
      expect(validateInterval('24h')).toBe(true)
      expect(validateInterval('25h')).toBe(false) // Use days
      expect(validateInterval('0h')).toBe(false)
    })

    it('should validate days intervals (1-31)', () => {
      expect(validateInterval('1d')).toBe(true)
      expect(validateInterval('14d')).toBe(true)
      expect(validateInterval('31d')).toBe(true)
      expect(validateInterval('32d')).toBe(false) // Use months
      expect(validateInterval('0d')).toBe(false)
    })

    it('should validate months intervals (1-12)', () => {
      expect(validateInterval('1M')).toBe(true)
      expect(validateInterval('6M')).toBe(true)
      expect(validateInterval('12M')).toBe(true)
      expect(validateInterval('13M')).toBe(false) // Max 12 months
      expect(validateInterval('0M')).toBe(false)
    })
  })

  describe('validateIntervalSchedule', () => {
    it('should validate a basic schedule', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1h',
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(true)
    })

    it('should reject invalid interval', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '60min', // Should use hours
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(false)
      expect(result.error).toContain('Invalid interval')
    })

    it('should validate daysOfWeek constraint', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1d',
        daysOfWeek: [1, 3, 5], // Mon, Wed, Fri
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(true)
    })

    it('should reject invalid daysOfWeek', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1d',
        daysOfWeek: [7, 8], // Invalid days
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(false)
    })

    it('should validate occurrences end condition', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1h',
        endCondition: {
          type: 'occurrences',
          value: 10,
        },
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(true)
    })

    it('should validate endTimestamp end condition', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1h',
        endCondition: {
          type: 'endTimestamp',
          value: '2025-12-31T23:59:59Z',
        },
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(true)
    })

    it('should reject endTimestamp before startTimestamp', () => {
      const schedule: IntervalSchedule = {
        startTimestamp: '2025-01-01T00:00:00Z',
        interval: '1h',
        endCondition: {
          type: 'endTimestamp',
          value: '2024-12-31T23:59:59Z', // Before start
        },
      }
      const result = validateIntervalSchedule(schedule)
      expect(result.valid).toBe(false)
      expect(result.error).toContain('must be after')
    })
  })

  describe('calculateNextRun', () => {
    describe('Minutes intervals', () => {
      it('should calculate next run for 30min interval', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z',
          interval: '30min',
        }
        const fromDate = new Date('2025-01-01T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBe('2025-01-01T10:30:00.000Z')
      })

      it('should respect daysOfWeek with minutes interval', () => {
        // Start on Wednesday Jan 1st 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z', // Wednesday
          interval: '30min',
          daysOfWeek: [3], // Only Wednesday
        }
        const fromDate = new Date('2025-01-01T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // Should be 30min later, same day (Wednesday)
        expect(nextRun).toBe('2025-01-01T10:30:00.000Z')
      })
    })

    describe('Hours intervals', () => {
      it('should calculate next run for 6h interval', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T00:00:00Z',
          interval: '6h',
        }
        const fromDate = new Date('2025-01-01T00:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBe('2025-01-01T06:00:00.000Z')
      })

      it('should respect daysOfWeek with hours interval', () => {
        // Start on Friday Jan 3rd 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-03T10:00:00Z', // Friday
          interval: '6h',
          daysOfWeek: [5], // Only Friday
        }
        const fromDate = new Date('2025-01-03T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // Should be 6h later, same day (Friday)
        expect(nextRun).toBe('2025-01-03T16:00:00.000Z')
      })
    })

    describe('Days intervals', () => {
      it('should calculate next run for 7d interval', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z',
          interval: '7d',
        }
        const fromDate = new Date('2025-01-01T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBe('2025-01-08T10:00:00.000Z')
      })

      it('should respect daysOfWeek with 14d interval starting on Friday', () => {
        // Start on Friday Jan 3rd 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-03T10:00:00Z', // Friday
          interval: '14d',
          daysOfWeek: [5], // Only Friday
        }
        const fromDate = new Date('2025-01-03T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // 14 days from Friday should land on Friday
        expect(nextRun).toBe('2025-01-17T10:00:00.000Z')

        // Verify it's indeed a Friday
        const nextDate = new Date(nextRun!)
        expect(nextDate.getUTCDay()).toBe(5) // Friday
      })

      it('should find next valid day when interval does not match daysOfWeek', () => {
        // Start on Monday Jan 6th 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-06T10:00:00Z', // Monday
          interval: '10d',
          daysOfWeek: [1], // Only Monday
        }
        const fromDate = new Date('2025-01-06T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // 10 days from Monday lands on Thursday
        // Should keep adding 10d until we find a Monday
        // Jan 6 (Mon) + 10d = Jan 16 (Thu)
        // Jan 16 + 10d = Jan 26 (Sun)
        // Jan 26 + 10d = Feb 5 (Wed)
        // Feb 5 + 10d = Feb 15 (Sat)
        // Feb 15 + 10d = Feb 25 (Tue)
        // Feb 25 + 10d = Mar 7 (Fri)
        // Mar 7 + 10d = Mar 17 (Mon) ✓
        expect(nextRun).toBe('2025-03-17T10:00:00.000Z')

        // Verify it's indeed a Monday
        const nextDate = new Date(nextRun!)
        expect(nextDate.getUTCDay()).toBe(1) // Monday
      })
    })

    describe('Months intervals', () => {
      it('should calculate next run for 1M interval', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-15T10:00:00Z',
          interval: '1M',
        }
        const fromDate = new Date('2025-01-15T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBe('2025-02-15T10:00:00.000Z')
      })

      it('should handle month overflow (Jan 31 + 1M)', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-31T10:00:00Z',
          interval: '1M',
        }
        const fromDate = new Date('2025-01-31T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // Jan 31 + 1M should be Feb 28 (last day of Feb in 2025)
        expect(nextRun).toBe('2025-02-28T10:00:00.000Z')
      })
    })

    describe('End conditions', () => {
      it('should return null when occurrences limit reached', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z',
          interval: '1h',
          endCondition: {
            type: 'occurrences',
            value: 5,
          },
        }
        const fromDate = new Date('2025-01-01T10:00:00Z')

        // After 5 occurrences, should return null
        const nextRun = calculateNextRun(schedule, fromDate, 5)
        expect(nextRun).toBeNull()
      })

      it('should return null when endTimestamp exceeded', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z',
          interval: '1d',
          endCondition: {
            type: 'endTimestamp',
            value: '2025-01-10T10:00:00Z',
          },
        }
        // Try to calculate from after end date
        const fromDate = new Date('2025-01-15T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBeNull()
      })

      it('should respect endTimestamp with daysOfWeek constraint', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-06T10:00:00Z', // Monday
          interval: '10d',
          daysOfWeek: [1], // Only Monday
          endCondition: {
            type: 'endTimestamp',
            value: '2025-02-28T23:59:59Z',
          },
        }
        const fromDate = new Date('2025-01-06T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // Should find next Monday within end date
        // Jan 6 (Mon) + 10d = Jan 16 (Thu) → skip
        // Jan 16 + 10d = Jan 26 (Sun) → skip
        // Jan 26 + 10d = Feb 5 (Wed) → skip
        // Feb 5 + 10d = Feb 15 (Sat) → skip
        // Feb 15 + 10d = Feb 25 (Tue) → skip
        // Feb 25 + 10d = Mar 7 (Fri) → exceeds endTimestamp
        // Should return null as no valid Monday before end date
        expect(nextRun).toBeNull()
      })
    })

    describe('Edge cases', () => {
      it('should return start time when fromDate is before start', () => {
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-10T10:00:00Z',
          interval: '1h',
        }
        const fromDate = new Date('2025-01-01T10:00:00Z') // Before start
        const nextRun = calculateNextRun(schedule, fromDate)

        expect(nextRun).toBe('2025-01-10T10:00:00.000Z')
      })

      it('should handle multiple daysOfWeek', () => {
        // Start on Monday Jan 6th 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-06T10:00:00Z', // Monday
          interval: '1d',
          daysOfWeek: [1, 3, 5], // Mon, Wed, Fri
        }
        const fromDate = new Date('2025-01-06T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // 1 day from Monday is Tuesday, skip
        // 1 day from Tuesday is Wednesday ✓
        expect(nextRun).toBe('2025-01-08T10:00:00.000Z') // Wednesday

        const nextDate = new Date(nextRun!)
        expect(nextDate.getUTCDay()).toBe(3) // Wednesday
      })
    })

    describe('Skipping non-selected days', () => {
      it('should skip to next valid day when 2h interval lands on non-selected day', () => {
        // Start on Monday Jan 6th 2025 at 10:00
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-06T10:00:00Z', // Monday 10:00
          interval: '2h',
          daysOfWeek: [1], // Only Monday
        }
        const fromDate = new Date('2025-01-06T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +2h = Monday 12:00 (same day) ✓
        expect(nextRun).toBe('2025-01-06T12:00:00.000Z')
        expect(new Date(nextRun!).getUTCDay()).toBe(1) // Monday

        // From Monday 23:00, +2h = Tuesday 01:00 (not Monday)
        const fromLateMonday = new Date('2025-01-06T23:00:00Z')
        const nextRunFromLate = calculateNextRun(schedule, fromLateMonday)

        // Should skip Tuesday-Sunday and land on next Monday
        // +2h = Tue 01:00 (skip), +2h = Tue 03:00 (skip), ...
        // Eventually reaches Monday Jan 13th
        expect(new Date(nextRunFromLate!).getUTCDay()).toBe(1) // Monday
        expect(nextRunFromLate).toBe('2025-01-13T01:00:00.000Z') // Next Monday 01:00
      })

      it('should skip to next valid day when 3d interval lands on non-selected day', () => {
        // Start on Monday Jan 6th 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-06T10:00:00Z', // Monday
          interval: '3d',
          daysOfWeek: [1, 5], // Monday and Friday only
        }
        const fromDate = new Date('2025-01-06T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +3d from Monday = Thursday (not in [1, 5])
        // +3d from Thursday = Sunday (not in [1, 5])
        // +3d from Sunday = Wednesday (not in [1, 5])
        // +3d from Wednesday = Saturday (not in [1, 5])
        // +3d from Saturday = Tuesday (not in [1, 5])
        // +3d from Tuesday = Friday ✓
        expect(nextRun).toBe('2025-01-24T10:00:00.000Z') // Friday Jan 24
        expect(new Date(nextRun!).getUTCDay()).toBe(5) // Friday
      })

      it('should skip to next valid day when 5d interval lands on non-selected day', () => {
        // Start on Wednesday Jan 1st 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-01T10:00:00Z', // Wednesday
          interval: '5d',
          daysOfWeek: [3], // Wednesday only
        }
        const fromDate = new Date('2025-01-01T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +5d from Wed = Mon (skip)
        // +5d from Mon = Sat (skip)
        // +5d from Sat = Thu (skip)
        // +5d from Thu = Tue (skip)
        // +5d from Tue = Sun (skip)
        // +5d from Sun = Fri (skip)
        // +5d from Fri = Wed ✓
        expect(nextRun).toBe('2025-02-05T10:00:00.000Z') // Wednesday Feb 5
        expect(new Date(nextRun!).getUTCDay()).toBe(3) // Wednesday
      })

      it('should skip multiple times with 1M interval when landing on non-selected days', () => {
        // Start on Friday Jan 3rd 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-03T10:00:00Z', // Friday
          interval: '1M',
          daysOfWeek: [5], // Friday only
        }
        const fromDate = new Date('2025-01-03T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +1M from Jan 3 (Fri) = Feb 3 (Mon) - not Friday
        // +1M from Feb 3 (Mon) = Mar 3 (Mon) - not Friday
        // +1M from Mar 3 (Mon) = Apr 3 (Thu) - not Friday
        // +1M from Apr 3 (Thu) = May 3 (Sat) - not Friday
        // +1M from May 3 (Sat) = Jun 3 (Tue) - not Friday
        // +1M from Jun 3 (Tue) = Jul 3 (Thu) - not Friday
        // +1M from Jul 3 (Thu) = Aug 3 (Sun) - not Friday
        // +1M from Aug 3 (Sun) = Sep 3 (Wed) - not Friday
        // +1M from Sep 3 (Wed) = Oct 3 (Fri) ✓
        expect(nextRun).toBe('2025-10-03T10:00:00.000Z') // Friday Oct 3
        expect(new Date(nextRun!).getUTCDay()).toBe(5) // Friday
      })

      it('should handle weekend-only schedule with weekday interval landing', () => {
        // Start on Saturday Jan 4th 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-04T10:00:00Z', // Saturday
          interval: '2d',
          daysOfWeek: [0, 6], // Sunday and Saturday only (weekend)
        }
        const fromDate = new Date('2025-01-04T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +2d from Sat = Mon (not weekend, skip)
        // +2d from Mon = Wed (not weekend, skip)
        // +2d from Wed = Fri (not weekend, skip)
        // +2d from Fri = Sun ✓
        expect(nextRun).toBe('2025-01-12T10:00:00.000Z') // Sunday Jan 12
        expect(new Date(nextRun!).getUTCDay()).toBe(0) // Sunday
      })

      it('should verify interval is respected even when skipping many days', () => {
        // Start on Tuesday Jan 7th 2025
        const schedule: IntervalSchedule = {
          startTimestamp: '2025-01-07T10:00:00Z', // Tuesday
          interval: '11d',
          daysOfWeek: [2], // Tuesday only
        }
        const fromDate = new Date('2025-01-07T10:00:00Z')
        const nextRun = calculateNextRun(schedule, fromDate)

        // +11d from Tue Jan 7 = Sat Jan 18 (not Tue)
        // +11d from Sat Jan 18 = Wed Jan 29 (not Tue)
        // +11d from Wed Jan 29 = Sun Feb 9 (not Tue)
        // +11d from Sun Feb 9 = Thu Feb 20 (not Tue)
        // +11d from Thu Feb 20 = Mon Mar 3 (not Tue)
        // +11d from Mon Mar 3 = Fri Mar 14 (not Tue)
        // +11d from Fri Mar 14 = Tue Mar 25 ✓
        expect(nextRun).toBe('2025-03-25T10:00:00.000Z') // Tuesday Mar 25
        expect(new Date(nextRun!).getUTCDay()).toBe(2) // Tuesday

        // Verify the number of 11-day intervals from start
        const startDate = new Date('2025-01-07T10:00:00Z')
        const resultDate = new Date(nextRun!)
        const daysDiff = Math.floor((resultDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24))
        expect(daysDiff % 11).toBe(0) // Should be exact multiple of 11 days
      })
    })
  })
})

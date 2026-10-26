import { ReasoningEffort } from '@whoz-oss/agentos-api-client'
import { EFFORT_OPTIONS, effortLabel, effortOptionsFor, parseEffortCommand } from './effort-command'

describe('effort command', () => {
  describe('effortOptionsFor()', () => {
    it('lists every level from two letters of the command on', () => {
      expect(effortOptionsFor('/ef')).toEqual([...EFFORT_OPTIONS])
      expect(effortOptionsFor('/effort')).toEqual([...EFFORT_OPTIONS])
      expect(effortOptionsFor('/EFFORT')).toEqual([...EFFORT_OPTIONS])
    })

    it('leaves a lone slash, one letter and other commands to the prompt list', () => {
      expect(effortOptionsFor('/')).toBeNull()
      expect(effortOptionsFor('/e')).toBeNull()
      expect(effortOptionsFor('/review')).toBeNull()
      expect(effortOptionsFor('hello /effort')).toBeNull()
    })

    it('filters the levels by the prefix typed after the command', () => {
      expect(effortOptionsFor('/effort ')?.length).toBe(EFFORT_OPTIONS.length)
      expect(effortOptionsFor('/effort h')?.map((o) => o.label)).toEqual(['high'])
      expect(effortOptionsFor('/effort x')?.map((o) => o.effort)).toEqual([ReasoningEffort.XHIGH])
      expect(effortOptionsFor('/effort zz')).toEqual([])
    })

    it('needs the full command name before a level', () => {
      expect(effortOptionsFor('/eff high')).toBeNull()
    })
  })

  describe('parseEffortCommand()', () => {
    it('returns the option of a known level, default included', () => {
      expect(parseEffortCommand('/effort high')?.effort).toBe(ReasoningEffort.HIGH)
      expect(parseEffortCommand('  /Effort MAX ')?.effort).toBe(ReasoningEffort.MAX)
      expect(parseEffortCommand('/effort default')).toEqual(EFFORT_OPTIONS[0])
    })

    it('returns null for a missing or unknown level, so nothing is sent to the agent', () => {
      expect(parseEffortCommand('/effort')).toBeNull()
      expect(parseEffortCommand('/effort extreme')).toBeNull()
    })

    it('returns undefined for anything that is not an /effort command', () => {
      expect(parseEffortCommand('What is /effort?')).toBeUndefined()
      expect(parseEffortCommand('/effortless')).toBeUndefined()
      expect(parseEffortCommand('/review high')).toBeUndefined()
    })
  })

  describe('effortLabel()', () => {
    it('names the level, and "default" when the case has no override', () => {
      expect(effortLabel(ReasoningEffort.XHIGH)).toBe('xhigh')
      expect(effortLabel(null)).toBe('default')
      expect(effortLabel(undefined)).toBe('default')
    })
  })
})

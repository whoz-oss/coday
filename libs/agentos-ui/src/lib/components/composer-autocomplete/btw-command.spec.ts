import { BTW_COMMAND, BTW_PROMPT, parseBtwCommand } from './btw-command'

describe('btw command', () => {
  describe('parseBtwCommand()', () => {
    it('returns the question of a /btw command, trimmed', () => {
      expect(parseBtwCommand('/btw what does the agent do?')).toBe('what does the agent do?')
      expect(parseBtwCommand('  /btw   which branch  ')).toBe('which branch')
      expect(parseBtwCommand('/BTW Which file?')).toBe('Which file?')
    })

    it('keeps a multi-line question whole', () => {
      expect(parseBtwCommand('/btw first line\nsecond line')).toBe('first line\nsecond line')
    })

    it('returns an empty question when the text is missing, so it is still a command', () => {
      expect(parseBtwCommand('/btw')).toBe('')
      expect(parseBtwCommand('/btw   ')).toBe('')
      expect(parseBtwCommand('  /btw\n')).toBe('')
    })

    it('returns undefined for anything that is not a /btw command, so it is a message for the agent', () => {
      expect(parseBtwCommand('hello')).toBeUndefined()
      expect(parseBtwCommand('')).toBeUndefined()
      expect(parseBtwCommand('what is /btw?')).toBeUndefined()
      expect(parseBtwCommand('/btwx question')).toBeUndefined()
      expect(parseBtwCommand('/effort high')).toBeUndefined()
      expect(parseBtwCommand('btw question')).toBeUndefined()
    })
  })

  describe('BTW_PROMPT', () => {
    it('is the suggestion that inserts /btw, with its description', () => {
      expect(BTW_PROMPT.name).toBe(BTW_COMMAND)
      expect(BTW_PROMPT.description).toBe('Ask a side question without interrupting the agent')
    })
  })
})

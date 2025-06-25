import { 
  limitOutput, 
  limitOutputLines, 
  limitOutputChars,
  DEFAULT_LINE_LIMIT,
  DEFAULT_CHAR_LIMIT 
} from './output-limiter'

describe('Output Limiter', () => {
  
  describe('limitOutputLines', () => {
    it('should return original output when line count is within limit', () => {
      const output = 'line1\nline2\nline3'
      const result = limitOutputLines(output, 5)
      expect(result).toBe(output)
    })

    it('should keep last N lines when exceeding limit', () => {
      const output = 'line1\nline2\nline3\nline4\nline5'
      const result = limitOutputLines(output, 3)
      expect(result).toBe('line3\nline4\nline5')
    })

    it('should handle single line output', () => {
      const output = 'single line'
      const result = limitOutputLines(output, 1)
      expect(result).toBe(output)
    })

    it('should handle empty output', () => {
      const output = ''
      const result = limitOutputLines(output, 10)
      expect(result).toBe('')
    })

    it('should handle output with only newlines', () => {
      const output = '\n\n\n'
      const result = limitOutputLines(output, 2)
      // When splitting '\n\n\n' by '\n', we get ['', '', '', ''] (4 empty strings)
      // Taking the last 2 elements gives ['', ''] which joined becomes '\n'
      expect(result).toBe('\n')
    })
  })

  describe('limitOutputChars', () => {
    it('should return original output when char count is within limit', () => {
      const output = 'short text'
      const result = limitOutputChars(output, 20)
      expect(result).toBe(output)
    })

    it('should keep last N characters when exceeding limit', () => {
      const output = 'this is a very long text that should be truncated'
      const result = limitOutputChars(output, 20)
      expect(result).toBe(' should be truncated')
    })

    it('should handle empty output', () => {
      const output = ''
      const result = limitOutputChars(output, 10)
      expect(result).toBe('')
    })

    it('should handle exact limit', () => {
      const output = 'exactly20characters!'
      const result = limitOutputChars(output, 20)
      expect(result).toBe(output)
    })
  })

  describe('limitOutput', () => {
    it('should apply both line and character limits', () => {
      const output = 'line1\nline2\nline3\nline4\nline5'
      const result = limitOutput(output, { lineLimit: 3, charLimit: 100 })
      expect(result).toBe('line3\nline4\nline5')
    })

    it('should prioritize character limit when both limits are exceeded', () => {
      const output = 'very long line 1\nvery long line 2\nvery long line 3\nvery long line 4'
      const result = limitOutput(output, { lineLimit: 5, charLimit: 30 })
      // Should first truncate by chars (last 30), then by lines
      expect(result.length).toBeLessThanOrEqual(30)
    })

    it('should handle case where character limit creates fewer lines than line limit', () => {
      const longOutput = Array(10).fill('line with some content').join('\n')
      const result = limitOutput(longOutput, { lineLimit: 8, charLimit: 50 })
      
      // Character limit should be applied first, potentially reducing line count
      expect(result.length).toBeLessThanOrEqual(50)
      const resultLines = result.split('\n')
      expect(resultLines.length).toBeLessThanOrEqual(8)
    })

    it('should return original when within both limits', () => {
      const output = 'line1\nline2'
      const result = limitOutput(output, { lineLimit: 5, charLimit: 100 })
      expect(result).toBe(output)
    })
  })

  describe('Default constants', () => {
    it('should have reasonable default values', () => {
      expect(DEFAULT_LINE_LIMIT).toBe(1000)
      expect(DEFAULT_CHAR_LIMIT).toBe(50000)
    })
  })

  describe('Edge cases', () => {
    it('should handle very large inputs efficiently', () => {
      const largeOutput = Array(10000).fill('test line').join('\n')
      const start = Date.now()
      const result = limitOutput(largeOutput, { lineLimit: 100, charLimit: 5000 })
      const duration = Date.now() - start
      
      expect(duration).toBeLessThan(100) // Should be fast
      expect(result.split('\n').length).toBeLessThanOrEqual(100)
      expect(result.length).toBeLessThanOrEqual(5000)
    })

    it('should handle unicode characters correctly', () => {
      const output = '🚀 ligne 1\n🎯 ligne 2\n✨ ligne 3'
      const result = limitOutputLines(output, 2)
      expect(result).toBe('🎯 ligne 2\n✨ ligne 3')
    })

    it('should handle mixed line endings', () => {
      const output = 'line1\r\nline2\nline3\r\nline4'
      const result = limitOutputLines(output, 2)
      // Should work with \n splits, \r characters will be preserved
      expect(result.split('\n').length).toBe(2)
    })
  })
})
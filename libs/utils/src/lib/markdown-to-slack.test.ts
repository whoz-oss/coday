import { markdownToSlack, validateSlackFormat } from './markdown-to-slack'

describe('markdownToSlack', () => {
  it('should convert markdown bold to slack bold', () => {
    const input = 'This is **bold text** and this is **also bold**'
    const expected = 'This is *bold text* and this is *also bold*'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should preserve existing slack bold', () => {
    const input = 'This is *already slack bold*'
    expect(markdownToSlack(input)).toBe(input)
  })

  it('should convert markdown links to slack links', () => {
    const input = 'Check out [this link](https://example.com) for more info'
    const expected = 'Check out <https://example.com|this link> for more info'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should convert multiple links', () => {
    const input = '[Link 1](https://example1.com) and [Link 2](https://example2.com)'
    const expected = '<https://example1.com|Link 1> and <https://example2.com|Link 2>'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should convert headers to bold text', () => {
    const input = '# Header 1\n## Header 2\n### Header 3'
    const expected = '*Header 1*\n*Header 2*\n*Header 3*'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should remove language hints from code blocks', () => {
    const input = '```typescript\nconst x = 1;\n```'
    const expected = '```\nconst x = 1;\n```'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should handle mixed formatting', () => {
    const input = '**Bold** text with a [link](https://example.com) and `code`'
    const expected = '*Bold* text with a <https://example.com|link> and `code`'
    expect(markdownToSlack(input)).toBe(expected)
  })

  it('should preserve inline code', () => {
    const input = 'Use `npm install` to install packages'
    expect(markdownToSlack(input)).toBe(input)
  })

  it('should preserve italics', () => {
    const input = 'This is _italic text_'
    expect(markdownToSlack(input)).toBe(input)
  })

  it('should handle empty string', () => {
    expect(markdownToSlack('')).toBe('')
  })

  it('should handle text without markdown', () => {
    const input = 'Plain text without any formatting'
    expect(markdownToSlack(input)).toBe(input)
  })
})

describe('validateSlackFormat', () => {
  it('should detect markdown bold', () => {
    const issues = validateSlackFormat('This is **bold**')
    expect(issues).toContain('Found **bold** syntax (use *bold* for Slack)')
  })

  it('should detect markdown links', () => {
    const issues = validateSlackFormat('[link text](https://example.com)')
    expect(issues).toContain('Found [text](url) links (use <url|text> for Slack)')
  })

  it('should detect headers', () => {
    const issues = validateSlackFormat('# Header')
    expect(issues).toContain("Found # headers (Slack doesn't support headers, use *text* instead)")
  })

  it('should return empty array for valid slack format', () => {
    const validText = 'This is *bold* with a <https://example.com|link> and `code`'
    expect(validateSlackFormat(validText)).toEqual([])
  })

  it('should detect multiple issues', () => {
    const text = '# Header\n**Bold** text with [link](url)'
    const issues = validateSlackFormat(text)
    expect(issues.length).toBe(3)
  })
})

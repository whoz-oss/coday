import { messagePreview, RAIL_LABEL_LENGTH, RAIL_PREVIEW_LENGTH } from './message-preview'

describe('messagePreview', () => {
  const preview = (text: string): string => messagePreview(text, RAIL_PREVIEW_LENGTH)

  it('leaves a short plain message untouched', () => {
    expect(preview('Fix the login bug')).toBe('Fix the login bug')
  })

  it('keeps a text of exactly the limit whole', () => {
    const text = 'a'.repeat(RAIL_PREVIEW_LENGTH)
    expect(preview(text)).toBe(text)
  })

  it('cuts a longer text to the limit, ellipsis included', () => {
    const result = preview('Please refactor the authentication module so that it uses the new token service')

    expect(result).toBe('Please refactor the authentication modu…')
    expect(Array.from(result)).toHaveLength(RAIL_PREVIEW_LENGTH)
  })

  it('does not leave a space before the ellipsis', () => {
    expect(messagePreview('one two three', 5)).toBe('one…')
  })

  it('collapses newlines, tabs and runs of spaces into single spaces, and trims', () => {
    expect(preview('  first line\n\n\tsecond    line \r\n third  ')).toBe('first line second line third')
  })

  it('drops emphasis, strikethrough and inline code markers, keeping the words', () => {
    expect(preview('**bold** and __strong__, *it* and _em_, ~~gone~~ `code`')).toBe(
      'bold and strong, it and em, gone code'
    )
  })

  it('keeps underscores and asterisks that are not emphasis', () => {
    expect(preview('rename snake_case_name, 2 * 3 * 4')).toBe('rename snake_case_name, 2 * 3 * 4')
  })

  it('drops heading, quote and list markers at the start of lines', () => {
    expect(preview('# Title\n> quoted\n- item\n2. second\n- [x] done')).toBe('Title quoted item second done')
  })

  it('keeps the text of links and images, not their targets', () => {
    expect(preview('See [the docs](https://example.com) and ![a chart](chart.png)')).toBe('See the docs and a chart')
  })

  it('drops code fences and horizontal rules, keeping the code', () => {
    expect(preview('Run this:\n```bash\npnpm test\n```\n---\nthanks')).toBe('Run this: pnpm test thanks')
  })

  it('never splits an emoji when cutting', () => {
    const result = messagePreview('ok 👍👍👍👍', 5)
    expect(result).toBe('ok 👍…')
  })

  it('returns an empty string for a message without text', () => {
    expect(preview('  \n ')).toBe('')
  })

  it('gives the accessible label a longer budget than the tooltip', () => {
    const text = 'word '.repeat(40)
    expect(Array.from(messagePreview(text, RAIL_LABEL_LENGTH)).length).toBeLessThanOrEqual(RAIL_LABEL_LENGTH)
    expect(messagePreview(text, RAIL_LABEL_LENGTH).length).toBeGreaterThan(preview(text).length)
  })
})

import { parseProposal } from './exchange-proposal'

describe('parseProposal', () => {
  it('takes the explanation and the proposal block', () => {
    expect(parseProposal('Shorter and clearer.\n\n```proposal\nBuy oat milk\n```')).toEqual({
      explanation: 'Shorter and clearer.',
      proposal: 'Buy oat milk',
    })
  })

  it('keeps the text after the block in the explanation, and a multi-line proposal intact', () => {
    const result = parseProposal('Intro\n```proposal\nline 1\n\n  line 3\n```\nOutro')
    expect(result.proposal).toBe('line 1\n\n  line 3')
    expect(result.explanation).toBe('Intro\nOutro')
  })

  it.each([
    ['a longer fence', '````proposal\nuse ``` inside\n````'],
    ['a tilde fence', '~~~proposal\nuse ``` inside\n~~~'],
    ['an upper-case info string', '```Proposal\nuse ``` inside\n```'],
    ['an indented fence', '  ```proposal\n' + 'use ``` inside\n  ```'],
    ['trailing spaces after the info string', '```proposal  \nuse ``` inside\n```'],
  ])('accepts %s', (_label, block) => {
    // The closing fence of the inner ``` must not end a longer fence: only the tilde and longer cases hold it.
    const result = parseProposal(`Why\n${block}`)
    expect(result.proposal).toContain('use')
    expect(result.explanation).toBe('Why')
  })

  it('returns no proposal when there is no proposal block', () => {
    expect(parseProposal('No change needed.')).toEqual({ explanation: 'No change needed.', proposal: null })
    // Another fenced block is not a proposal.
    const other = parseProposal('See:\n```js\nlet a = 1\n```')
    expect(other.proposal).toBeNull()
    expect(other.explanation).toContain('let a = 1')
  })

  it('takes the last proposal block when there are several', () => {
    const result = parseProposal('First try\n```proposal\none\n```\nBetter:\n```proposal\ntwo\n```')
    expect(result.proposal).toBe('two')
    expect(result.explanation).toContain('First try')
    expect(result.explanation).toContain('Better:')
  })

  it('handles CRLF line endings', () => {
    const result = parseProposal('Why\r\n```proposal\r\nline 1\r\nline 2\r\n```\r\n')
    expect(result).toEqual({ explanation: 'Why', proposal: 'line 1\nline 2' })
  })

  it('runs an unclosed proposal block to the end', () => {
    expect(parseProposal('Why\n```proposal\nabc\ndef')).toEqual({ explanation: 'Why', proposal: 'abc\ndef' })
  })

  it('allows an empty proposal, to delete the passage', () => {
    expect(parseProposal('Remove it\n```proposal\n```')).toEqual({ explanation: 'Remove it', proposal: '' })
  })
})

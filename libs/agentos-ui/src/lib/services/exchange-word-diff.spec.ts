import { diffWords } from './exchange-word-diff'

describe('diffWords', () => {
  it('returns one equal part for identical texts, and nothing for empty ones', () => {
    expect(diffWords('same text', 'same text')).toEqual([{ type: 'equal', text: 'same text' }])
    expect(diffWords('', '')).toEqual([])
  })

  it('marks a replaced word as a deletion then an insertion, keeping the common words', () => {
    expect(diffWords('the quick fox', 'the slow fox')).toEqual([
      { type: 'equal', text: 'the ' },
      { type: 'delete', text: 'quick' },
      { type: 'insert', text: 'slow' },
      { type: 'equal', text: ' fox' },
    ])
  })

  it('marks pure insertions and pure deletions', () => {
    expect(diffWords('a c', 'a b c')).toEqual([
      { type: 'equal', text: 'a ' },
      { type: 'insert', text: 'b ' },
      { type: 'equal', text: 'c' },
    ])
    expect(diffWords('a b c', 'a c')).toEqual([
      { type: 'equal', text: 'a ' },
      { type: 'delete', text: 'b ' },
      { type: 'equal', text: 'c' },
    ])
  })

  it('gives the texts back when the parts are joined', () => {
    const before = 'Buy milk,\n  eggs and bread'
    const after = 'Buy oat milk,\n  bread and eggs today'
    const parts = diffWords(before, after)
    expect(
      parts
        .filter((p) => p.type !== 'insert')
        .map((p) => p.text)
        .join('')
    ).toBe(before)
    expect(
      parts
        .filter((p) => p.type !== 'delete')
        .map((p) => p.text)
        .join('')
    ).toBe(after)
  })

  it('handles a passage deleted or created entirely', () => {
    expect(diffWords('gone', '')).toEqual([{ type: 'delete', text: 'gone' }])
    expect(diffWords('', 'new')).toEqual([{ type: 'insert', text: 'new' }])
  })

  it('degrades to a full replacement for passages too large to compare', () => {
    const big = Array.from({ length: 2500 }, (_, i) => `a${i}`).join(' ')
    const other = Array.from({ length: 2500 }, (_, i) => `b${i}`).join(' ')
    expect(diffWords(big, other)).toEqual([
      { type: 'delete', text: big },
      { type: 'insert', text: other },
    ])
  })
})

import { diffLines, DiffRow } from './field-diff'

const numbered = (count: number, changed: Record<number, string> = {}): string =>
  Array.from({ length: count }, (_, i) => changed[i + 1] ?? `line ${i + 1}`).join('\n')

const brief = (rows: DiffRow[]) =>
  rows.map((row) => (row.kind === 'gap' ? `gap:${row.count}` : `${row.kind}:${row.text}`))

describe('diffLines', () => {
  it('returns nothing when both sides are absent', () => {
    expect(diffLines(null, null)).toEqual([])
  })

  it('shows every line as context when nothing changed and the text is a single line', () => {
    expect(diffLines('same', 'same')).toEqual([{ kind: 'context', text: 'same' }])
  })

  it('collapses an identical text the same way as any unchanged run', () => {
    expect(diffLines('a\nb\nc', 'a\nb\nc')).toEqual([{ kind: 'gap', count: 3 }])
  })

  it('shows a creation as all added lines', () => {
    expect(brief(diffLines(null, 'a\nb'))).toEqual(['added:a', 'added:b'])
  })

  it('shows a deletion as all removed lines', () => {
    expect(brief(diffLines('a\nb', null))).toEqual(['removed:a', 'removed:b'])
  })

  it('shows an added line among unchanged ones', () => {
    expect(brief(diffLines('a\nc', 'a\nb\nc'))).toEqual(['context:a', 'added:b', 'context:c'])
  })

  it('shows a removed line among unchanged ones', () => {
    expect(brief(diffLines('a\nb\nc', 'a\nc'))).toEqual(['context:a', 'removed:b', 'context:c'])
  })

  it('puts the removals before the additions of a changed line', () => {
    expect(brief(diffLines('a\nold\nc', 'a\nnew\nc'))).toEqual(['context:a', 'removed:old', 'added:new', 'context:c'])
  })

  it('puts all removals of a hunk before its additions', () => {
    expect(brief(diffLines('x1\nx2', 'y1\ny2'))).toEqual(['removed:x1', 'removed:x2', 'added:y1', 'added:y2'])
  })

  it('collapses the unchanged lines far from a change and counts them', () => {
    const rows = diffLines(numbered(20), numbered(20, { 10: 'changed' }))

    expect(brief(rows)).toEqual([
      'gap:6',
      'context:line 7',
      'context:line 8',
      'context:line 9',
      'removed:line 10',
      'added:changed',
      'context:line 11',
      'context:line 12',
      'context:line 13',
      'gap:7',
    ])
  })

  it('honours the context size', () => {
    const rows = diffLines(numbered(10), numbered(10, { 5: 'changed' }), 1)

    expect(brief(rows)).toEqual([
      'gap:3',
      'context:line 4',
      'removed:line 5',
      'added:changed',
      'context:line 6',
      'gap:4',
    ])
  })

  it('does not replace a single unchanged line by a gap', () => {
    // 3 lines of context after the first change, 1 unchanged line, then 3 lines before the second one: 7 lines apart
    const rows = diffLines(numbered(9, {}), numbered(9, { 1: 'a', 9: 'z' }))
    const gaps = rows.filter((row) => row.kind === 'gap')

    expect(gaps).toEqual([])
    expect(rows.filter((row) => row.kind === 'context').length).toBe(7)
  })

  it('collapses a run between two changes when it hides at least 2 lines', () => {
    const rows = diffLines(numbered(10), numbered(10, { 1: 'a', 10: 'z' }))

    expect(rows.filter((row) => row.kind === 'gap')).toEqual([{ kind: 'gap', count: 2 }])
  })

  it('skips the line matching on very large values and shows them as replaced', () => {
    const before = Array.from({ length: 1001 }, (_, i) => `b${i}`).join('\n')
    const after = Array.from({ length: 1001 }, (_, i) => `a${i}`).join('\n')

    const rows = diffLines(before, after)

    expect(rows.length).toBe(2002)
    expect(rows.slice(0, 1001).every((row) => row.kind === 'removed')).toBe(true)
    expect(rows.slice(1001).every((row) => row.kind === 'added')).toBe(true)
  })

  it('does not take the guard path just below the limit', () => {
    const same = Array.from({ length: 1000 }, (_, i) => `l${i}`).join('\n')

    expect(diffLines(same, same)).toEqual([{ kind: 'gap', count: 1000 }])
  })
})

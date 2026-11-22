import { locatePassage, rangeFromTextOffsets, rangeTextOffsets, squash } from './exchange-passage'

describe('locatePassage', () => {
  it('compares ignoring whitespace when asked to', () => {
    expect(locatePassage('HelloSecond tail', 0, 11, ['Hello\nSecond'], (a, b) => squash(a) === squash(b))).toEqual({
      from: 0,
      to: 11,
    })
  })

  it('keeps the offsets when the text there is still the passage', () => {
    expect(locatePassage('one two three', 4, 7, ['two'])).toEqual({ from: 4, to: 7 })
  })

  it('searches the passage when the text moved, preferring the first occurrence after the old position', () => {
    expect(locatePassage('xx two one two', 4, 7, ['two'])).toEqual({ from: 11, to: 14 })
    expect(locatePassage('two then one', 20, 23, ['two'])).toEqual({ from: 0, to: 3 })
  })

  it('tries the next candidate text', () => {
    expect(locatePassage('alpha beta', 0, 3, ['xyz', 'beta'])).toEqual({ from: 6, to: 10 })
  })

  it('returns null when the passage is gone, and for empty candidates', () => {
    expect(locatePassage('one three', 4, 7, ['two'])).toBeNull()
    expect(locatePassage('one', 0, 0, [''])).toBeNull()
  })
})

describe('text offsets and DOM ranges', () => {
  let root: HTMLElement
  beforeEach(() => {
    root = document.createElement('div')
    root.innerHTML = '<p>Hello <b>big</b> world</p><p>Second</p>'
    document.body.appendChild(root)
  })
  afterEach(() => root.remove())

  it('maps a range to its text offsets and back, across elements', () => {
    const range = rangeFromTextOffsets(root, 6, 15)!
    expect(range.toString()).toBe('big world')
    expect(rangeTextOffsets(root, range)).toEqual({ from: 6, to: 15 })
  })

  it('maps a range inside a single text node', () => {
    expect(rangeFromTextOffsets(root, 0, 5)!.toString()).toBe('Hello')
    expect(rangeFromTextOffsets(root, 15, 21)!.toString()).toBe('Second')
  })

  it('returns null outside the text, and for a range outside the root', () => {
    expect(rangeFromTextOffsets(root, 0, 999)).toBeNull()
    const outside = document.createElement('p')
    outside.textContent = 'x'
    document.body.appendChild(outside)
    const range = document.createRange()
    range.selectNodeContents(outside)
    expect(rangeTextOffsets(root, range)).toBeNull()
    outside.remove()
  })
})

import { formatTokenCount, TokenCountPipe } from './token-count.pipe'

describe('TokenCountPipe', () => {
  const pipe = new TokenCountPipe()

  it.each([
    [0, '0'],
    [845, '845'],
    [1234, '1.2k'],
    [9999, '10.0k'],
    [12_345, '12k'],
    [999_999, '1000k'],
    [1_234_567, '1.2m'],
  ])('reads %s as %s', (tokens, expected) => {
    expect(pipe.transform(tokens)).toBe(expected)
  })

  it('reads a missing count as zero rather than printing NaN', () => {
    expect(pipe.transform(null)).toBe('0')
    expect(pipe.transform(undefined)).toBe('0')
    expect(pipe.transform(Number.NaN)).toBe('0')
  })

  it('never reports a negative count', () => {
    expect(pipe.transform(-5)).toBe('0')
  })

  it('exposes the same rule as a function, so non-template callers do not reimplement it', () => {
    expect(formatTokenCount(12_345)).toBe(pipe.transform(12_345))
  })
})

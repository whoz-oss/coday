import { formatTokenCount, TokenCountPipe } from './token-count.pipe'

describe('TokenCountPipe', () => {
  const pipe = new TokenCountPipe()

  it.each([
    [0, '0'],
    [845, '845'],
    [999, '999'],
    [1000, '1.0k'],
    [1234, '1.2k'],
    [12_345, '12k'],
    [999_499, '999k'],
    [1_234_567, '1.2m'],
  ])('reads %s as %s', (tokens, expected) => {
    expect(pipe.transform(tokens)).toBe(expected)
  })

  it('never prints four digits and a unit where the next tier says the same thing', () => {
    // 999 999 rounds to a million: `1000k` would be wider than the `1.0m` of its own neighbour.
    expect(pipe.transform(999_999)).toBe('1.0m')
    expect(pipe.transform(1_000_000)).toBe('1.0m')
  })

  it('does not change precision between two counts that read the same', () => {
    // Both are ten thousand; printing one as `10.0k` claims a precision the other drops.
    expect(pipe.transform(9999)).toBe('10k')
    expect(pipe.transform(10_000)).toBe('10k')
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

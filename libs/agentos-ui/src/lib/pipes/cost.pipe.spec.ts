import { CostPipe, UNKNOWN_COST } from './cost.pipe'

describe('CostPipe', () => {
  const pipe = new CostPipe()

  it('prints two decimals at and above a cent, which is what the figures are read for', () => {
    expect(pipe.transform(0.01)).toBe('0.01')
    expect(pipe.transform(0.4249)).toBe('0.42')
    expect(pipe.transform(12.3456789)).toBe('12.35')
  })

  it('keeps four decimals under a cent rather than collapsing a cheap run to zero', () => {
    expect(pipe.transform(0.004)).toBe('0.004')
    expect(pipe.transform(0.0004)).toBe('0.0004')
  })

  it('distinguishes a known zero from an unknown cost', () => {
    expect(pipe.transform(0)).toBe('0.00')
    expect(pipe.transform(null)).toBe(UNKNOWN_COST)
    expect(pipe.transform(undefined)).toBe(UNKNOWN_COST)
  })

  describe('with usage whose price is partly or wholly unknown', () => {
    it('reports no figure when nothing was priced', () => {
      // The case every namespace without pricing produces: the panels used to render this as
      // "At least 0.00", which reads as a free run.
      expect(pipe.transform(0, 3)).toBe(UNKNOWN_COST)
    })

    it('marks a partial total as a lower bound', () => {
      expect(pipe.transform(0.42, 2)).toBe('\u2265\u00a00.42')
      expect(pipe.transform(0.004, 2)).toBe('\u2265\u00a00.004')
    })

    it('leaves a complete total unmarked', () => {
      expect(pipe.transform(0.42, 0)).toBe('0.42')
      expect(pipe.transform(0, 0)).toBe('0.00')
    })

    it('ignores a count that is not a usable number', () => {
      expect(pipe.transform(0.42, Number.NaN)).toBe('0.42')
    })
  })

  it('treats a non-finite figure as unknown instead of printing NaN', () => {
    expect(pipe.transform(Number.NaN)).toBe(UNKNOWN_COST)
    expect(pipe.transform(Number.POSITIVE_INFINITY)).toBe(UNKNOWN_COST)
  })

  it('never prints a negative cost', () => {
    expect(pipe.transform(-1)).toBe('0.00')
  })

  it('states the bound rather than printing a zero, below four decimals', () => {
    // The assertion is on the string, not on "something other than 0.00": `0.0000` passes that
    // test while reading exactly as free, which is what this pipe exists to prevent.
    expect(pipe.transform(0.00004)).toBe('< 0.0001')
    expect(pipe.transform(1e-7)).toBe('< 0.0001')
    expect(pipe.transform(0.0001)).toBe('0.0001')
  })

  it('keeps a value that rounds to a cent with the two-decimal figures', () => {
    // 0.0100 beside 0.01 is the same amount printed at two widths.
    expect(pipe.transform(0.00999)).toBe('0.01')
    expect(pipe.transform(0.0094)).toBe('0.0094')
    expect(pipe.transform(0.01)).toBe('0.01')
  })
})

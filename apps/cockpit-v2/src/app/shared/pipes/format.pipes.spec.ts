import { DurationPipe, TokensPipe, UsdPipe } from './format.pipes'

describe('UsdPipe', () => {
  const pipe = new UsdPipe()

  it('formats a plain cost without an uncertainty marker', () => {
    expect(pipe.transform(1.0723, 4)).toBe('$1.0723')
    expect(pipe.transform(1.0723, 2, 0)).toBe('$1.07')
  })

  it('marks a lower bound with a ≥ prefix when unknownCostCount > 0', () => {
    expect(pipe.transform(1.0723, 4, 3)).toBe('≥ $1.0723')
    expect(pipe.transform(0, 2, 1)).toBe('≥ $0.00')
  })

  it('renders a dash for a missing value', () => {
    expect(pipe.transform(null)).toBe('—')
    expect(pipe.transform(undefined, 2, 5)).toBe('—')
  })
})

describe('DurationPipe', () => {
  const pipe = new DurationPipe()

  it('formats seconds and minutes', () => {
    expect(pipe.transform(12)).toBe('12.00s')
    expect(pipe.transform(732)).toBe('12m 12s')
    expect(pipe.transform(null)).toBe('—')
  })
})

describe('TokensPipe', () => {
  const pipe = new TokensPipe()

  it('formats thousands and millions', () => {
    expect(pipe.transform(606_500)).toBe('606.5k')
    expect(pipe.transform(2_000_000)).toBe('2.0M')
    expect(pipe.transform(42)).toBe('42')
  })
})

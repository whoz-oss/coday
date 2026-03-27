import { resolveChoice } from './slack-canal'

describe('resolveChoice', () => {
  const options = ['Option A', 'Option B', 'Option C']

  it('resolves a direct exact match (case-insensitive)', () => {
    expect(resolveChoice('Option A', options)).toBe('Option A')
    expect(resolveChoice('option a', options)).toBe('Option A')
    expect(resolveChoice('OPTION B', options)).toBe('Option B')
  })

  it('resolves a 1-based numeric index', () => {
    expect(resolveChoice('1', options)).toBe('Option A')
    expect(resolveChoice('2', options)).toBe('Option B')
    expect(resolveChoice('3', options)).toBe('Option C')
  })

  it('falls back to the raw trimmed input when no match is found', () => {
    expect(resolveChoice('unknown', options)).toBe('unknown')
    expect(resolveChoice('  custom answer  ', options)).toBe('custom answer')
  })

  it('returns fallback for out-of-range numeric index', () => {
    expect(resolveChoice('0', options)).toBe('0')
    expect(resolveChoice('4', options)).toBe('4')
    expect(resolveChoice('-1', options)).toBe('-1')
  })

  it('handles a single-option list', () => {
    expect(resolveChoice('1', ['Yes'])).toBe('Yes')
    expect(resolveChoice('Yes', ['Yes'])).toBe('Yes')
  })

  it('trims whitespace before matching', () => {
    expect(resolveChoice('  2  ', options)).toBe('Option B')
    expect(resolveChoice('  Option C  ', options)).toBe('Option C')
  })
})

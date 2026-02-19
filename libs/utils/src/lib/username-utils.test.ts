import { sanitizeUsername } from './username-utils'

describe('sanitizeUsername', () => {
  it('should replace hyphens with underscores', () => {
    expect(sanitizeUsername('Jean-Pierre')).toBe('Jean_Pierre')
  })

  it('should replace @ symbol with underscore', () => {
    expect(sanitizeUsername('user@example.com')).toBe('user_example_com')
  })

  it('should keep alphanumeric characters unchanged', () => {
    expect(sanitizeUsername('JohnDoe123')).toBe('JohnDoe123')
  })

  it('should keep existing underscores', () => {
    expect(sanitizeUsername('John_Doe')).toBe('John_Doe')
  })

  it('should replace multiple special characters', () => {
    expect(sanitizeUsername('José.García')).toBe('Jos__Garc_a')
  })

  it('should replace dots with underscores', () => {
    expect(sanitizeUsername('first.last')).toBe('first_last')
  })

  it('should handle spaces', () => {
    expect(sanitizeUsername('John Doe')).toBe('John_Doe')
  })

  it('should handle mixed special characters', () => {
    expect(sanitizeUsername('user+tag@domain.com')).toBe('user_tag_domain_com')
  })

  it('should handle empty string', () => {
    expect(sanitizeUsername('')).toBe('')
  })

  it('should handle string with only special characters', () => {
    expect(sanitizeUsername('@#$%')).toBe('____')
  })
})

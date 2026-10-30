import { computeMemberDiff, memberLabel } from './user-group-form.util'

describe('computeMemberDiff', () => {
  it('splits into additions and removals', () => {
    const { toAdd, toRemove } = computeMemberDiff(['alice', 'bob'], ['bob', 'carol'])

    expect(toAdd).toEqual(['carol'])
    expect(toRemove).toEqual(['alice'])
  })

  it('returns empty lists when unchanged', () => {
    const { toAdd, toRemove } = computeMemberDiff(['alice', 'bob'], ['alice', 'bob'])

    expect(toAdd).toEqual([])
    expect(toRemove).toEqual([])
  })

  it('treats a fresh selection as all additions', () => {
    const { toAdd, toRemove } = computeMemberDiff([], ['alice', 'bob'])

    expect(toAdd).toEqual(['alice', 'bob'])
    expect(toRemove).toEqual([])
  })
})

describe('memberLabel', () => {
  it('prefers the full name', () => {
    expect(memberLabel({ firstname: 'Alice', lastname: 'Adams', externalId: 'alice@example.com' })).toBe('Alice Adams')
  })

  it('falls back to first or last name alone', () => {
    expect(memberLabel({ firstname: 'Alice', externalId: 'alice@example.com' })).toBe('Alice')
    expect(memberLabel({ lastname: 'Adams', externalId: 'alice@example.com' })).toBe('Adams')
  })

  it('falls back to the external id when no name is set', () => {
    expect(memberLabel({ externalId: 'alice@example.com' })).toBe('alice@example.com')
  })
})

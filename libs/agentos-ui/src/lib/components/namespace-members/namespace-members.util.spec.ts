import { computeMemberDiff, hasAtLeastOneAdmin, memberLabel, MemberRoleEntry } from './namespace-members.util'

function entry(userId: string, role: string): MemberRoleEntry {
  return { userId, role }
}

describe('computeMemberDiff', () => {
  it('treats a fresh selection as all upserts', () => {
    const { toUpsert, toRemove } = computeMemberDiff([], [entry('alice', 'MEMBER'), entry('bob', 'ADMIN')])

    expect(toUpsert).toEqual([entry('alice', 'MEMBER'), entry('bob', 'ADMIN')])
    expect(toRemove).toEqual([])
  })

  it('returns empty results when nothing changed', () => {
    const original = [entry('alice', 'MEMBER'), entry('bob', 'ADMIN')]
    const { toUpsert, toRemove } = computeMemberDiff(original, [entry('alice', 'MEMBER'), entry('bob', 'ADMIN')])

    expect(toUpsert).toEqual([])
    expect(toRemove).toEqual([])
  })

  it('detects additions', () => {
    const original = [entry('alice', 'MEMBER')]
    const { toUpsert, toRemove } = computeMemberDiff(original, [entry('alice', 'MEMBER'), entry('carol', 'MEMBER')])

    expect(toUpsert).toEqual([entry('carol', 'MEMBER')])
    expect(toRemove).toEqual([])
  })

  it('detects removals', () => {
    const original = [entry('alice', 'MEMBER'), entry('bob', 'ADMIN')]
    const { toUpsert, toRemove } = computeMemberDiff(original, [entry('alice', 'MEMBER')])

    expect(toUpsert).toEqual([])
    expect(toRemove).toEqual(['bob'])
  })

  it('detects a pure role change on an existing member as an upsert, not add+remove', () => {
    const original = [entry('alice', 'MEMBER')]
    const { toUpsert, toRemove } = computeMemberDiff(original, [entry('alice', 'ADMIN')])

    expect(toUpsert).toEqual([entry('alice', 'ADMIN')])
    expect(toRemove).toEqual([])
  })

  it('combines add, remove and role-change in a single diff', () => {
    const original = [entry('alice', 'MEMBER'), entry('bob', 'ADMIN'), entry('carol', 'MEMBER')]
    const edited = [entry('alice', 'ADMIN'), entry('dave', 'MEMBER')]

    const { toUpsert, toRemove } = computeMemberDiff(original, edited)

    expect(toUpsert).toEqual(expect.arrayContaining([entry('alice', 'ADMIN'), entry('dave', 'MEMBER')]))
    expect(toUpsert).toHaveLength(2)
    expect(toRemove.sort()).toEqual(['bob', 'carol'])
  })
})

describe('hasAtLeastOneAdmin', () => {
  it('returns true when at least one ADMIN is present', () => {
    expect(hasAtLeastOneAdmin([entry('alice', 'MEMBER'), entry('bob', 'ADMIN')])).toBe(true)
  })

  it('returns false when no ADMIN is present', () => {
    expect(hasAtLeastOneAdmin([entry('alice', 'MEMBER')])).toBe(false)
  })

  it('returns false for an empty list', () => {
    expect(hasAtLeastOneAdmin([])).toBe(false)
  })
})

describe('memberLabel', () => {
  it('prefers the full name', () => {
    expect(memberLabel({ firstname: 'Alice', lastname: 'Adams', email: 'alice@example.com' })).toBe('Alice Adams')
  })

  it('falls back to first or last name alone', () => {
    expect(memberLabel({ firstname: 'Alice', email: 'alice@example.com' })).toBe('Alice')
    expect(memberLabel({ lastname: 'Adams', email: 'alice@example.com' })).toBe('Adams')
  })

  it('falls back to email when no name is set', () => {
    expect(memberLabel({ email: 'alice@example.com' })).toBe('alice@example.com')
  })

  it('falls back to externalId when neither name nor email is set', () => {
    expect(memberLabel({ externalId: 'alice-ext' })).toBe('alice-ext')
  })
})

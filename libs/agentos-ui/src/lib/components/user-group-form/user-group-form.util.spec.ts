import { UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { computeMemberDiff, computeTakenElsewhere, memberLabel } from './user-group-form.util'

function group(userGroupId: string, name: string, agentIds: string[]): UserGroupSearchResult {
  return {
    userGroupId,
    namespaceId: 'ns-1',
    namespaceExternalId: 'ext-1',
    name,
    agentIds,
    userCount: 0,
  }
}

describe('computeTakenElsewhere', () => {
  it('maps agents deployed to other groups to their owning group name', () => {
    const groups = [group('g1', 'Ops', ['a1', 'a2']), group('g2', 'Support', ['a3'])]

    const taken = computeTakenElsewhere(groups, null)

    expect(taken.get('a1')).toBe('Ops')
    expect(taken.get('a2')).toBe('Ops')
    expect(taken.get('a3')).toBe('Support')
    expect(taken.size).toBe(3)
  })

  it('excludes the current group so its own agents stay selectable', () => {
    const groups = [group('g1', 'Ops', ['a1']), group('g2', 'Support', ['a2'])]

    const taken = computeTakenElsewhere(groups, 'g1')

    expect(taken.has('a1')).toBe(false)
    expect(taken.get('a2')).toBe('Support')
  })

  it('keeps the first owning group when an agent appears in several', () => {
    const groups = [group('g1', 'First', ['a1']), group('g2', 'Second', ['a1'])]

    const taken = computeTakenElsewhere(groups, null)

    expect(taken.get('a1')).toBe('First')
  })

  it('returns an empty map when there are no groups', () => {
    expect(computeTakenElsewhere([], null).size).toBe(0)
  })
})

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

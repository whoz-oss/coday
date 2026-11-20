import { UserGroupMemberRoleEnum as Role } from '@whoz-oss/agentos-api-client'
import {
  computeAdminDelta,
  computeMemberDiff,
  memberLabel,
  mergeMemberPage,
  SelectedMember,
} from './user-group-form.util'

const row = (externalId: string, role: Role, roleEdited?: boolean): SelectedMember => ({
  externalId,
  label: externalId,
  role,
  roleEdited,
})
const server = (externalId: string, role: Role) => ({ externalId, userId: `id-${externalId}`, role })

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

describe('mergeMemberPage', () => {
  it('records every page row in the baseline but never duplicates a displayed row', () => {
    const rows = [row('bob', Role.MEMBER)]
    const { rows: merged, baseline } = mergeMemberPage(rows, new Map(), [
      server('bob', Role.ADMIN),
      server('carol', Role.MEMBER),
    ])

    expect(merged.map((r) => r.externalId)).toEqual(['bob', 'carol'])
    expect(baseline.get('bob')).toBe(Role.ADMIN)
    expect(baseline.get('carol')).toBe(Role.MEMBER)
  })

  it('shows the server role of a locally added member unless the user edited it', () => {
    const rows = [row('bob', Role.MEMBER), row('dave', Role.ADMIN, true)]
    const { rows: merged } = mergeMemberPage(rows, new Map(), [server('bob', Role.ADMIN), server('dave', Role.MEMBER)])

    expect(merged.map((r) => r.role)).toEqual([Role.ADMIN, Role.ADMIN])
  })

  it('keeps the baseline of a member removed locally, so it is neither re-displayed nor forgotten', () => {
    const first = mergeMemberPage([], new Map(), [server('alice', Role.ADMIN)])
    const afterRemoval = first.rows.filter((r) => r.externalId !== 'alice')
    const { rows, baseline } = mergeMemberPage(afterRemoval, first.baseline, [server('bob', Role.MEMBER)])

    expect(rows.map((r) => r.externalId)).toEqual(['bob'])
    expect(baseline.has('alice')).toBe(true)
  })

  it('keeps the role a typed-in member was given, and the delta then follows the loaded server role', () => {
    const typedIn = [row('zed', Role.MEMBER, true)]
    const { rows, baseline } = mergeMemberPage(typedIn, new Map(), [server('zed', Role.ADMIN)])

    expect(rows[0]!.role).toBe(Role.MEMBER)
    expect(computeAdminDelta(baseline, rows)).toEqual({ toAdd: [], toRemove: ['zed'] })
  })
})

describe('computeAdminDelta', () => {
  const baseline = new Map([
    ['alice', Role.ADMIN],
    ['bob', Role.MEMBER],
  ])

  it('promotes and demotes against the server roles, leaving unchanged loaded members alone', () => {
    const delta = computeAdminDelta(baseline, [
      row('alice', Role.MEMBER),
      row('bob', Role.ADMIN),
      row('new', Role.ADMIN),
      row('same', Role.MEMBER),
    ])

    expect(delta).toEqual({ toAdd: ['bob', 'new'], toRemove: ['alice', 'same'] })
  })

  it('leaves loaded members untouched when their role did not change', () => {
    expect(computeAdminDelta(baseline, [row('alice', Role.ADMIN), row('bob', Role.MEMBER)])).toEqual({
      toAdd: [],
      toRemove: [],
    })
  })

  it('demotes a typed-in member picked as MEMBER, as it may be an admin on a page not loaded yet', () => {
    expect(computeAdminDelta(baseline, [row('unloaded-admin', Role.MEMBER)])).toEqual({
      toAdd: [],
      toRemove: ['unloaded-admin'],
    })
  })

  it('promotes a typed-in member picked as ADMIN', () => {
    expect(computeAdminDelta(baseline, [row('unloaded', Role.ADMIN)])).toEqual({
      toAdd: ['unloaded'],
      toRemove: [],
    })
  })

  it('demotes a removed admin that is re-added as MEMBER', () => {
    expect(computeAdminDelta(baseline, [row('alice', Role.MEMBER)]).toRemove).toEqual(['alice'])
  })
})

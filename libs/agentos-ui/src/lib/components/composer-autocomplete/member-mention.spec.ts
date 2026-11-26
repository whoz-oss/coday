import { MemberItem } from '@whoz-oss/agentos-api-client'
import { filterMemberMentions, memberHandle, memberMentions } from './member-mention'

const member = (fields: Partial<MemberItem> & { id: string }): MemberItem =>
  ({ email: '', externalId: '', ...fields }) as MemberItem

describe('memberHandle', () => {
  it('is the email before its @', () => {
    expect(memberHandle(member({ id: 'u-1', email: 'alice.smith@example.com' }))).toBe('alice.smith')
  })

  it('falls back to the name without spaces, then to the id', () => {
    expect(memberHandle(member({ id: 'u-1', firstname: 'Alice', lastname: 'Smith' }))).toBe('AliceSmith')
    expect(memberHandle(member({ id: 'u-1' }))).toBe('u-1')
  })
})

describe('memberMentions', () => {
  const members = [
    member({ id: 'U-1', email: 'alice@example.com', firstname: 'Alice' }),
    member({ id: 'u-2', email: 'bob@example.com' }),
  ]

  it('leaves the current user out, whatever the letter case of the ids', () => {
    expect(memberMentions(members, 'u-1').map((m) => m.id)).toEqual(['u-2'])
  })

  it('keeps everyone while the current user is not known, with the best label', () => {
    expect(memberMentions(members, null).map((m) => m.label)).toEqual(['Alice', 'bob@example.com'])
  })
})

describe('filterMemberMentions', () => {
  const mentions = memberMentions(
    [
      member({ id: 'u-1', email: 'alice@example.com', firstname: 'Alice', lastname: 'Smith' }),
      member({ id: 'u-2', email: 'bob@example.com', firstname: 'Bob', lastname: 'Alison' }),
      member({ id: 'u-3', email: 'carol@example.com' }),
    ],
    null
  )

  it('lists everyone for a bare @', () => {
    expect(filterMemberMentions(mentions, '').map((m) => m.id)).toEqual(['u-1', 'u-2', 'u-3'])
  })

  it('puts the people whose handle, name or email starts with the prefix before the others', () => {
    expect(filterMemberMentions(mentions, 'ali').map((m) => m.id)).toEqual(['u-1', 'u-2'])
    expect(filterMemberMentions(mentions, 'ice').map((m) => m.id)).toEqual(['u-1'])
  })

  it('finds a person by a part of their name, after the ones that start with it', () => {
    expect(filterMemberMentions(mentions, 'smi').map((m) => m.id)).toEqual(['u-1'])
  })

  it('is empty when nobody matches, and caps the list', () => {
    expect(filterMemberMentions(mentions, 'zzz')).toEqual([])
    const many = memberMentions(
      Array.from({ length: 20 }, (_, i) => member({ id: `u-${i}`, email: `user${i}@example.com` })),
      null
    )
    expect(filterMemberMentions(many, 'user')).toHaveLength(8)
  })
})

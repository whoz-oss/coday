import { MemberItem } from '@whoz-oss/agentos-api-client'
import { avatarUrlsOf, hueOf, initialsOf, presentMembers } from './case-presence'

const member = (fields: Partial<MemberItem> & { id: string }): MemberItem =>
  ({ email: '', externalId: '', ...fields }) as MemberItem

describe('initialsOf', () => {
  it('takes the first and the last word of a name', () => {
    expect(initialsOf('Ada Lovelace')).toBe('AL')
    expect(initialsOf('Jean Claude Van Damme')).toBe('JD')
  })

  it('takes the first two letters of a single word', () => {
    expect(initialsOf('Ada')).toBe('AD')
  })

  it('reads an email by its local part, split at the dots', () => {
    expect(initialsOf('ada.lovelace@example.com')).toBe('AL')
    expect(initialsOf('bob@example.com')).toBe('BO')
  })

  it('is empty for an empty label', () => {
    expect(initialsOf('')).toBe('')
  })
})

describe('hueOf', () => {
  it('is a stable hue between 0 and 359 for an id, whatever its letter case', () => {
    expect(hueOf('ABCDEF00-0000-0000-0000-000000000001')).toBe(hueOf('abcdef00-0000-0000-0000-000000000001'))
    for (const id of ['u-1', 'u-2', 'a-very-long-user-id-0000']) {
      expect(hueOf(id)).toBeGreaterThanOrEqual(0)
      expect(hueOf(id)).toBeLessThan(360)
    }
  })

  it('tells different ids apart', () => {
    expect(hueOf('u-1')).not.toBe(hueOf('u-2'))
  })
})

describe('avatarUrlsOf', () => {
  const person = (id: string, email: string) => ({ id, label: id, email, initials: 'XX', hue: 0 })

  it('maps each person to the URL given for their email', async () => {
    const urls = await avatarUrlsOf(
      [person('u-1', 'a@example.com'), person('u-2', 'b@example.com')],
      async (email) => `https://img/${email}`
    )

    expect(Array.from(urls)).toEqual([
      ['u-1', 'https://img/a@example.com'],
      ['u-2', 'https://img/b@example.com'],
    ])
  })

  it('leaves out who has no email, no URL, or whose URL cannot be computed', async () => {
    const resolve = jest.fn(async (email: string) => {
      if (email === 'boom@example.com') throw new Error('no crypto')
      return email === 'none@example.com' ? null : `https://img/${email}`
    })

    const urls = await avatarUrlsOf(
      [
        person('u-1', ''),
        person('u-2', 'none@example.com'),
        person('u-3', 'boom@example.com'),
        person('u-4', 'ok@example.com'),
      ],
      resolve
    )

    expect(Array.from(urls)).toEqual([['u-4', 'https://img/ok@example.com']])
    // Nothing is asked for a person without an email.
    expect(resolve).not.toHaveBeenCalledWith('')
  })
})

describe('presentMembers', () => {
  const directory = [
    member({ id: 'u-1', email: 'alice@example.com', firstname: 'Alice', lastname: 'Smith' }),
    member({ id: 'U-2', email: 'bob@example.com' }),
    member({ id: 'u-3', email: 'carol@example.com' }),
  ]

  it('lists the members who are present, whatever the letter case of the ids', () => {
    expect(presentMembers(directory, ['u-2', 'U-3'], null).map((p) => p.id)).toEqual(['U-2', 'u-3'])
  })

  it('leaves the current user out, whatever the letter case of the ids', () => {
    expect(presentMembers(directory, ['u-1', 'u-2', 'u-3'], 'U-3').map((p) => p.id)).toEqual(['u-1', 'U-2'])
    expect(presentMembers(directory, ['u-3'], 'u-3')).toEqual([])
  })

  it('names them, with their initials, by the best label', () => {
    expect(presentMembers(directory, ['u-1', 'u-2'], null)).toEqual([
      { id: 'u-1', label: 'Alice Smith', email: 'alice@example.com', initials: 'AS', hue: hueOf('u-1') },
      { id: 'U-2', label: 'bob@example.com', email: 'bob@example.com', initials: 'BO', hue: hueOf('U-2') },
    ])
  })

  it('leaves out a present id who is not a member of the case, and shows nobody when nobody is there', () => {
    expect(presentMembers(directory, ['u-9'], null)).toEqual([])
    expect(presentMembers(directory, [], null)).toEqual([])
  })
})

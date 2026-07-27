import { NamespaceUserListItem, NamespaceUserListItemRoleEnum } from '@whoz-oss/agentos-api-client'
import { firstValueFrom } from 'rxjs'
import { UserGroupMemberAutocompleteDataSource } from './user-group-member.data-source'

function user(externalId: string, email: string, firstname?: string, lastname?: string): NamespaceUserListItem {
  return { externalId, email, firstname, lastname, id: externalId, role: NamespaceUserListItemRoleEnum.MEMBER }
}

async function search(
  source: UserGroupMemberAutocompleteDataSource,
  query: string
): Promise<{ id: string; name: string }[]> {
  return (await firstValueFrom(source.search(query))) as { id: string; name: string }[]
}

describe('UserGroupMemberAutocompleteDataSource', () => {
  const candidates = [
    user('alice@corp', 'alice@corp', 'Alice', 'Martin'),
    user('bob@corp', 'bob@corp', 'Bob', 'Durand'),
    user('carol@corp', 'carol@corp'),
  ]

  it('matches on external id, email and the member label, case-insensitively', async () => {
    const source = new UserGroupMemberAutocompleteDataSource(
      () => candidates,
      () => new Set()
    )

    expect((await search(source, 'MARTIN')).map((i) => i.id)).toEqual(['alice@corp'])
    expect((await search(source, 'bob@corp')).map((i) => i.id)).toEqual(['bob@corp'])
    expect((await search(source, 'carol')).map((i) => i.id)).toEqual(['carol@corp'])
  })

  it('excludes users already selected as members', async () => {
    const source = new UserGroupMemberAutocompleteDataSource(
      () => candidates,
      () => new Set(['alice@corp'])
    )

    const ids = (await search(source, 'corp')).map((i) => i.id)

    expect(ids).not.toContain('alice@corp')
    expect(ids).toEqual(['bob@corp', 'carol@corp'])
  })

  it('reflects the current exclusion set on every search (read lazily)', async () => {
    const excluded = new Set<string>()
    const source = new UserGroupMemberAutocompleteDataSource(
      () => candidates,
      () => excluded
    )

    expect((await search(source, 'corp')).map((i) => i.id)).toContain('bob@corp')
    excluded.add('bob@corp')
    expect((await search(source, 'corp')).map((i) => i.id)).not.toContain('bob@corp')
  })

  it('caps the suggestions at the maximum', async () => {
    const many = Array.from({ length: 30 }, (_, i) => user(`u${i}@corp`, `u${i}@corp`))
    const source = new UserGroupMemberAutocompleteDataSource(
      () => many,
      () => new Set()
    )

    expect(await search(source, 'corp')).toHaveLength(20)
  })
})

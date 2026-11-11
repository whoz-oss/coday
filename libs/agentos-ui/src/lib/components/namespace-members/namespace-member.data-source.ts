import { AutocompleteDataSource, AutocompleteItem } from '@whoz-oss/design-system'
import { Observable, of } from 'rxjs'
import { memberLabel } from './namespace-members.util'

const MAX_SUGGESTIONS = 20

/**
 * Minimal shape the data source needs from a candidate. Structural so that both a platform
 * `User` (super-admin directory) and a `MemberItem` (members of a namespace) fit.
 */
export interface MemberCandidate {
  id?: string
  externalId?: string
  email?: string
  firstname?: string
  lastname?: string
}

/**
 * In-memory autocomplete over a list of candidate users (`User` or `MemberItem`),
 * excluding those already listed as members of the host entity.
 *
 * Keyed on the internal `id` (UUID) — unlike UserGroupMemberAutocompleteDataSource, which
 * keys on `externalId` and sources from `listNamespaceUsers()`. Here we need the internal id
 * because `UserMembershipRole.userId` is the internal UUID. The candidate pool is chosen by
 * the host: the whole platform directory for super-admins (`UserControllerService.listAllUser()`),
 * or the members of the namespace for everyone else.
 *
 * The candidate list is loaded once by the host component; filtering is synchronous. Both
 * accessors are read lazily on each search so the source always reflects the current selection.
 */
export class NamespaceMemberAutocompleteDataSource implements AutocompleteDataSource {
  constructor(
    private readonly candidates: () => MemberCandidate[],
    private readonly excludedUserIds: () => Set<string>
  ) {}

  search(query: string): Observable<AutocompleteItem[]> {
    const normalized = query.toLowerCase()
    const excluded = this.excludedUserIds()
    const items = this.candidates()
      .filter((user) => !!user.id && !excluded.has(user.id))
      .filter((user) => this.matches(user, normalized))
      .slice(0, MAX_SUGGESTIONS)
      .map((user) => ({ id: user.id as string, name: memberLabel(user), description: user.email }))
    return of(items)
  }

  private matches(user: MemberCandidate, normalizedQuery: string): boolean {
    return (
      (user.externalId ?? '').toLowerCase().includes(normalizedQuery) ||
      (user.email ?? '').toLowerCase().includes(normalizedQuery) ||
      memberLabel(user).toLowerCase().includes(normalizedQuery)
    )
  }
}

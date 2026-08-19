import { User } from '@whoz-oss/agentos-api-client'
import { AutocompleteDataSource, AutocompleteItem } from '@whoz-oss/design-system'
import { Observable, of } from 'rxjs'
import { memberLabel } from './namespace-members.util'

const MAX_SUGGESTIONS = 20

/**
 * In-memory autocomplete over ALL platform users (`UserControllerService.listAllUser()`),
 * excluding those already listed as members of the namespace.
 *
 * Keyed on the internal `id` (UUID) — unlike UserGroupMemberAutocompleteDataSource, which
 * keys on `externalId` and sources from `listNamespaceUsers()`. Here we need the internal id
 * because `UserMembershipRole.userId` is the internal UUID, and the candidate pool is the
 * whole platform directory (only accessible to super-admins), not the namespace's existing
 * users.
 *
 * The candidate list is loaded once by the host component; filtering is synchronous. Both
 * accessors are read lazily on each search so the source always reflects the current selection.
 */
export class NamespaceMemberAutocompleteDataSource implements AutocompleteDataSource {
  constructor(
    private readonly candidates: () => User[],
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

  private matches(user: User, normalizedQuery: string): boolean {
    return (
      (user.externalId ?? '').toLowerCase().includes(normalizedQuery) ||
      (user.email ?? '').toLowerCase().includes(normalizedQuery) ||
      memberLabel(user).toLowerCase().includes(normalizedQuery)
    )
  }
}

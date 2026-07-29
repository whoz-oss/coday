import { NamespaceUserListItem } from '@whoz-oss/agentos-api-client'
import { AutocompleteDataSource, AutocompleteItem } from '@whoz-oss/design-system'
import { Observable, of } from 'rxjs'
import { memberLabel } from './user-group-form.util'

const MAX_SUGGESTIONS = 20

/**
 * In-memory autocomplete over a namespace's users, excluding those already selected as members.
 *
 * The candidate list is loaded once by the form; filtering is synchronous. Both accessors are
 * read lazily on each search so the source always reflects the current selection.
 */
export class UserGroupMemberAutocompleteDataSource implements AutocompleteDataSource {
  constructor(
    private readonly candidates: () => NamespaceUserListItem[],
    private readonly excludedExternalIds: () => Set<string>
  ) {}

  search(query: string): Observable<AutocompleteItem[]> {
    const normalized = query.toLowerCase()
    const excluded = this.excludedExternalIds()
    const items = this.candidates()
      .filter((user) => !excluded.has(user.externalId))
      .filter((user) => this.matches(user, normalized))
      .slice(0, MAX_SUGGESTIONS)
      .map((user) => ({ id: user.externalId, name: memberLabel(user), description: user.email }))
    return of(items)
  }

  private matches(user: NamespaceUserListItem, normalizedQuery: string): boolean {
    return (
      user.externalId.toLowerCase().includes(normalizedQuery) ||
      user.email.toLowerCase().includes(normalizedQuery) ||
      memberLabel(user).toLowerCase().includes(normalizedQuery)
    )
  }
}

import { Observable } from 'rxjs'

/**
 * Represents a single autocomplete suggestion item.
 */
export interface AutocompleteItem {
  /** Unique identifier (username, agent ID, etc.) */
  id: string
  /** Display label */
  name: string
  /** Optional secondary text */
  description?: string
}

/**
 * Data source contract for the autocomplete component.
 * Implement this interface to provide domain-specific autocomplete suggestions.
 *
 * @example
 * class UserAutocompleteComponent implements AutocompleteDataSource {
 *   search(query: string): Observable<AutocompleteItem[]> {
 *     return this.userApi.listUsers().pipe(
 *       map(users => users.filter(u => u.username.includes(query)))
 *     )
 *   }
 * }
 */
export interface AutocompleteDataSource {
  search(query: string): Observable<AutocompleteItem[]>
}

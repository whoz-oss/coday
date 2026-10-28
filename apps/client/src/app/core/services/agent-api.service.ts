import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable, throwError } from 'rxjs'
import { catchError, map, shareReplay } from 'rxjs/operators'
import { filterAndSortAutocomplete } from '../../utils/autocomplete.utils'

/**
 * Agent for autocomplete
 */
export interface AgentAutocomplete {
  name: string
  description: string
}

/**
 * Service for agent-related API calls
 * Caches agent list per project and filters client-side for performance
 */
@Injectable({
  providedIn: 'root',
})
export class AgentApiService {
  private readonly http = inject(HttpClient)

  // Cache: Map<projectName, Observable<agents[]>>
  private agentsCache = new Map<string, Observable<AgentAutocomplete[]>>()

  /**
   * Get all agents for a project (cached)
   * @param projectName Project name
   * @returns Observable of all agents
   */
  getAgents(projectName: string): Observable<AgentAutocomplete[]> {
    let agents$ = this.agentsCache.get(projectName)
    if (!agents$) {
      agents$ = this.http.get<AgentAutocomplete[]>(`/api/projects/${projectName}/agents`).pipe(
        catchError((error) => {
          this.agentsCache.delete(projectName)
          return throwError(() => error)
        }),
        shareReplay({ bufferSize: 1, refCount: false })
      )
      this.agentsCache.set(projectName, agents$)
    }

    return agents$
  }

  /**
   * Get agents matching query for autocomplete (client-side filtering + relevance sort).
   *
   * Results are ordered by match relevance:
   *   1. Name starts with query (highest relevance)
   *   2. Name contains query but does not start with it
   *   3. Description match only (lowest relevance)
   *
   * @param projectName Project name
   * @param query Query string to filter agents
   * @returns Observable of filtered and sorted agent autocomplete items
   */
  getAgentsAutocomplete(projectName: string, query: string): Observable<AgentAutocomplete[]> {
    return this.getAgents(projectName).pipe(map((agents) => filterAndSortAutocomplete(agents, query)))
  }

  /**
   * Clear cache for a specific project (useful when project config changes)
   * @param projectName Project name
   */
  clearCache(projectName: string): void {
    this.agentsCache.delete(projectName)
  }

  /**
   * Clear all cache
   */
  clearAllCache(): void {
    this.agentsCache.clear()
  }
}

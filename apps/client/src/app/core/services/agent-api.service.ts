import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable, of } from 'rxjs'
import { map, tap } from 'rxjs/operators'

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

  // Cache: Map<projectName, agents[]>
  private agentsCache = new Map<string, AgentAutocomplete[]>()

  /**
   * Get all agents for a project (cached)
   * @param projectName Project name
   * @returns Observable of all agents
   */
  getAgents(projectName: string): Observable<AgentAutocomplete[]> {
    // Return from cache if available
    const cached = this.agentsCache.get(projectName)
    if (cached) {
      return of(cached)
    }

    // Fetch from API and cache
    return this.http.get<AgentAutocomplete[]>(`/api/projects/${projectName}/agents`).pipe(
      tap((agents) => {
        this.agentsCache.set(projectName, agents)
      })
    )
  }

  /**
   * Get agents matching query for autocomplete (client-side filtering)
   * @param projectName Project name
   * @param query Query string to filter agents
   * @returns Observable of filtered agent autocomplete items
   */
  getAgentsAutocomplete(projectName: string, query: string): Observable<AgentAutocomplete[]> {
    return this.getAgents(projectName).pipe(
      map((agents) => {
        if (!query) {
          return agents
        }

        const lowerQuery = query.toLowerCase()
        return agents.filter((agent) => {
          const lowerName = agent.name.toLowerCase()
          const lowerDescription = agent.description?.toLowerCase() || ''

          // Match if query is found in name or description
          return lowerName.includes(lowerQuery) || lowerDescription.includes(lowerQuery)
        })
      })
    )
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

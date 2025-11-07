import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Agent for autocomplete
 */
export interface AgentAutocomplete {
  name: string
  description: string
}

/**
 * Service for agent-related API calls
 */
@Injectable({
  providedIn: 'root',
})
export class AgentApiService {
  private readonly http = inject(HttpClient)

  /**
   * Get agents matching query for autocomplete
   * @param projectName Project name
   * @param query Query string to filter agents (optional)
   * @returns Observable of agent autocomplete items
   */
  getAgentsAutocomplete(projectName: string, query?: string): Observable<AgentAutocomplete[]> {
    const params: any = { project: projectName }
    if (query) {
      params.query = query
    }

    return this.http.get<AgentAutocomplete[]>('/api/agents/autocomplete', { params })
  }
}

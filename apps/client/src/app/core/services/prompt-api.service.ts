import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable, of } from 'rxjs'
import { map } from 'rxjs/operators'
import { ProjectStateService } from './project-state.service'

/**
 * Source location for prompt storage
 */
export type PromptSource = 'local' | 'project'

/**
 * Prompt information for autocomplete (minimal data)
 */
export interface PromptAutocomplete {
  name: string
  description: string
  parameterFormat?: string // Format hint for parameters
}

/**
 * Prompt model matching backend
 */
export interface Prompt {
  id: string
  name: string
  description: string
  commands: string[]
  webhookEnabled: boolean
  createdBy: string
  createdAt: string
  updatedAt?: string
  threadLifetime?: string
  activeThreadId?: string
  source: PromptSource
}

export interface PromptInfo {
  id: string
  name: string
  description: string
  webhookEnabled: boolean
  createdBy: string
  createdAt: string
  updatedAt?: string
  source: PromptSource
  parameterFormat?: string // Format hint for parameters
}

/**
 * PromptApiService - HTTP client for prompt management
 *
 * Endpoints:
 * - GET    /api/projects/:projectName/prompts
 * - GET    /api/projects/:projectName/prompts/:id
 * - POST   /api/projects/:projectName/prompts
 * - PUT    /api/projects/:projectName/prompts/:id
 * - DELETE /api/projects/:projectName/prompts/:id
 * - POST   /api/projects/:projectName/prompts/:id/webhook
 * - DELETE /api/projects/:projectName/prompts/:id/webhook
 */
@Injectable({
  providedIn: 'root',
})
export class PromptApiService {
  private readonly http = inject(HttpClient)
  private readonly projectState = inject(ProjectStateService)

  // Cache for prompt lists by project
  private promptsCache = new Map<string, PromptInfo[]>()

  private getBaseUrl(): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/prompts`
  }

  /**
   * List all prompts for the current project
   */
  listPrompts(): Observable<PromptInfo[]> {
    return this.http.get<PromptInfo[]>(this.getBaseUrl())
  }

  /**
   * Get prompts for autocomplete (filtered by query)
   * Used for slash command autocomplete in chat textarea
   * Uses cache to avoid multiple API calls during typing
   *
   * @param projectName Project name
   * @param query Search query to filter prompts
   * @returns Observable of filtered prompts for autocomplete
   */
  getPromptsAutocomplete(projectName: string, query: string): Observable<PromptAutocomplete[]> {
    // Check cache first
    const cached = this.promptsCache.get(projectName)

    if (cached) {
      // Use cached data and filter client-side
      return of(this.filterPromptsForAutocomplete(cached, query))
    }

    // No cache, fetch from API
    return this.http.get<PromptInfo[]>(`/api/projects/${projectName}/prompts`).pipe(
      map((prompts) => {
        // Store in cache
        this.promptsCache.set(projectName, prompts)

        // Filter and return
        return this.filterPromptsForAutocomplete(prompts, query)
      })
    )
  }

  /**
   * Filter prompts for autocomplete (client-side filtering)
   */
  private filterPromptsForAutocomplete(prompts: PromptInfo[], query: string): PromptAutocomplete[] {
    const lowerQuery = query.toLowerCase()
    const filtered = prompts.filter(
      (prompt) =>
        prompt.name.toLowerCase().includes(lowerQuery) || prompt.description.toLowerCase().includes(lowerQuery)
    )

    return filtered.map((prompt) => ({
      name: prompt.name,
      description: prompt.description,
      parameterFormat: prompt.parameterFormat,
    }))
  }

  /**
   * Clear the prompts cache for a project (call after creating/updating/deleting prompts)
   */
  clearCache(projectName?: string): void {
    if (projectName) {
      this.promptsCache.delete(projectName)
    } else {
      this.promptsCache.clear()
    }
  }

  /**
   * Get a specific prompt by ID
   */
  getPrompt(id: string): Observable<Prompt> {
    return this.http.get<Prompt>(`${this.getBaseUrl()}/${id}`)
  }

  /**
   * Create a new prompt
   */
  createPrompt(prompt: Omit<Prompt, 'id' | 'createdAt' | 'createdBy' | 'updatedAt'>): Observable<Prompt> {
    return this.http.post<Prompt>(this.getBaseUrl(), prompt).pipe(
      map((result) => {
        // Clear cache after creating a prompt
        this.clearCache(this.projectState.getSelectedProjectId() ?? undefined)
        return result
      })
    )
  }

  /**
   * Update an existing prompt
   */
  updatePrompt(id: string, updates: Partial<Prompt>): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.put<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl()}/${id}`, updates).pipe(
      map((result) => {
        // Clear cache after updating a prompt
        this.clearCache(this.projectState.getSelectedProjectId() ?? undefined)
        return result
      })
    )
  }

  /**
   * Delete a prompt
   */
  deletePrompt(id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${id}`).pipe(
      map((result) => {
        // Clear cache after deleting a prompt
        this.clearCache(this.projectState.getSelectedProjectId() ?? undefined)
        return result
      })
    )
  }

  /**
   * Enable webhook for a prompt (CODAY_ADMIN only)
   */
  enableWebhook(id: string): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.post<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl()}/${id}/webhook`, {})
  }

  /**
   * Disable webhook for a prompt (CODAY_ADMIN only)
   */
  disableWebhook(id: string): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.delete<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl()}/${id}/webhook`)
  }
}

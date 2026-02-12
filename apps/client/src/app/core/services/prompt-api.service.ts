import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ProjectStateService } from './project-state.service'

/**
 * Source location for prompt storage
 */
export type PromptSource = 'local' | 'project'

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
   * Get a specific prompt by ID
   */
  getPrompt(id: string): Observable<Prompt> {
    return this.http.get<Prompt>(`${this.getBaseUrl()}/${id}`)
  }

  /**
   * Create a new prompt
   */
  createPrompt(prompt: Omit<Prompt, 'id' | 'createdAt' | 'createdBy' | 'updatedAt'>): Observable<Prompt> {
    return this.http.post<Prompt>(this.getBaseUrl(), prompt)
  }

  /**
   * Update an existing prompt
   */
  updatePrompt(id: string, updates: Partial<Prompt>): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.put<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl()}/${id}`, updates)
  }

  /**
   * Delete a prompt
   */
  deletePrompt(id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${id}`)
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

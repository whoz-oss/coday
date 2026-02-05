import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

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
}

export interface PromptInfo {
  id: string
  name: string
  description: string
  webhookEnabled: boolean
  createdBy: string
  createdAt: string
  updatedAt?: string
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
  private http = inject(HttpClient)

  private getBaseUrl(projectName: string): string {
    return `/api/projects/${projectName}/prompts`
  }

  /**
   * List all prompts for a project
   */
  listPrompts(projectName: string): Observable<PromptInfo[]> {
    return this.http.get<PromptInfo[]>(this.getBaseUrl(projectName))
  }

  /**
   * Get a specific prompt by ID
   */
  getPrompt(projectName: string, id: string): Observable<Prompt> {
    return this.http.get<Prompt>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Create a new prompt
   */
  createPrompt(
    projectName: string,
    prompt: Omit<Prompt, 'id' | 'createdAt' | 'createdBy' | 'updatedAt'>
  ): Observable<Prompt> {
    return this.http.post<Prompt>(this.getBaseUrl(projectName), prompt)
  }

  /**
   * Update an existing prompt
   */
  updatePrompt(
    projectName: string,
    id: string,
    updates: Partial<Prompt>
  ): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.put<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl(projectName)}/${id}`, updates)
  }

  /**
   * Delete a prompt
   */
  deletePrompt(projectName: string, id: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${id}`)
  }

  /**
   * Enable webhook for a prompt (CODAY_ADMIN only)
   */
  enableWebhook(projectName: string, id: string): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.post<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl(projectName)}/${id}/webhook`, {})
  }

  /**
   * Disable webhook for a prompt (CODAY_ADMIN only)
   */
  disableWebhook(projectName: string, id: string): Observable<{ success: boolean; prompt: Prompt }> {
    return this.http.delete<{ success: boolean; prompt: Prompt }>(`${this.getBaseUrl(projectName)}/${id}/webhook`)
  }
}

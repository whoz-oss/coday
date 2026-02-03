import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Webhook interface matching backend definition
 * Note: 'project' is no longer part of the model - webhooks are stored per project
 */
export interface Webhook {
  uuid: string
  name: string
  createdBy: string
  createdAt: Date | string
  commandType: 'free' | 'template'
  commands?: string[]
}

/**
 * Data for creating a new webhook (without auto-generated fields)
 * Note: project is passed via URL path, not in the data
 */
export interface WebhookCreateData {
  name: string
  commandType: 'free' | 'template'
  commands?: string[]
}

/**
 * Data for updating a webhook (partial updates allowed)
 */
export interface WebhookUpdateData {
  name?: string
  commandType?: 'free' | 'template'
  commands?: string[]
}

/**
 * Angular service for webhook API communication
 *
 * NEW ARCHITECTURE:
 * - CRUD operations are scoped to projects: /api/projects/:projectName/webhooks
 * - Webhooks are stored per project and filtered by ownership (owner OR CODAY_ADMIN)
 * - All methods require projectName parameter
 *
 * All methods return Observables for reactive programming patterns.
 */
@Injectable({
  providedIn: 'root',
})
export class WebhookApiService {
  private http = inject(HttpClient)

  /**
   * Get base URL for webhook operations within a project
   */
  private getBaseUrl(projectName: string): string {
    return `/api/projects/${projectName}/webhooks`
  }

  /**
   * List all webhooks for a project (filtered by access control)
   */
  listWebhooks(projectName: string): Observable<Webhook[]> {
    return this.http.get<Webhook[]>(this.getBaseUrl(projectName))
  }

  /**
   * Get specific webhook by UUID within a project
   */
  getWebhook(projectName: string, uuid: string): Observable<Webhook> {
    return this.http.get<Webhook>(`${this.getBaseUrl(projectName)}/${uuid}`)
  }

  /**
   * Create new webhook in a project
   */
  createWebhook(projectName: string, data: WebhookCreateData): Observable<Webhook> {
    return this.http.post<Webhook>(this.getBaseUrl(projectName), data)
  }

  /**
   * Update existing webhook in a project
   */
  updateWebhook(
    projectName: string,
    uuid: string,
    data: WebhookUpdateData
  ): Observable<{ success: boolean; webhook: Webhook }> {
    return this.http.put<{ success: boolean; webhook: Webhook }>(`${this.getBaseUrl(projectName)}/${uuid}`, data)
  }

  /**
   * Delete webhook from a project
   */
  deleteWebhook(projectName: string, uuid: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${uuid}`)
  }
}

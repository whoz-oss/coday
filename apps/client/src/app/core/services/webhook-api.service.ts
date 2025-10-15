import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Webhook interface matching backend definition
 */
export interface Webhook {
  uuid: string
  name: string
  project: string
  createdBy: string
  createdAt: Date | string
  commandType: 'free' | 'template'
  commands?: string[]
}

/**
 * Data for creating a new webhook (without auto-generated fields)
 */
export interface WebhookCreateData {
  name: string
  project: string
  commandType: 'free' | 'template'
  commands?: string[]
}

/**
 * Data for updating a webhook (partial updates allowed)
 */
export interface WebhookUpdateData {
  name?: string
  project?: string
  commandType?: 'free' | 'template'
  commands?: string[]
}

/**
 * Angular service for webhook API communication
 * 
 * Provides methods to interact with the webhook REST API endpoints.
 * All methods return Observables for reactive programming patterns.
 */
@Injectable({
  providedIn: 'root'
})
export class WebhookApiService {
  private http = inject(HttpClient)
  private readonly BASE_URL = '/api/webhooks'

  /**
   * List all webhooks
   */
  listWebhooks(): Observable<Webhook[]> {
    return this.http.get<Webhook[]>(this.BASE_URL)
  }

  /**
   * Get specific webhook by UUID
   */
  getWebhook(uuid: string): Observable<Webhook> {
    return this.http.get<Webhook>(`${this.BASE_URL}/${uuid}`)
  }

  /**
   * Create new webhook
   */
  createWebhook(data: WebhookCreateData): Observable<Webhook> {
    return this.http.post<Webhook>(this.BASE_URL, data)
  }

  /**
   * Update existing webhook
   */
  updateWebhook(uuid: string, data: WebhookUpdateData): Observable<{success: boolean, webhook: Webhook}> {
    return this.http.put<{success: boolean, webhook: Webhook}>(`${this.BASE_URL}/${uuid}`, data)
  }

  /**
   * Delete webhook
   */
  deleteWebhook(uuid: string): Observable<{success: boolean, message: string}> {
    return this.http.delete<{success: boolean, message: string}>(`${this.BASE_URL}/${uuid}`)
  }
}

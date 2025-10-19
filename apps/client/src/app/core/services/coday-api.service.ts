import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { tap, map } from 'rxjs/operators'
import { CodayEvent } from '@coday/coday-events'
import { SessionState } from '@coday/model/session-state'

@Injectable({
  providedIn: 'root'
})
export class CodayApiService {
  private clientId: string | null = null

  // Modern Angular dependency injection
  private http = inject(HttpClient)
  
  constructor() {
    // Don't generate clientId automatically anymore
    // It will be generated lazily when needed (legacy mode)
  }

  /**
   * Get or create client ID for this session
   */
  private getOrCreateClientId(): string {
    // Check URL parameters first
    const params = new URLSearchParams(window.location.search)
    let clientId = params.get('clientId')

    if (!clientId) {
      // Generate new if not found
      clientId = Math.random().toString(36).substring(2, 15)

      // Update URL without page reload
      const newUrl = new URL(window.location.href)
      newUrl.searchParams.set('clientId', clientId)
      window.history.pushState({}, '', newUrl)
    }

    return clientId
  }

  /**
   * Get the current client ID (lazy initialization for legacy mode)
   */
  getClientId(): string {
    if (!this.clientId) {
      this.clientId = this.getOrCreateClientId()
    }
    return this.clientId
  }

  /**
   * Send an event to the Coday API (thread-based)
   */
  sendEvent(event: CodayEvent, projectName?: string, threadId?: string): Observable<any> {
    // Use new thread-based endpoint if project and thread are provided
    if (projectName && threadId) {
      return this.http.post(`/api/projects/${projectName}/threads/${threadId}/message`, event, {
        observe: 'response',
        responseType: 'text'
      }).pipe(
        tap(response => {
          if (response.status !== 200) {
            console.warn('[API] Unexpected status:', response.status)
          }
        }),
        map(response => response.body)
      )
    }

    // Fallback to legacy endpoint
    return this.http.post(`/api/message?clientId=${this.getClientId()}`, event, {
      observe: 'response',
      responseType: 'text'
    }).pipe(
      tap(response => {
        if (response.status !== 200) {
          console.warn('[API] Unexpected status:', response.status)
        }
      }),
      map(response => response.body)
    )
  }

  /**
   * Stop the current execution
   */
  stopExecution(): Observable<any> {
    return this.http.post(`/api/stop?clientId=${this.getClientId()}`, {})
  }

  /**
   * Get event details by ID
   */
  getEventDetails(eventId: string): Observable<string> {
    return this.http.get(`/api/event/${eventId}?clientId=${this.getClientId()}`, {
      responseType: 'text'
    })
  }


  /**
   * Get the SSE URL for events (legacy)
   */
  getEventsUrl(): string {
    return `/events?clientId=${this.getClientId()}`
  }

  /**
   * Delete a message from the thread (rewind/retry functionality)
   */
  deleteMessage(eventId: string): Observable<{success: boolean, message?: string, error?: string}> {
    const url = `/api/thread/message/${encodeURIComponent(eventId)}?clientId=${this.getClientId()}`
    return this.http.delete<{success: boolean, message?: string, error?: string}>(url).pipe(
      tap(response => console.log('[API] Delete message response:', response))
    )
  }
}
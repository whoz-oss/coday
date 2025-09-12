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
  private clientId: string

  // Modern Angular dependency injection
  private http = inject(HttpClient)
  
  constructor() {
    this.clientId = this.getOrCreateClientId()
    console.log('[API] Client ID:', this.clientId)
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
   * Get the current client ID
   */
  getClientId(): string {
    return this.clientId
  }

  /**
   * Send an event to the Coday API
   */
  sendEvent(event: CodayEvent): Observable<any> {
    console.log('[API] Sending:', event.type)
    
    return this.http.post(`/api/message?clientId=${this.clientId}`, event, {
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
    console.log('[API] Stopping execution')
    return this.http.post(`/api/stop?clientId=${this.clientId}`, {})
  }

  /**
   * Get event details by ID
   */
  getEventDetails(eventId: string): Observable<string> {
    return this.http.get(`/api/event/${eventId}?clientId=${this.clientId}`, {
      responseType: 'text'
    })
  }


  /**
   * Get the SSE URL for events
   */
  getEventsUrl(): string {
    return `/events?clientId=${this.clientId}`
  }

  /**
   * Get session state (projects and threads)
   */
  getSessionState(): Observable<SessionState> {
    console.log('[API] Getting session state')
    return this.http.get<SessionState>(`/api/session/state?clientId=${this.clientId}`).pipe(
      tap(state => console.log('[API] Session state received:', state))
    )
  }
}
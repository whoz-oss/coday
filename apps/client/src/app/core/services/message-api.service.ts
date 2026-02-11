import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ProjectStateService } from './project-state.service'

/**
 * Service for message-related API calls.
 * Handles HTTP communication with the message REST endpoints.
 */
@Injectable({
  providedIn: 'root',
})
export class MessageApiService {
  private readonly http = inject(HttpClient)
  private readonly projectState = inject(ProjectStateService)

  /**
   * Build base URL for the current project's thread messages
   */
  private getBaseUrl(threadId: string): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/threads/${threadId}/messages`
  }

  /**
   * Get all messages from a thread
   * @param threadId Thread ID
   * @returns Observable of messages array
   */
  getMessages(threadId: string): Observable<any[]> {
    return this.http.get<any[]>(this.getBaseUrl(threadId))
  }

  /**
   * Send a message to a thread
   * @param threadId Thread ID
   * @param payload AnswerEvent payload
   * @returns Observable of response
   */
  sendMessage(threadId: string, payload: any): Observable<any> {
    return this.http.post(this.getBaseUrl(threadId), payload, {
      responseType: 'text',
    })
  }

  /**
   * Get a specific message by event ID
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of message
   */
  getMessage(threadId: string, eventId: string): Observable<any> {
    return this.http.get<any>(`${this.getBaseUrl(threadId)}/${encodeURIComponent(eventId)}`)
  }

  /**
   * Delete a message (truncate thread at this message)
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of response
   */
  deleteMessage(threadId: string, eventId: string): Observable<any> {
    return this.http.delete(`${this.getBaseUrl(threadId)}/${encodeURIComponent(eventId)}`)
  }

  /**
   * Get formatted message for display (temporary endpoint)
   * TODO: Remove once frontend has proper event display components
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of formatted text
   */
  getFormattedMessage(threadId: string, eventId: string): Observable<string> {
    return this.http.get(`${this.getBaseUrl(threadId)}/${encodeURIComponent(eventId)}/formatted`, {
      responseType: 'text',
    })
  }
}

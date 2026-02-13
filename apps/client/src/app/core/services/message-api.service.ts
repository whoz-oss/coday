import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'

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
  private readonly threadState = inject(ThreadStateService)

  /**
   * Build base URL for the current project's thread messages
   */
  private getBaseUrl(): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/threads/${this.threadState.getSelectedThreadIdOrThrow()}/messages`
  }

  /**
   * Get all messages from the current thread
   * @returns Observable of messages array
   */
  getMessages(): Observable<any[]> {
    return this.http.get<any[]>(this.getBaseUrl())
  }

  /**
   * Send a message to the current thread
   * @param payload AnswerEvent payload
   * @returns Observable of response
   */
  sendMessage(payload: any): Observable<any> {
    return this.http.post(this.getBaseUrl(), payload, {
      responseType: 'text',
    })
  }

  /**
   * Get a specific message by event ID
   * @param eventId Event timestamp ID
   * @returns Observable of message
   */
  getMessage(eventId: string): Observable<any> {
    return this.http.get<any>(`${this.getBaseUrl()}/${encodeURIComponent(eventId)}`)
  }

  /**
   * Delete a message (truncate thread at this message)
   * @param eventId Event timestamp ID
   * @returns Observable of response
   */
  deleteMessage(eventId: string): Observable<any> {
    return this.http.delete(`${this.getBaseUrl()}/${encodeURIComponent(eventId)}`)
  }

  /**
   * Get formatted message for display (temporary endpoint)
   * TODO: Remove once frontend has proper event display components
   * @param eventId Event timestamp ID
   * @returns Observable of formatted text
   */
  getFormattedMessage(eventId: string): Observable<string> {
    return this.http.get(`${this.getBaseUrl()}/${encodeURIComponent(eventId)}/formatted`, {
      responseType: 'text',
    })
  }
}

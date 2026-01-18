import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Service for message-related API calls.
 * Handles HTTP communication with the message REST endpoints.
 */
@Injectable({
  providedIn: 'root',
})
export class MessageApiService {
  private readonly http = inject(HttpClient)

  /**
   * Get all messages from a thread
   * @param projectName Project name
   * @param threadId Thread ID
   * @returns Observable of messages array
   */
  getMessages(projectName: string, threadId: string): Observable<any[]> {
    return this.http.get<any[]>(`/api/projects/${projectName}/threads/${threadId}/messages`)
  }

  /**
   * Send a message to a thread
   * @param projectName Project name
   * @param threadId Thread ID
   * @param payload AnswerEvent payload
   * @returns Observable of response
   */
  sendMessage(projectName: string, threadId: string, payload: any): Observable<any> {
    return this.http.post(`/api/projects/${projectName}/threads/${threadId}/messages`, payload, {
      responseType: 'text',
    })
  }

  /**
   * Get a specific message by event ID
   * @param projectName Project name
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of message
   */
  getMessage(projectName: string, threadId: string, eventId: string): Observable<any> {
    return this.http.get<any>(
      `/api/projects/${projectName}/threads/${threadId}/messages/${encodeURIComponent(eventId)}`
    )
  }

  /**
   * Delete a message (truncate thread at this message)
   * @param projectName Project name
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of response
   */
  deleteMessage(projectName: string, threadId: string, eventId: string): Observable<any> {
    return this.http.delete(`/api/projects/${projectName}/threads/${threadId}/messages/${encodeURIComponent(eventId)}`)
  }

  /**
   * Get formatted message for display (temporary endpoint)
   * TODO: Remove once frontend has proper event display components
   * @param projectName Project name
   * @param threadId Thread ID
   * @param eventId Event timestamp ID
   * @returns Observable of formatted text
   */
  getFormattedMessage(projectName: string, threadId: string, eventId: string): Observable<string> {
    return this.http.get(
      `/api/projects/${projectName}/threads/${threadId}/messages/${encodeURIComponent(eventId)}/formatted`,
      { responseType: 'text' }
    )
  }

  /**
   * Toggle auto-accept state for file operations in a thread
   * @param projectName Project name
   * @param threadId Thread ID
   * @returns Observable of response
   */
  toggleAutoAccept(projectName: string, threadId: string): Observable<any> {
    return this.http.post(`/api/projects/${projectName}/threads/${threadId}/toggle-auto-accept`, {})
  }
}

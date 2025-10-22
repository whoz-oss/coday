import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ThreadSummary } from '@coday/ai-thread/ai-thread.types'

/**
 * Thread list response
 */
export interface ThreadListResponse {
  threads: ThreadSummary[]
}

/**
 * Thread details
 */
export interface ThreadDetails {
  id: string
  name: string
  projectId: string
  username: string
  summary: string
  createdDate: string
  modifiedDate: string
  price: number
  messageCount: number
}

/**
 * Thread creation response
 */
export interface ThreadCreationResponse {
  success: boolean
  thread: {
    id: string
    name: string
    projectId: string
    username: string
    createdDate: string
    modifiedDate: string
  }
}

/**
 * Thread update response
 */
export interface ThreadUpdateResponse {
  success: boolean
  thread: {
    id: string
    name: string
    projectId: string
    modifiedDate: string
  }
}

/**
 * Service for interacting with thread REST API
 */
@Injectable({
  providedIn: 'root',
})
export class ThreadApiService {
  private readonly http = inject(HttpClient)

  /**
   * Build base URL for a project's threads
   */
  private getBaseUrl(projectName: string): string {
    return `/api/projects/${projectName}/threads`
  }

  /**
   * List all threads for a project (filtered by current user on backend)
   * @param projectName Project name
   */
  listThreads(projectName: string): Observable<ThreadSummary[]> {
    return this.http.get<ThreadSummary[]>(this.getBaseUrl(projectName))
  }

  /**
   * Get details of a specific thread
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  getThread(projectName: string, threadId: string): Observable<ThreadDetails> {
    return this.http.get<ThreadDetails>(`${this.getBaseUrl(projectName)}/${threadId}`)
  }

  /**
   * Create a new thread
   * @param projectName Project name
   * @param name Optional thread name
   */
  createThread(projectName: string, name?: string): Observable<ThreadCreationResponse> {
    const body = name ? { name } : {}
    return this.http.post<ThreadCreationResponse>(this.getBaseUrl(projectName), body)
  }

  /**
   * Update a thread (rename)
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param name New thread name
   */
  updateThread(projectName: string, threadId: string, name: string): Observable<ThreadUpdateResponse> {
    return this.http.put<ThreadUpdateResponse>(`${this.getBaseUrl(projectName)}/${threadId}`, { name })
  }

  /**
   * Delete a thread
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  deleteThread(projectName: string, threadId: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${threadId}`)
  }

  /**
   * Stop the current execution for a thread
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  stopThread(projectName: string, threadId: string): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(`${this.getBaseUrl(projectName)}/${threadId}/stop`, {})
  }
}

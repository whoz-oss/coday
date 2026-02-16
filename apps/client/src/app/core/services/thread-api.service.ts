import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { ThreadSummary } from '@coday/model'
import { ProjectStateService } from './project-state.service'

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
  private readonly projectState = inject(ProjectStateService)

  /**
   * Build base URL for the current project's threads
   */
  private getBaseUrl(): string {
    return `/api/projects/${this.projectState.getSelectedProjectIdOrThrow()}/threads`
  }

  /**
   * List all threads for the current project (filtered by current user on backend)
   */
  listThreads(): Observable<ThreadSummary[]> {
    return this.http.get<ThreadSummary[]>(this.getBaseUrl())
  }

  /**
   * Get details of a specific thread
   * @param threadId Thread identifier
   */
  getThread(threadId: string): Observable<ThreadDetails> {
    return this.http.get<ThreadDetails>(`${this.getBaseUrl()}/${threadId}`)
  }

  /**
   * Create a new thread
   * @param name Optional thread name
   */
  createThread(name?: string): Observable<ThreadCreationResponse> {
    const body = name ? { name } : {}
    return this.http.post<ThreadCreationResponse>(this.getBaseUrl(), body)
  }

  /**
   * Update a thread (rename)
   * @param threadId Thread identifier
   * @param name New thread name
   */
  updateThread(threadId: string, name: string): Observable<ThreadUpdateResponse> {
    return this.http.put<ThreadUpdateResponse>(`${this.getBaseUrl()}/${threadId}`, { name })
  }

  /**
   * Star a thread (add current user to starring list)
   * @param threadId Thread identifier
   */
  starThread(threadId: string): Observable<{ success: boolean; thread: any }> {
    return this.http.post<{ success: boolean; thread: any }>(`${this.getBaseUrl()}/${threadId}/star`, {})
  }

  /**
   * Unstar a thread (remove current user from starring list)
   * @param threadId Thread identifier
   */
  unstarThread(threadId: string): Observable<{ success: boolean; thread: any }> {
    return this.http.delete<{ success: boolean; thread: any }>(`${this.getBaseUrl()}/${threadId}/star`)
  }

  /**
   * Delete a thread
   * @param threadId Thread identifier
   */
  deleteThread(threadId: string): Observable<{ success: boolean; message: string }> {
    return this.http.delete<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${threadId}`)
  }

  /**
   * Stop the current execution for a thread
   * @param threadId Thread identifier
   */
  stopThread(threadId: string): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(`${this.getBaseUrl()}/${threadId}/stop`, {})
  }
}

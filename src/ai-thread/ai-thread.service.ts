/**
 * Service managing AI thread lifecycle and operations.
 * Provides a simple interface for thread selection and management
 * while handling complex internal state and transitions.
 */

import {BehaviorSubject, Observable} from "rxjs"
import {map} from "rxjs/operators"
import {AiThread} from "./ai-thread"
import {AiThreadRepository} from "./ai-thread.repository"

export interface ThreadSummary {
  id: string
  name: string
  summary: string
  createdDate: string
  modifiedDate: string
}

export class AiThreadService {
  private readonly activeThread$ = new BehaviorSubject<AiThread | null>(null)
  
  constructor(private readonly repository: AiThreadRepository) {}
  
  
  /**
   * Select a thread for use, or create a default one if none exists.
   * This is the main entry point for thread management.
   * 
   * @param threadId Optional ID of thread to select
   * @returns Selected or created thread
   */
  async select(threadId?: string): Promise<AiThread> {

    if (threadId) {
      const thread = await this.repository.getById(threadId)
      if (!thread) {
        throw new Error(`Thread ${threadId} not found`)
      }
      this.activeThread$.next(thread)
      return thread
    }
    
    // No ID provided, get last used or create new
    const threads = await this.repository.listThreads()
    if (threads.length === 0) {
      // No threads exist, create first one
      const thread = new AiThread({
        id: crypto.randomUUID(),
        name: "New Thread",  // TODO: Generate meaningful name
        summary: "",
        createdDate: new Date().toISOString(),
        modifiedDate: new Date().toISOString()
      })
      await this.repository.save(thread)
      this.activeThread$.next(thread)
      return thread
    }
    
    // Select most recent thread
    const mostRecent = threads.reduce((latest, current) => {
      if (!latest || current.modifiedDate > latest.modifiedDate) {
        return current
      }
      return latest
    })
    
    const thread = await this.repository.getById(mostRecent.id)
    if (!thread) {
      throw new Error("Failed to load most recent thread")
    }
    
    this.activeThread$.next(thread)
    return thread
  }
  
  /**
   * Save current thread state and trigger post-processing
   * like summarization and memory extraction.
   */
  async save(): Promise<void> {
    const thread = this.activeThread$.value
    if (!thread) {
      return
    }
    
    // Save current state
    await this.repository.save(thread)
    
    // TODO: Post-processing
    // - Summarization
    // - Memory extraction
    // - Knowledge sharing
  }
  
  /**
   * Delete a thread permanently
   */
  async delete(threadId: string): Promise<void> {
    const success = await this.repository.delete(threadId)
    if (!success) {
      throw new Error(`Failed to delete thread ${threadId}`)
    }
    
    // If active thread was deleted, clear it
    if (this.activeThread$.value?.id === threadId) {
      this.activeThread$.next(null)
    }
  }
  
  /**
   * Get currently active thread as an observable
   */
  getActive(): Observable<AiThread | null> {
    return this.activeThread$.asObservable()
  }
  
  /**
   * List all available threads
   */
  list(): Observable<ThreadSummary[]> {
    // Convert Promise to Observable for consistency
    return new Observable<ThreadSummary[]>(subscriber => {
      this.repository.listThreads()
        .then(threads => {
          subscriber.next(threads)
          subscriber.complete()
        })
        .catch(error => subscriber.error(error))
    })
  }
}
/**
 * Service managing AI thread lifecycle and operations.
 * Provides a simple interface for thread selection and management
 * while handling complex internal state and transitions.
 */

import {BehaviorSubject, firstValueFrom, Observable} from "rxjs"
import {AiThread} from "./ai-thread"
import {AiThreadRepository} from "./ai-thread.repository"
import {AiThreadRepositoryFactory} from "./repository/ai-thread.repository.factory"
import {filter} from "rxjs/operators"

export interface ThreadSummary {
  id: string
  name: string
  summary: string
  createdDate: string
  modifiedDate: string
}

export class AiThreadService {
  private readonly activeThread$ = new BehaviorSubject<AiThread | null>(null)
  
  constructor(private readonly repositoryFactory: AiThreadRepositoryFactory) {
    // Reset active thread when repository changes
    this.repositoryFactory.repository.subscribe(() => {
      console.log("resetting active thread")
      this.activeThread$.next(null)
      setTimeout(() => this.select(), 0) // auto select last thread after a project change
    })
  }
  
  /**
   * Get current repository instance asynchronously.
   * Waits for a valid repository to be available.
   * Used internally to ensure we always work with the latest valid repository.
   */
  private async getRepository(): Promise<AiThreadRepository> {
    // Use firstValueFrom to get the first valid repository
    return await firstValueFrom(
      this.repositoryFactory.repository.pipe(
        filter((repo): repo is AiThreadRepository => repo !== null)
      )
    )
  }
  
  /**
   * Select a thread for use, or create a default one if none exists.
   * This is the main entry point for thread management.
   *
   * @param threadId Optional ID of thread to select
   * @returns Selected or created thread
   */
  async select(threadId?: string): Promise<AiThread> {
    const repository = await this.getRepository()
    
    if (threadId) {
      const thread = await repository.getById(threadId)
      if (!thread) {
        throw new Error(`Thread ${threadId} not found`)
      }
      this.activeThread$.next(thread)
      return thread
    }
    
    // No ID provided, get last used or create new
    const threads = await repository.listThreads()
    if (threads.length === 0) {
      // No threads exist, create first one
      const thread = new AiThread({
        id: crypto.randomUUID(),
        name: "New Thread",  // TODO: Generate meaningful name
        summary: "",
        createdDate: new Date().toISOString(),
        modifiedDate: new Date().toISOString()
      })
      await repository.save(thread)
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
    
    const thread = await repository.getById(mostRecent.id)
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
    
    const repository = await this.getRepository()
    // Save current state
    await repository.save(thread)
    
    // TODO: Post-processing
    // - Summarization
    // - Memory extraction
    // - Knowledge sharing
  }
  
  /**
   * Delete a thread permanently
   */
  async delete(threadId: string): Promise<void> {
    const repository = await this.getRepository()
    const success = await repository.delete(threadId)
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
   * List all available threads.
   * Returns an Observable that will emit once with the list of threads
   * or error if repository is not available.
   */
  list(): Observable<ThreadSummary[]> {
    // Convert Promise to Observable for consistency
    return new Observable<ThreadSummary[]>(subscriber => {
      this.getRepository()
        .then(repository => repository.listThreads())
        .then(threads => {
          subscriber.next(threads)
          subscriber.complete()
        })
        .catch(error => subscriber.error(error))
    })
  }
}
/**
 * Service managing AI thread lifecycle and operations.
 * Provides a simple interface for thread selection and management
 * while handling complex internal state and transitions.
 */

import { BehaviorSubject, Observable } from 'rxjs'
import { AiThread } from './ai-thread'

import { UserService } from '@coday/service'
import { Killable } from '@coday/model'
import { Interactor } from '@coday/model'
import { ThreadRepository } from '@coday/repository'
import { ThreadUpdateEvent } from '@coday/model'

export class ThreadStateService implements Killable {
  private readonly activeThread$ = new BehaviorSubject<AiThread | null>(null)
  private isKilled = false

  /**
   * Observable of the currently active thread.
   * Emits whenever the active thread changes.
   */
  readonly activeThread: Observable<AiThread | null> = this.activeThread$.asObservable()

  readonly username: string

  constructor(
    userService: UserService,
    private readonly threadRepository: ThreadRepository,
    private readonly projectId: string,
    private readonly interactor?: Interactor
  ) {
    this.username = userService.username
  }

  async kill(): Promise<void> {
    this.isKilled = true
    this.activeThread$.complete()
  }

  async create(name?: string): Promise<AiThread> {
    const newThread = new AiThread({
      id: '', // TODO falsy, will be overriden by repository, shitty pattern FTW...
      username: this.username,
      name: name ?? '',
      price: 0,
    })

    this.activeThread$.next(newThread)
    return newThread
  }

  /**
   * Select a thread for use, or create a default one if none exists.
   * This is the main entry point for thread management.
   *
   * @param threadId ID of thread to select
   * @returns Selected or created thread
   */
  async select(threadId: string): Promise<AiThread> {
    const thread = await this.threadRepository.getById(this.projectId, threadId)
    if (!thread) {
      throw new Error(`Thread ${threadId} not found`)
    }
    this.activeThread$.next(thread)

    return thread
  }

  /**
   * Save current thread state and trigger post-processing
   * like summarization and memory extraction.
   *
   * @param newName Optional new name for the thread
   */
  async save(newName?: string): Promise<void> {
    const thread = this.activeThread$.value
    if (!thread) {
      console.error(`No thread existing when save attempt with name '${newName}'`)
      return
    }

    // If renaming, update the name
    if (newName) {
      thread.name = newName
    }
    const saved = await this.threadRepository.save(this.projectId, thread)

    // TODO: Post-processing
    // - Summarization
    // - Memory extraction
    // - Knowledge sharing
    this.activeThread$.next(saved)
  }

  async autoSave(newName?: string): Promise<void> {
    // Check if service has been killed
    if (this.isKilled) {
      console.log('Autosave skipped: service has been killed')
      return
    }

    const thread = this.activeThread$.value
    if (!thread || thread.messagesLength == 0) {
      // skip saving a thread that has no messages
      console.log(`Autosave of an empty or falsy thread aborted, threadId: ${thread?.id}, user: ${this.username}`)
      return
    }

    try {
      if (newName) {
        thread.name = newName
      }
      await this.threadRepository.save(this.projectId, thread)

      // Emit thread update event whenever we save
      // Always include the current name to ensure frontend list stays in sync
      if (this.interactor) {
        this.interactor.sendEvent(
          new ThreadUpdateEvent({
            threadId: thread.id,
            name: thread.name || undefined, // Include current name if it exists
          })
        )
      }
    } catch (error) {
      // Gracefully handle errors during autosave (e.g., service killed during operation)
      console.log('Autosave failed (service may have been killed):', error instanceof Error ? error.message : error)
    }
  }

  /**
   * Truncate the current thread at a specific user message
   * @param eventId The timestamp ID of the user message to delete
   * @returns Promise<boolean> true if truncation was successful
   */
  async truncateAtUserMessage(eventId: string): Promise<boolean> {
    const thread = this.activeThread$.value
    if (!thread) {
      console.error('No active thread available for truncation')
      return false
    }

    const success = thread.truncateAtUserMessage(eventId)
    if (!success) {
      this.interactor?.warn(`Failed to truncate thread.`)
    }

    return success
  }

  /**
   * Get currently active thread synchronously.
   * @returns The current thread or null if none is active
   */
  getCurrentThread(): AiThread | null {
    return this.activeThread$.value
  }
}

/**
 * @fileoverview Thread repository interface definition
 */

import {AiThread} from './ai-thread'
import {ThreadSummary} from './ai-thread.types'

/**
 * Core interface for thread persistence operations
 */
export interface AiThreadRepository {
  /**
   * Retrieve a thread by its unique identifier
   * @param id Unique thread identifier
   * @returns The thread if found, null otherwise
   * @throws ThreadRepositoryError for system-level errors
   */
  getById(id: string): Promise<AiThread | null>

  /**
   * Save a thread (create or update)
   * @param thread Thread to persist
   * @returns The saved thread
   * @throws ThreadRepositoryError for persistence failures
   */
  save(thread: AiThread): Promise<AiThread>

  /**
   * List available threads with their basic information
   * @returns Array of thread information without full message history
   * @throws ThreadRepositoryError for system-level errors
   */
  listThreadsByUsername(username: string): Promise<ThreadSummary[]>

  /**
   * Delete a thread by its identifier
   * @param id Thread identifier to delete
   * @returns true if deleted, false if not found
   * @throws ThreadRepositoryError for system-level errors
   */
  delete(id: string): Promise<boolean>
}

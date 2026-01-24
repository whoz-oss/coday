import { AiThread } from '@coday/ai-thread/ai-thread'
import { ThreadSummary } from '@coday/ai-thread/ai-thread.types'

/**
 * Repository interface for thread persistence operations.
 * Implementations can be file-based, database-based, or remote API-based.
 *
 * All operations require a projectId to identify which project the thread belongs to.
 */
export interface ThreadRepository {
  /**
   * Retrieve a thread by its unique identifier within a project
   * @param projectId Project identifier
   * @param threadId Unique thread identifier
   * @returns The thread if found, null otherwise
   */
  getById(projectId: string, threadId: string): Promise<AiThread | null>

  /**
   * Save a thread (create or update) within a project
   * @param projectId Project identifier
   * @param thread Thread to persist
   * @returns The saved thread
   */
  save(projectId: string, thread: AiThread): Promise<AiThread>

  /**
   * List threads for a specific project, optionally filtered by user
   * @param projectId Project identifier
   * @param username Optional user identifier to filter threads
   * @returns Array of thread summaries without full message history
   */
  listByProject(projectId: string, username?: string): Promise<ThreadSummary[]>

  /**
   * Delete a thread by its identifier within a project
   * @param projectId Project identifier
   * @param threadId Thread identifier to delete
   * @returns true if deleted, false if not found
   */
  delete(projectId: string, threadId: string): Promise<boolean>
}

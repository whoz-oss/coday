import { ThreadRepository } from '@coday/repository/thread.repository'
import { ThreadFileRepository } from '@coday/repository/thread-file.repository'
import { ProjectRepository } from '@coday/repository/project.repository'
import { AiThread } from '@coday/ai-thread/ai-thread'
import { ThreadSummary } from '@coday/ai-thread/ai-thread.types'
import { ThreadFileService } from './thread-file.service'

/**
 * Cache entry for thread list with timestamp
 */
interface ThreadListCacheEntry {
  data: ThreadSummary[]
  timestamp: number
}

/**
 * Server-side thread management service.
 * Stateless service for managing threads independently of user sessions.
 *
 * This service provides a clean API for thread operations without coupling
 * to session state or observables. It creates thread repositories on-demand
 * based on the project context.
 *
 * Performance: Thread list cached in memory (4h TTL) to avoid repeated file I/O.
 * Cache stores only ThreadSummary metadata, invalidated on all modifications.
 * Issue #382
 */
export class ThreadService {
  /**
   * Cache of thread repositories by project name.
   * Avoids recreating repository instances for each operation.
   */
  private readonly repositoryCache = new Map<string, ThreadRepository>()

  /**
   * Thread list cache: key="projectName:username", TTL=4h
   * Stores only ThreadSummary (no messages), invalidated on modifications
   */
  private readonly threadListCache = new Map<string, ThreadListCacheEntry>()
  private readonly CACHE_TTL_MS = 4 * 60 * 60 * 1000 // 4 hours

  constructor(
    private readonly projectRepository: ProjectRepository,
    private readonly projectsDir: string,
    private readonly threadFileService: ThreadFileService
  ) {}

  /**
   * Get or create a cached thread repository for a specific project
   * @param projectName Project name
   * @returns Cached thread repository instance
   * @throws Error if project doesn't exist
   */
  getThreadRepository(projectName: string): ThreadRepository {
    // Check cache first
    const cached = this.repositoryCache.get(projectName)
    if (cached) {
      return cached
    }

    // Validate project exists
    const projectInfo = this.projectRepository.getProjectInfo(projectName)
    if (!projectInfo) {
      throw new Error(`Project '${projectName}' not found`)
    }

    // Create and cache new repository
    const repository = new ThreadFileRepository(this.projectsDir)
    this.repositoryCache.set(projectName, repository)
    return repository
  }

  /**
   * Clear the repository cache for a specific project or all projects
   * @param projectName Optional project name to clear specific cache entry
   */
  clearCache(projectName?: string): void {
    if (projectName) {
      this.repositoryCache.delete(projectName)
    } else {
      this.repositoryCache.clear()
    }
  }

  /**
   * List all threads for a project and user (cached)
   * @param projectName Project name
   * @param username User identifier
   * @returns Array of thread summaries
   */
  async listThreads(projectName: string, username: string): Promise<ThreadSummary[]> {
    const cacheKey = `${projectName}:${username}`
    const cached = this.threadListCache.get(cacheKey)

    // Return cached data if still valid
    if (cached && Date.now() - cached.timestamp < this.CACHE_TTL_MS) {
      return cached.data
    }

    // Fetch from repository
    const repository = this.getThreadRepository(projectName)
    const threads = await repository.listByProject(projectName, username)

    // Cache the result (only metadata, not full thread content)
    this.threadListCache.set(cacheKey, {
      data: threads,
      timestamp: Date.now(),
    })

    return threads
  }

  /**
   * Invalidate thread list cache for a project
   * @param projectName Project to invalidate
   * @param username Optional: invalidate only for specific user
   */
  private invalidateThreadListCache(projectName: string, username?: string): void {
    if (username) {
      // Invalidate only for specific user
      this.threadListCache.delete(`${projectName}:${username}`)
    } else {
      // Invalidate for all users of this project
      for (const key of this.threadListCache.keys()) {
        if (key.startsWith(`${projectName}:`)) {
          this.threadListCache.delete(key)
        }
      }
    }
  }

  /**
   * Get a specific thread by ID
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns Thread if found, null otherwise
   */
  async getThread(projectName: string, threadId: string): Promise<AiThread | null> {
    const repository = this.getThreadRepository(projectName)
    return await repository.getById(projectName, threadId)
  }

  /**
   * Create a new thread
   * @param projectName Project name
   * @param username User identifier
   * @param name Optional thread name
   * @returns Created thread
   */
  async createThread(projectName: string, username: string, name?: string): Promise<AiThread> {
    const repository = this.getThreadRepository(projectName)

    const thread = new AiThread({
      id: crypto.randomUUID(),
      username,
      projectId: projectName,
      name: name || '',
      price: 0,
    })

    const savedThread = await repository.save(projectName, thread)

    // Invalidate cache for this user only
    this.invalidateThreadListCache(projectName, username)

    return savedThread
  }

  /**
   * Update a thread (rename)
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param updates Partial thread updates
   * @returns Updated thread
   * @throws Error if thread doesn't exist
   */
  async updateThread(projectName: string, threadId: string, updates: { name?: string }): Promise<AiThread> {
    const repository = this.getThreadRepository(projectName)

    const thread = await repository.getById(projectName, threadId)
    if (!thread) {
      throw new Error(`Thread '${threadId}' not found in project '${projectName}'`)
    }

    // Apply updates
    if (updates.name !== undefined) {
      thread.name = updates.name
    }

    const updatedThread = await repository.save(projectName, thread)

    // Invalidate cache for all users (name change visible to all)
    this.invalidateThreadListCache(projectName)

    return updatedThread
  }

  /**
   * Star a thread (add username to starring list)
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param username Username to add to starring list
   * @returns Updated thread
   * @throws Error if thread doesn't exist
   */
  async starThread(projectName: string, threadId: string, username: string): Promise<AiThread> {
    const repository = this.getThreadRepository(projectName)

    const thread = await repository.getById(projectName, threadId)
    if (!thread) {
      throw new Error(`Thread '${threadId}' not found in project '${projectName}'`)
    }

    // Add username to starring list if not already present
    if (!thread.starring.includes(username)) {
      thread.starring.push(username)
    }

    const updatedThread = await repository.save(projectName, thread)

    // Invalidate cache for all users (starring visible to all)
    this.invalidateThreadListCache(projectName)

    return updatedThread
  }

  /**
   * Unstar a thread (remove username from starring list)
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param username Username to remove from starring list
   * @returns Updated thread
   * @throws Error if thread doesn't exist
   */
  async unstarThread(projectName: string, threadId: string, username: string): Promise<AiThread> {
    const repository = this.getThreadRepository(projectName)

    const thread = await repository.getById(projectName, threadId)
    if (!thread) {
      throw new Error(`Thread '${threadId}' not found in project '${projectName}'`)
    }

    // Remove username from starring list
    thread.starring = thread.starring.filter((u) => u !== username)

    const updatedThread = await repository.save(projectName, thread)

    // Invalidate cache for all users (starring visible to all)
    this.invalidateThreadListCache(projectName)

    return updatedThread
  }

  /**
   * Delete a thread and its associated files directory
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns true if deleted, false if not found
   */
  async deleteThread(projectName: string, threadId: string): Promise<boolean> {
    const repository = this.getThreadRepository(projectName)
    const deleted = await repository.delete(projectName, threadId)

    if (deleted) {
      // Also delete the thread files directory if it exists
      await this.threadFileService.deleteThreadFiles(projectName, threadId)

      // Invalidate cache for all users
      this.invalidateThreadListCache(projectName)
    }

    return deleted
  }

  /**
   * Check if a thread exists
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns true if thread exists
   */
  async exists(projectName: string, threadId: string): Promise<boolean> {
    const thread = await this.getThread(projectName, threadId)
    return thread !== null
  }
}

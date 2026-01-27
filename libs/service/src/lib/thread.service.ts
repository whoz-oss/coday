import { AiThread, ThreadSummary } from '@coday/model'
import { ProjectRepository, ThreadFileRepository, ThreadRepository } from '@coday/repository'
import { ThreadFileService } from './thread-file.service'

/**
 * Cache entry for thread list with timestamp
 */
interface ThreadListCacheEntry {
  data: ThreadSummary[] // All threads for the project (not filtered by user)
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
 * Performance: Thread list cached per project (4h TTL) with incremental updates.
 * Cache stores all threads for a project, filtering by user done in-memory.
 * Updates modify cache entries instead of invalidating entire cache.
 * Issue #382
 */
export class ThreadService {
  /**
   * Cache of thread repositories by project name.
   * Avoids recreating repository instances for each operation.
   */
  private readonly repositoryCache = new Map<string, ThreadRepository>()

  /**
   * Thread list cache: key="projectName", value=all threads for project
   * Shared across all users, filtered in-memory. Updated incrementally on modifications.
   */
  private readonly threadListCache = new Map<string, ThreadListCacheEntry>()
  private readonly CACHE_TTL_MS = 4 * 60 * 60 * 1000 // 4 hours

  /**
   * Loading promises to prevent concurrent cache misses (race condition fix)
   * When multiple requests arrive simultaneously for expired cache, only first one loads from disk
   */
  private readonly loadingPromises = new Map<string, Promise<ThreadSummary[]>>()

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
   * Cache stores all threads for project, filtering by user done in-memory.
   * Race condition safe: concurrent requests will await the same loading promise.
   * @param projectName Project name
   * @param username User identifier
   * @returns Array of thread summaries for the user
   */
  async listThreads(projectName: string, username: string): Promise<ThreadSummary[]> {
    const cached = this.threadListCache.get(projectName)

    // Return cached data if still valid, filtered by user
    if (cached && Date.now() - cached.timestamp < this.CACHE_TTL_MS) {
      return cached.data
        .filter((t) => t.username === username)
        .sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
    }

    // Check if loading is already in progress (race condition prevention)
    const existingPromise = this.loadingPromises.get(projectName)
    if (existingPromise) {
      // Wait for the ongoing load to complete
      await existingPromise
      // Recursively call to return filtered cached data
      return this.listThreads(projectName, username)
    }

    // Start loading - create promise and store it
    const loadingPromise = this.loadThreadListFromDisk(projectName)
    this.loadingPromises.set(projectName, loadingPromise)

    try {
      // Wait for loading to complete
      const allThreads = await loadingPromise
      // Return filtered and sorted for this user
      return allThreads
        .filter((t) => t.username === username)
        .sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
    } catch (error) {
      // On error, invalidate cache to allow retry on next request
      this.threadListCache.delete(projectName)
      // Propagate error to caller (route handler will handle HTTP response)
      throw error
    } finally {
      // Always clean up loading promise (success or error)
      this.loadingPromises.delete(projectName)
    }
  }

  /**
   * Load thread list from disk and update cache
   * @param projectName Project name
   * @returns All threads for the project
   */
  private async loadThreadListFromDisk(projectName: string): Promise<ThreadSummary[]> {
    const repository = this.getThreadRepository(projectName)
    const allThreads = await repository.listByProject(projectName)

    // Cache all threads for the project (only metadata, not full thread content)
    this.threadListCache.set(projectName, {
      data: allThreads,
      timestamp: Date.now(),
    })

    return allThreads
  }

  /**
   * Update a thread entry in the cache
   * @param projectName Project name
   * @param threadSummary Updated thread summary
   */
  private updateThreadInCache(projectName: string, threadSummary: ThreadSummary): void {
    const cached = this.threadListCache.get(projectName)
    if (!cached) return

    const index = cached.data.findIndex((t) => t.id === threadSummary.id)
    if (index !== -1) {
      // Update existing entry
      cached.data[index] = threadSummary
    } else {
      // Add new entry
      cached.data.push(threadSummary)
    }
  }

  /**
   * Remove a thread entry from the cache
   * @param projectName Project name
   * @param threadId Thread ID to remove
   */
  private removeThreadFromCache(projectName: string, threadId: string): void {
    const cached = this.threadListCache.get(projectName)
    if (!cached) return

    cached.data = cached.data.filter((t) => t.id !== threadId)
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

    // Add to cache
    this.updateThreadInCache(projectName, {
      id: savedThread.id,
      username: savedThread.username,
      projectId: savedThread.projectId,
      name: savedThread.name,
      summary: savedThread.summary,
      createdDate: savedThread.createdDate,
      modifiedDate: savedThread.modifiedDate,
      price: savedThread.price,
      starring: savedThread.starring,
    })

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

    // Update in cache
    this.updateThreadInCache(projectName, {
      id: updatedThread.id,
      username: updatedThread.username,
      projectId: updatedThread.projectId,
      name: updatedThread.name,
      summary: updatedThread.summary,
      createdDate: updatedThread.createdDate,
      modifiedDate: updatedThread.modifiedDate,
      price: updatedThread.price,
      starring: updatedThread.starring,
    })

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

    // Update in cache
    this.updateThreadInCache(projectName, {
      id: updatedThread.id,
      username: updatedThread.username,
      projectId: updatedThread.projectId,
      name: updatedThread.name,
      summary: updatedThread.summary,
      createdDate: updatedThread.createdDate,
      modifiedDate: updatedThread.modifiedDate,
      price: updatedThread.price,
      starring: updatedThread.starring,
    })

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

    // Update in cache
    this.updateThreadInCache(projectName, {
      id: updatedThread.id,
      username: updatedThread.username,
      projectId: updatedThread.projectId,
      name: updatedThread.name,
      summary: updatedThread.summary,
      createdDate: updatedThread.createdDate,
      modifiedDate: updatedThread.modifiedDate,
      price: updatedThread.price,
      starring: updatedThread.starring,
    })

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

      // Remove from cache
      this.removeThreadFromCache(projectName, threadId)
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

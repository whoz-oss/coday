import { ThreadRepository } from '@coday/repository/thread.repository'
import { ThreadFileRepository } from '@coday/repository/thread-file.repository'
import { ProjectRepository } from '@coday/repository/project.repository'
import { AiThread } from '@coday/ai-thread/ai-thread'
import { ThreadSummary } from '@coday/ai-thread/ai-thread.types'

/**
 * Server-side thread management service.
 * Stateless service for managing threads independently of user sessions.
 *
 * This service provides a clean API for thread operations without coupling
 * to session state or observables. It creates thread repositories on-demand
 * based on the project context.
 */
export class ThreadService {
  /**
   * Cache of thread repositories by project name.
   * Avoids recreating repository instances for each operation.
   */
  private readonly repositoryCache = new Map<string, ThreadRepository>()

  constructor(
    private readonly projectRepository: ProjectRepository,
    private readonly projectsDir: string
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
   * List all threads for a project and user
   * @param projectName Project name
   * @param username User identifier
   * @returns Array of thread summaries
   */
  async listThreads(projectName: string, username: string): Promise<ThreadSummary[]> {
    const repository = this.getThreadRepository(projectName)
    return await repository.listByProject(projectName, username)
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

    return await repository.save(projectName, thread)
  }

  /**
   * Update a thread (typically to rename it)
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

    return await repository.save(projectName, thread)
  }

  /**
   * Delete a thread
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns true if deleted, false if not found
   */
  async deleteThread(projectName: string, threadId: string): Promise<boolean> {
    const repository = this.getThreadRepository(projectName)
    return await repository.delete(projectName, threadId)
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

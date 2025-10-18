import { promises as fs } from 'fs'
import path from 'path'
import yaml from 'yaml'
import { ThreadRepository } from './thread.repository'
import { AiThread } from '../ai-thread/ai-thread'
import { ThreadSummary, ThreadRepositoryError } from '../ai-thread/ai-thread.types'
import { aiThreadMigrations } from '../ai-thread/ai-thread.migrations'
import { migrateData } from '../utils/data-migration'
import { writeYamlFile } from '../service/write-yaml-file'

/**
 * Helper function to safely read YAML file content
 * @param filePath Path to YAML file
 * @returns Parsed YAML content or null if file can't be read/parsed
 */
const readYamlFile = async (filePath: string): Promise<any | null> => {
  try {
    const content = await fs.readFile(filePath, 'utf-8')
    return yaml.parse(content)
  } catch {
    return null
  }
}

/**
 * File-based implementation of ThreadRepository.
 * Stores threads as individual YAML files in project-specific directories.
 *
 * Directory structure: {projectsDir}/{projectId}/threads/{thread-files}.yml
 *
 * This implementation automatically infers projectId from the directory structure
 * when reading existing threads, ensuring backward compatibility with threads
 * that don't have projectId stored in their YAML files.
 */
export class ThreadFileRepository implements ThreadRepository {
  /**
   * Creates a new ThreadFileRepository
   * @param projectsDir Base directory containing all projects (typically ~/.coday/projects)
   */
  constructor(private readonly projectsDir: string) {}

  /**
   * Get the threads directory for a specific project
   * @param projectId Project identifier
   * @returns Full path to the threads directory
   */
  private getThreadsDir(projectId: string): string {
    return path.join(this.projectsDir, projectId, 'threads')
  }

  /**
   * Ensure the threads directory exists for a project
   * @param projectId Project identifier
   */
  private async ensureThreadsDir(projectId: string): Promise<void> {
    const threadsDir = this.getThreadsDir(projectId)
    try {
      await fs.mkdir(threadsDir, { recursive: true })
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to initialize threads directory for project ${projectId}`, error as Error)
    }
  }

  /**
   * Sanitize a thread name for use as a file name
   * @param name Thread name
   * @returns Sanitized file name
   */
  private sanitizeFileName(name?: string): string {
    return (
      (name || 'untitled')
        .toLowerCase()
        // Replace spaces and special chars with hyphens
        .replace(/[^a-z0-9]+/g, '-')
        // Remove leading/trailing hyphens
        .replace(/^-+|-+$/g, '')
    )
  }

  /**
   * Generate a filename for a thread, combining sanitized name and id
   * @param thread Thread to generate filename for
   * @returns The filename with .yml extension
   */
  private getThreadFileName(thread: AiThread): string {
    const sanitizedName = this.sanitizeFileName(thread.name || 'untitled')
    return `${sanitizedName}-${thread.id}.yml`
  }

  /**
   * Find a thread file by its ID using the filename pattern {name}-{id}.yml
   * @param projectId Project identifier
   * @param threadId ID of the thread to find
   * @returns Filename if found, null otherwise
   */
  private async findThreadFile(projectId: string, threadId: string): Promise<string | null> {
    try {
      const threadsDir = this.getThreadsDir(projectId)
      const files = await fs.readdir(threadsDir)
      const threadFile = files.find((file) => file.endsWith(`-${threadId}.yml`))
      return threadFile || null
    } catch (error) {
      throw new ThreadRepositoryError(`Error finding thread ${threadId} in project ${projectId}`, error as Error)
    }
  }

  async getById(projectId: string, threadId: string): Promise<AiThread | null> {
    try {
      const file = await this.findThreadFile(projectId, threadId)
      if (!file) {
        return null
      }

      const threadsDir = this.getThreadsDir(projectId)
      const filePath = path.join(threadsDir, file)
      const data = await readYamlFile(filePath)
      if (!data) {
        return null
      }

      // Apply migrations
      const migratedThread = migrateData(data, aiThreadMigrations)

      // Infer projectId from directory structure if not present in file
      if (!migratedThread.projectId) {
        migratedThread.projectId = projectId
      }

      // Save migrated thread if needed
      if (migratedThread !== data) {
        writeYamlFile(filePath, migratedThread)
      }

      return new AiThread(migratedThread)
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to read thread ${threadId} from project ${projectId}`, error as Error)
    }
  }

  async save(projectId: string, thread: AiThread): Promise<AiThread> {
    await this.ensureThreadsDir(projectId)

    try {
      // Ensure thread has an ID
      if (!thread.id) {
        thread.id = crypto.randomUUID()
      }

      // Ensure thread has projectId set
      if (!thread.projectId) {
        thread.projectId = projectId
      }

      // Validate projectId matches
      if (thread.projectId !== projectId) {
        throw new Error(`Thread projectId mismatch: expected ${projectId}, got ${thread.projectId}`)
      }

      const threadsDir = this.getThreadsDir(projectId)
      const newFileName = this.getThreadFileName(thread)
      const newThreadPath = path.join(threadsDir, newFileName)

      // Check if an old file exists with the same ID but different name (rename case)
      const existingFile = await this.findThreadFile(projectId, thread.id)
      if (existingFile && existingFile !== newFileName) {
        // Delete the old file before writing the new one
        const oldFilePath = path.join(threadsDir, existingFile)
        try {
          await fs.unlink(oldFilePath)
          console.log(`[THREAD-REPO] Deleted old thread file: ${existingFile}`)
        } catch (error) {
          // Ignore if old file doesn't exist or can't be deleted
          console.warn(`[THREAD-REPO] Could not delete old thread file: ${existingFile}`, error)
        }
      }

      const versionned = { ...thread, version: aiThreadMigrations.length + 1 }
      const contentToSave = yaml.stringify(versionned)

      // Write the new file
      await fs.writeFile(newThreadPath, contentToSave, 'utf-8')

      return thread
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to save thread ${thread.id} to project ${projectId}`, error as Error)
    }
  }

  async listByProject(projectId: string, username: string): Promise<ThreadSummary[]> {
    try {
      const threadsDir = this.getThreadsDir(projectId)

      // Check if directory exists
      try {
        await fs.access(threadsDir)
      } catch {
        // Directory doesn't exist yet, return empty list
        return []
      }

      const files = await fs.readdir(threadsDir)
      const threads = (
        await Promise.all(
          files
            .filter((file) => file.endsWith('.yml'))
            .map(async (file) => {
              const data = await readYamlFile(path.join(threadsDir, file))
              if (!data) return null

              // Infer projectId if not present
              const threadProjectId = data.projectId || projectId

              return {
                id: data.id,
                username: data.username,
                projectId: threadProjectId,
                name: data.name || 'untitled',
                summary: data.summary || '',
                createdDate: data.createdDate || '',
                modifiedDate: data.modifiedDate || '',
                price: data.price || 0,
              } as ThreadSummary
            })
        )
      )
        .filter((t): t is ThreadSummary => !!t)
        .filter((t) => t.username === username)
        // Sort by decreasing last modified date
        .sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))

      return threads
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to list threads for project ${projectId}`, error as Error)
    }
  }

  async delete(projectId: string, threadId: string): Promise<boolean> {
    try {
      const file = await this.findThreadFile(projectId, threadId)
      if (!file) return false

      const threadsDir = this.getThreadsDir(projectId)
      await fs.unlink(path.join(threadsDir, file))
      return true
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to delete thread ${threadId} from project ${projectId}`, error as Error)
    }
  }
}

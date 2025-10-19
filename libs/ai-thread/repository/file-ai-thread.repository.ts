/**
 * @fileoverview File-based implementation of ThreadRepository using YAML files
 */

import { promises as fs } from 'fs'
import path from 'path'
import yaml from 'yaml'
import { AiThread } from '../ai-thread'
import { AiThreadRepository } from '../ai-thread.repository'
import { ThreadRepositoryError, ThreadSummary } from '../ai-thread.types'
import { aiThreadMigrations } from '../ai-thread.migrations'
import { writeYamlFile } from '@coday/service/write-yaml-file'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { migrateData } from '../../utils/data-migration'

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
 * File-based implementation of ThreadRepository
 * Stores threads as individual YAML files in the project's .coday/threads directory
 */
export class FileAiThreadRepository implements AiThreadRepository {
  private initPromise: Promise<void>

  /**
   * Creates a new FileAiThreadRepository and starts initialization
   * @param threadsDir Directory path where thread files will be stored
   */
  constructor(private readonly threadsDir: string) {
    // Start initialization but don't block
    this.initPromise = this.doInitialize()
  }

  /**
   * Internal initialization of the repository
   */
  private async doInitialize(): Promise<void> {
    try {
      await fs.mkdir(this.threadsDir, { recursive: true })
    } catch (error) {
      throw new ThreadRepositoryError('Failed to initialize threads directory', error as Error)
    }
  }



  async getById(id: string): Promise<AiThread | null> {
    await this.initPromise
    try {
      const file = await this.findThreadFile(id)
      if (!file) {
        return null
      }

      const filePath = path.join(this.threadsDir, file)
      const data = await readYamlFile(filePath)
      if (!data) {
        return null
      }
      // do migrations on thread
      const migratedThread = migrateData(data, aiThreadMigrations)

      if (migratedThread !== data) {
        writeYamlFile(filePath, migratedThread)
      }

      return new AiThread(migratedThread)
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to read thread ${id}`, error as Error)
    }
  }

  /**
   * Find a thread file by its ID using the filename pattern {id}.yml
   * Also checks legacy pattern {name}-{id}.yml for backward compatibility
   * @param threadId ID of the thread to find
   * @returns Filename if found, null otherwise
   */
  private async findThreadFile(threadId: string): Promise<string | null> {
    try {
      const newFormat = `${threadId}.yml`
      
      // Check if new format exists
      try {
        await fs.access(path.join(this.threadsDir, newFormat))
        return newFormat
      } catch {
        // New format doesn't exist, check legacy format
      }
      
      // Check legacy format {name}-{id}.yml for backward compatibility
      const files = await fs.readdir(this.threadsDir)
      const threadFile = files.find((file) => file.endsWith(`-${threadId}.yml`))
      return threadFile || null
    } catch (error) {
      throw new ThreadRepositoryError(`Error finding thread ${threadId}`, error as Error)
    }
  }

  /**
   * Generate a filename for a thread using only the thread ID
   * @param thread Thread to generate filename for
   * @returns The filename with .yml extension
   */
  private getThreadFileName(thread: AiThread): string {
    return `${thread.id}.yml`
  }

  /**
   * Save a thread to a file.
   * Uses only thread ID for filename.
   * Migrates from legacy {name}-{id}.yml format if needed.
   * @param thread Thread to save
   * @returns The saved thread
   */
  async save(thread: AiThread): Promise<AiThread> {
    await this.initPromise
    try {
      if (!thread.id) {
        thread.id = crypto.randomUUID()
      }
      
      const newFileName = this.getThreadFileName(thread)
      const newThreadPath = path.join(this.threadsDir, newFileName)

      // Check if an old file exists (could be legacy format with name in filename)
      const existingFile = await this.findThreadFile(thread.id)
      if (existingFile && existingFile !== newFileName) {
        // Delete the old file before writing the new one (handles both rename and legacy format migration)
        const oldFilePath = path.join(this.threadsDir, existingFile)
        try {
          await fs.unlink(oldFilePath)
          console.log(`[THREAD-REPO] Migrated/renamed thread file: ${existingFile} → ${newFileName}`)
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
      throw new ThreadRepositoryError(`Failed to save thread ${thread.id}`, error as Error)
    }
  }

  // TODO: kill this monstruosity someday, reading all threads is not sustainable
  async listThreadsByUsername(username: string): Promise<ThreadSummary[]> {
    await this.initPromise
    try {
      const files = await fs.readdir(this.threadsDir)
      return (
        (
          await Promise.all(
            files
              .filter((file) => file.endsWith('.yml'))
              .map(async (file) => {
                const data = await readYamlFile(path.join(this.threadsDir, file))
                if (!data) return null

                return {
                  id: data.id,
                  username: data.username,
                  projectId: data.projectId || '',
                  name: data.name || 'untitled',
                  summary: data.summary || '',
                  createdDate: data.createdDate || '',
                  modifiedDate: data.modifiedDate || '',
                  price: data.price || 0,
                }
              })
          )
        )
          .filter((t) => !!t)
          .filter((t) => t.username === username)
          // sort by decreasing last modified date
          .sort((a: ThreadSummary, b: ThreadSummary) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
      )
    } catch (error) {
      throw new ThreadRepositoryError('Failed to list threads', error as Error)
    }
  }

  async delete(id: string): Promise<boolean> {
    await this.initPromise
    try {
      const file = await this.findThreadFile(id)
      if (!file) return false

      await fs.unlink(path.join(this.threadsDir, file))
      return true
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to delete thread ${id}`, error as Error)
    }
  }
}

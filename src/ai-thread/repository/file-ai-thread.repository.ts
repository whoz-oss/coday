/**
 * @fileoverview File-based implementation of ThreadRepository using YAML files
 */

import fs from "fs/promises"
import path from "path"
import * as yaml from "yaml"
import {AiThread} from "../ai-thread"
import {AiThreadRepository} from "../ai-thread.repository"
import {ThreadRepositoryError, ThreadSummary} from "../ai-thread.types"

/**
 * Helper function to safely read YAML file content
 * @param filePath Path to YAML file
 * @returns Parsed YAML content or null if file can't be read/parsed
 */
const readYamlFile = async (filePath: string): Promise<any | null> => {
  try {
    const content = await fs.readFile(filePath, "utf-8")
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
      await fs.mkdir(this.threadsDir, {recursive: true})
    } catch (error) {
      throw new ThreadRepositoryError("Failed to initialize threads directory", error as Error)
    }
  }
  
  /**
   * Sanitize a thread name for use as a file name
   * @param name Thread name
   * @returns Sanitized file name
   */
  private sanitizeFileName(name?: string): string {
    return (name || "untitled")
      .toLowerCase()
      // Replace spaces and special chars with hyphens
      .replace(/[^a-z0-9]+/g, "-")
      // Remove leading/trailing hyphens
      .replace(/^-+|-+$/g, "")
  }
  
  async getById(id: string): Promise<AiThread | null> {
    await this.initPromise
    try {
      const file = await this.findThreadFile(id)
      if (!file) return null
      
      const data = await readYamlFile(path.join(this.threadsDir, file))
      return data ? new AiThread(data) : null
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to read thread ${id}`, error as Error)
    }
  }
  
  
  /**
   * Find a thread file by its ID using the filename pattern {name}-{id}.yml
   * @param threadId ID of the thread to find
   * @returns Filename if found, null otherwise
   */
  private async findThreadFile(threadId: string): Promise<string | null> {
    try {
      const files = await fs.readdir(this.threadsDir)
      const threadFile = files.find(file => file.endsWith(`-${threadId}.yml`))
      return threadFile || null
    } catch (error) {
      throw new ThreadRepositoryError(`Error finding thread ${threadId}`, error as Error)
    }
  }
  
  
  /**
   * Generate a filename for a thread, combining sanitized name and id
   * @param thread Thread to generate filename for
   * @returns The filename with .yml extension
   */
  private getThreadFileName(thread: AiThread): string {
    const sanitizedName = this.sanitizeFileName(thread.name || "untitled")
    return `${sanitizedName}-${thread.id}.yml`
  }
  
  /**
   * Save a thread to a file.
   * Uses thread name and id to create a unique filename.
   * Creates a new file if name changed.
   * @param thread Thread to save
   * @returns The saved thread
   */
  async save(thread: AiThread): Promise<AiThread> {
    await this.initPromise
    try {
      if (!thread.id) thread.id = crypto.randomUUID()
      const fileName = this.getThreadFileName(thread)
      const contentToSave = yaml.stringify(thread)
      
      // Write the file
      await fs.writeFile(
        path.join(this.threadsDir, fileName),
        contentToSave,
        "utf-8"
      )
      
      return thread
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to save thread ${thread.id}`, error as Error)
    }
  }
  
  async listThreads(): Promise<Array<ThreadSummary>> {
    await this.initPromise
    try {
      const files = await fs.readdir(this.threadsDir)
      return (await Promise.all(
        files
          .filter(file => file.endsWith(".yml"))
          .map(async file => {
            const data = await readYamlFile(path.join(this.threadsDir, file))
            return data ? {
              id: data.id,
              name: data.name || "untitled",
              summary: data.summary || "",
              createdDate: data.createdDate || "",
              modifiedDate: data.modifiedDate || ""
            } : null
          })
      ))
        .filter(t => !!t)
        // sort by decreasing last modified date
        .sort((a: ThreadSummary, b: ThreadSummary) => a.modifiedDate > b.modifiedDate ? -1 : 1)
    } catch (error) {
      throw new ThreadRepositoryError("Failed to list threads", error as Error)
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
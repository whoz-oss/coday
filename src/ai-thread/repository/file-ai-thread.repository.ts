/**
 * @fileoverview File-based implementation of ThreadRepository using YAML files
 */

import fs from "fs/promises"
import path from "path"
import * as yaml from "yaml"
import {AiThread} from "../ai-thread"
import {AiThreadRepository} from "../ai-thread.repository"
import {ThreadRepositoryError} from "../ai-thread.types"

/**
 * File-based implementation of ThreadRepository
 * Stores threads as individual YAML files in the project's .coday/threads directory
 */
export class FileAiThreadRepository implements AiThreadRepository {
  
  /**
   * Creates a new FileAiThreadRepository
   * @param threadsDir Directory path where thread files will be stored
   */
  constructor(private readonly threadsDir: string) {
  }
  
  
  /**
   * Initialize the repository by ensuring the threads directory exists
   */
  async initialize(): Promise<void> {
    try {
      await fs.mkdir(this.threadsDir, {recursive: true})
    } catch (error) {
      throw new ThreadRepositoryError("Failed to initialize threads directory", error as Error)
    }
  }
  
  /**
   * Get the file path for a thread using its name
   * @param thread Thread instance containing name and id
   * @returns Full path to the thread's YAML file
   */
  private getFilePath(thread: AiThread): string
  /**
   * Get the file path for a thread using its id (for retrieval)
   * @param id Thread identifier
   * @returns Full path to the thread's YAML file
   */
  private getFilePath(id: string): string
  private getFilePath(threadOrId: AiThread | string): string {
    // If given a thread, use its name, otherwise we're looking up by id
    const isThread = threadOrId instanceof AiThread
    const fileName = isThread
      ? this.sanitizeFileName(threadOrId.name)
      : this.findFileNameById(threadOrId)
    
    return path.join(this.threadsDir, `${fileName}.yml`)
  }
  
  /**
   * Sanitize a thread name for use as a file name
   * @param name Thread name
   * @returns Sanitized file name
   */
  private sanitizeFileName(name: string): string {
    return name
        .toLowerCase()
        // Replace spaces and special chars with hyphens
        .replace(/[^a-z0-9]+/g, "-")
        // Remove leading/trailing hyphens
        .replace(/^-+|-+$/g, "")
      // Ensure we have something valid
      || "untitled"
  }
  
  /**
   * Find a file name by thread id by scanning directory
   * @param id Thread id to find
   * @returns File name if found
   * @throws ThreadRepositoryError if file not found
   */
  private async findFileNameById(id: string): Promise<string> {
    try {
      const files = await fs.readdir(this.threadsDir)
      for (const file of files) {
        if (!file.endsWith(".yml")) continue
        
        const content = await fs.readFile(path.join(this.threadsDir, file), "utf-8")
        const data = yaml.parse(content)
        if (data.id === id) {
          return file.slice(0, -4) // Remove .yml extension
        }
      }
      throw new ThreadRepositoryError(`Thread ${id} not found`)
    } catch (error) {
      if (error instanceof ThreadRepositoryError) throw error
      throw new ThreadRepositoryError(`Error finding thread ${id}`, error as Error)
    }
  }
  
  async getById(id: string): Promise<AiThread | null> {
    try {
      // First find the file with matching id
      const fileName = await this.findFileNameById(id)
      const filePath = path.join(this.threadsDir, `${fileName}.yml`)
      
      const content = await fs.readFile(filePath, "utf-8")
      const data = yaml.parse(content)
      return new AiThread(data)
    } catch (error) {
      if (error instanceof ThreadRepositoryError) {
        return null
      }
      throw new ThreadRepositoryError(`Failed to read thread ${id}`, error as Error)
    }
  }
  
  async save(thread: AiThread): Promise<AiThread> {
    try {
      // Check for name collisions
      const sanitizedName = this.sanitizeFileName(thread.name)
      const files = await fs.readdir(this.threadsDir)
      const existingFile = files.find(file => {
        const fileName = file.slice(0, -4) // Remove .yml
        return fileName === sanitizedName
      })
      
      if (existingFile) {
        // Read the existing file to check if it's the same thread (update case)
        const content = await fs.readFile(path.join(this.threadsDir, existingFile), "utf-8")
        const data = yaml.parse(content)
        if (data.id !== thread.id) {
          // Different thread with same sanitized name, append id
          const uniqueName = `${sanitizedName}-${thread.id}`
          const filePath = path.join(this.threadsDir, `${uniqueName}.yml`)
          await fs.writeFile(filePath, yaml.stringify(thread), "utf-8")
          return thread
        }
      }
      
      // Normal save case
      const filePath = path.join(this.threadsDir, `${sanitizedName}.yml`)
      await fs.writeFile(filePath, yaml.stringify(thread), "utf-8")
      return thread
    } catch (error) {
      throw new ThreadRepositoryError(`Failed to save thread ${thread.id}`, error as Error)
    }
  }
  
  async listThreads(): Promise<Array<{
    id: string,
    name: string,
    summary: string,
    createdDate: string,
    modifiedDate: string
  }>> {
    try {
      const files = await fs.readdir(this.threadsDir)
      const threads = await Promise.all(
        files
          .filter(file => file.endsWith(".yml"))
          .map(async file => {
            try {
              const content = await fs.readFile(path.join(this.threadsDir, file), "utf-8")
              const data = yaml.parse(content)
              return {
                id: data.id,
                name: data.name || "untitled",
                summary: data.summary || "",
                createdDate: data.createdDate || "",
                modifiedDate: data.modifiedDate || ""
              }
            } catch {
              // Skip files that can't be read or parsed
              return null
            }
          })
      )
      // Filter out null entries from failed reads
      return threads.filter((t): t is NonNullable<typeof t> => t !== null)
    } catch (error) {
      throw new ThreadRepositoryError("Failed to list threads", error as Error)
    }
  }
  
  async delete(id: string): Promise<boolean> {
    try {
      // First find the file with matching id
      const fileName = await this.findFileNameById(id)
      const filePath = path.join(this.threadsDir, `${fileName}.yml`)
      
      await fs.unlink(filePath)
      return true
    } catch (error) {
      if (error instanceof ThreadRepositoryError) {
        return false
      }
      throw new ThreadRepositoryError(`Failed to delete thread ${id}`, error as Error)
    }
  }
}
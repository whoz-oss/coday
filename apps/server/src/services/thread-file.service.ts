import * as path from 'path'
import * as fs from 'fs'
import * as fsPromises from 'fs/promises'

/**
 * File metadata returned by listFiles
 */
export interface FileMetadata {
  filename: string
  size: number
  lastModified: string // Changed from modifiedAt to match frontend expectation
}

/**
 * Service for managing thread-specific files (conversation workspace)
 *
 * Each thread has its own isolated directory for file storage:
 * .coday/projects/{projectName}/threads/{threadId}-files/
 *
 * This service encapsulates all filesystem operations for thread files,
 * providing a clean API for file management.
 */
export class ThreadFileService {
  constructor(private readonly projectsDir: string) {}

  /**
   * Get the thread files directory path
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns Absolute path to thread files directory
   */
  private getThreadFilesDir(projectName: string, threadId: string): string {
    return path.join(this.projectsDir, projectName, 'threads', `${threadId}-files`)
  }

  /**
   * Ensure the thread files directory exists (create if needed)
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  async ensureThreadFilesDir(projectName: string, threadId: string): Promise<void> {
    const filesDir = this.getThreadFilesDir(projectName, threadId)
    if (!fs.existsSync(filesDir)) {
      await fsPromises.mkdir(filesDir, { recursive: true })
    }
  }

  /**
   * List all files in a thread's workspace
   * @param projectName Project name
   * @param threadId Thread identifier
   * @returns Array of file metadata
   */
  async listFiles(projectName: string, threadId: string): Promise<FileMetadata[]> {
    // Ensure directory exists
    await this.ensureThreadFilesDir(projectName, threadId)

    const filesDir = this.getThreadFilesDir(projectName, threadId)

    const files = await fsPromises.readdir(filesDir)
    const fileStats = await Promise.all(
      files.map(async (filename) => {
        const filePath = path.join(filesDir, filename)
        const stats = await fsPromises.stat(filePath)
        return {
          filename,
          size: stats.size,
          lastModified: stats.mtime.toISOString(), // Use lastModified to match frontend
        }
      })
    )

    return fileStats
  }

  /**
   * Save a file to the thread's workspace
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param filename File name
   * @param buffer File content as buffer
   */
  async saveFile(projectName: string, threadId: string, filename: string, buffer: Buffer): Promise<void> {
    const filesDir = this.getThreadFilesDir(projectName, threadId)

    // Create directory if it doesn't exist
    await fsPromises.mkdir(filesDir, { recursive: true })

    const filePath = path.join(filesDir, filename)
    await fsPromises.writeFile(filePath, buffer)
  }

  /**
   * Get a file from the thread's workspace
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param filename File name
   * @returns File content as buffer
   * @throws Error if file doesn't exist
   */
  async getFile(projectName: string, threadId: string, filename: string): Promise<Buffer> {
    // Ensure directory exists (in case of race conditions)
    await this.ensureThreadFilesDir(projectName, threadId)

    const filesDir = this.getThreadFilesDir(projectName, threadId)
    const filePath = path.join(filesDir, filename)

    // Security: Ensure the resolved path is within the files directory
    const resolvedPath = path.resolve(filePath)
    const resolvedFilesDir = path.resolve(filesDir)
    if (!resolvedPath.startsWith(resolvedFilesDir)) {
      throw new Error('Access denied: invalid file path')
    }

    // Check if file exists
    if (!fs.existsSync(filePath)) {
      throw new Error(`File '${filename}' not found`)
    }

    return await fsPromises.readFile(filePath)
  }

  /**
   * Get the absolute path to a file (for sendFile)
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param filename File name
   * @returns Absolute file path
   * @throws Error if file doesn't exist or path is invalid
   */
  async getFilePath(projectName: string, threadId: string, filename: string): Promise<string> {
    // Ensure directory exists (in case of race conditions)
    await this.ensureThreadFilesDir(projectName, threadId)

    const filesDir = this.getThreadFilesDir(projectName, threadId)
    const filePath = path.join(filesDir, filename)

    // Security: Ensure the resolved path is within the files directory
    const resolvedPath = path.resolve(filePath)
    const resolvedFilesDir = path.resolve(filesDir)
    if (!resolvedPath.startsWith(resolvedFilesDir)) {
      throw new Error('Access denied: invalid file path')
    }

    // Check if file exists
    if (!fs.existsSync(filePath)) {
      throw new Error(`File '${filename}' not found`)
    }

    return filePath
  }

  /**
   * Check if a file exists in the thread's workspace
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param filename File name
   * @returns true if file exists
   */
  fileExists(projectName: string, threadId: string, filename: string): boolean {
    const filesDir = this.getThreadFilesDir(projectName, threadId)
    const filePath = path.join(filesDir, filename)

    // Security check
    const resolvedPath = path.resolve(filePath)
    const resolvedFilesDir = path.resolve(filesDir)
    if (!resolvedPath.startsWith(resolvedFilesDir)) {
      return false
    }

    return fs.existsSync(filePath)
  }

  /**
   * Delete a file from the thread's workspace
   * @param projectName Project name
   * @param threadId Thread identifier
   * @param filename File name
   * @throws Error if file doesn't exist or path is invalid
   */
  async deleteFile(projectName: string, threadId: string, filename: string): Promise<void> {
    // Ensure directory exists (in case of race conditions)
    await this.ensureThreadFilesDir(projectName, threadId)

    const filesDir = this.getThreadFilesDir(projectName, threadId)
    const filePath = path.join(filesDir, filename)

    // Security: Ensure the resolved path is within the files directory
    const resolvedPath = path.resolve(filePath)
    const resolvedFilesDir = path.resolve(filesDir)
    if (!resolvedPath.startsWith(resolvedFilesDir)) {
      throw new Error('Access denied: invalid file path')
    }

    // Check if file exists
    if (!fs.existsSync(filePath)) {
      throw new Error(`File '${filename}' not found`)
    }

    await fsPromises.unlink(filePath)
  }

  /**
   * Delete all files for a thread (cleanup when thread is deleted)
   * @param projectName Project name
   * @param threadId Thread identifier
   */
  async deleteThreadFiles(projectName: string, threadId: string): Promise<void> {
    try {
      const filesDir = this.getThreadFilesDir(projectName, threadId)

      if (fs.existsSync(filesDir)) {
        await fsPromises.rm(filesDir, { recursive: true, force: true })
        console.log(`Deleted thread files directory: ${filesDir}`)
      }
    } catch (error) {
      console.error(`Error deleting thread files for ${threadId}:`, error)
      // Don't throw - file cleanup failure shouldn't prevent thread deletion
    }
  }
}

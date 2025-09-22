/**
 * Automatic cleanup service for expired threads
 * Used only on server side
 */

import * as fs from 'fs/promises'
import * as path from 'path'
import * as yaml from 'yaml'
import { CodayLogger } from './coday-logger'

// Service configuration
const INITIAL_DELAY_MINUTES = 5
const CLEANUP_INTERVAL_HOURS = 24
const TTL_DAYS = 30
const BATCH_SIZE = 100

export class ThreadCleanupService {
  private cleanupTimer: NodeJS.Timeout | null = null
  private initialTimer: NodeJS.Timeout | null = null
  private isRunning = false

  constructor(
    private readonly projectsConfigPath: string,
    private readonly logger: CodayLogger
  ) {}

  /**
   * Starts the cleanup service
   * Performs first cleanup after initial delay, then periodically
   */
  async start(): Promise<void> {
    if (this.isRunning) {
      this.log('Thread cleanup service already running')
      return
    }

    this.isRunning = true
    this.log(`Starting thread cleanup service (TTL: ${TTL_DAYS} days)`)

    // First cleanup after initial delay
    this.initialTimer = setTimeout(
      async () => {
        await this.performCleanup()

        // Then periodic cleanup
        this.cleanupTimer = setInterval(
          async () => {
            await this.performCleanup()
          },
          CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000
        )

        this.log(`Thread cleanup scheduled every ${CLEANUP_INTERVAL_HOURS} hours`)
      },
      INITIAL_DELAY_MINUTES * 60 * 1000
    )

    this.log(`Initial cleanup scheduled in ${INITIAL_DELAY_MINUTES} minutes`)
  }

  /**
   * Stops the cleanup service
   */
  async stop(): Promise<void> {
    // Clear both timers to prevent any cleanup from running
    if (this.initialTimer) {
      clearTimeout(this.initialTimer)
      this.initialTimer = null
    }
    
    if (this.cleanupTimer) {
      clearInterval(this.cleanupTimer)
      this.cleanupTimer = null
    }
    
    this.isRunning = false
    this.log('Thread cleanup service stopped (all timers cleared)')
  }

  /**
   * Performs cleanup of expired threads
   */
  private async performCleanup(): Promise<void> {
    const startTime = Date.now()
    let totalScanned = 0
    let totalDeleted = 0
    let totalErrors = 0

    try {
      this.log('Starting thread cleanup...')

      // List all existing projects
      const projectDirs = await fs.readdir(this.projectsConfigPath)
      this.log(`Found ${projectDirs.length} projects to scan`)

      // Clean threads for each project
      for (const projectName of projectDirs) {
        try {
          const projectPath = path.join(this.projectsConfigPath, projectName)
          const stat = await fs.lstat(projectPath)

          if (!stat.isDirectory()) continue

          const threadsDir = path.join(projectPath, 'threads')

          // Check if threads directory exists
          try {
            await fs.access(threadsDir)
          } catch {
            continue // No threads directory for this project
          }

          const { scanned, deleted, errors } = await this.cleanupProjectThreads(projectName, threadsDir)
          totalScanned += scanned
          totalDeleted += deleted
          totalErrors += errors
        } catch (error) {
          this.logError(`Error processing project ${projectName}: ${error}`)
          totalErrors++
        }
      }

      const duration = Date.now() - startTime
      this.log(
        `Cleanup completed: ${totalDeleted}/${totalScanned} threads deleted across all projects in ${duration}ms (${totalErrors} errors)`
      )
    } catch (error) {
      const duration = Date.now() - startTime
      this.logError(`Cleanup failed after ${duration}ms: ${error}`)
    }
  }

  /**
   * Cleans expired threads for a specific project
   */
  private async cleanupProjectThreads(
    projectName: string,
    threadsDir: string
  ): Promise<{
    scanned: number
    deleted: number
    errors: number
  }> {
    let scanned = 0
    let deleted = 0
    let errors = 0

    try {
      const files = await fs.readdir(threadsDir)
      const threadFiles = files.filter((file) => file.endsWith('.yml'))
      scanned = threadFiles.length

      if (threadFiles.length === 0) {
        return { scanned, deleted, errors }
      }

      this.log(`Scanning ${threadFiles.length} threads in project ${projectName}`)

      // Process in batches to avoid overload
      for (let i = 0; i < threadFiles.length; i += BATCH_SIZE) {
        const batch = threadFiles.slice(i, i + BATCH_SIZE)
        const batchResult = await this.processBatch(batch, threadsDir, projectName)
        deleted += batchResult.deleted
        errors += batchResult.errors
      }

      if (deleted > 0) {
        this.log(`Project ${projectName}: deleted ${deleted}/${scanned} expired threads`)
      }
    } catch (error) {
      this.logError(`Error scanning project ${projectName}: ${error}`)
      errors++
    }

    return { scanned, deleted, errors }
  }

  /**
   * Processes a batch of thread files
   */
  private async processBatch(
    files: string[],
    threadsDir: string,
    projectName: string
  ): Promise<{
    deleted: number
    errors: number
  }> {
    let deleted = 0
    let errors = 0

    await Promise.all(
      files.map(async (file) => {
        const filePath = path.join(threadsDir, file)
        let threadData: any
        try {
          const content = await fs.readFile(filePath, 'utf-8')
          threadData = yaml.parse(content)
        } catch (error) {
          await fs.unlink(filePath)
          this.logError(`Error processing file ${file} in project ${projectName}: ${error}`)
        }

        if (!threadData || !threadData.modifiedDate) {
          return // Skip invalid files
        }

        // Check if thread is expired
        if (this.isThreadExpired(threadData.modifiedDate)) {
          await fs.unlink(filePath)
          deleted++
          // Audit trail logging
          this.logger.logThreadCleanup(projectName, file)
          console.log(`ThreadCleanup: Deleted expired thread: ${threadData.id || file} from project ${projectName}`)
        }
      })
    )

    return { deleted, errors }
  }

  /**
   * Checks if a thread is expired
   */
  private isThreadExpired(modifiedDate: string): boolean {
    const expirationDate = new Date(modifiedDate)
    expirationDate.setDate(expirationDate.getDate() + TTL_DAYS)
    return new Date() > expirationDate
  }

  /**
   * Standard logging
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString()
    console.log(`[${timestamp}] ThreadCleanup: ${message}`)
    // Note: CodayLogger only logs agent interactions, not general messages
  }

  /**
   * Error logging
   */
  private logError(message: string): void {
    const timestamp = new Date().toISOString()
    console.error(`[${timestamp}] ThreadCleanup ERROR: ${message}`)
    // Note: CodayLogger only logs agent interactions, not errors
  }

  /**
   * Method to force manual cleanup (useful for tests/debug)
   */
  async forceCleanup(): Promise<void> {
    this.log('Manual cleanup triggered')
    await this.performCleanup()
  }
}

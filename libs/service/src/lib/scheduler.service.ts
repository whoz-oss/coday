import * as fs from 'fs'
import * as path from 'path'
import * as yaml from 'yaml'
import { randomUUID } from 'node:crypto'
import type { Scheduler, SchedulerInfo, IntervalSchedule } from '@coday/model'
import { validateIntervalSchedule, calculateNextRun, shouldExecuteNow } from '@coday/utils'
import { CodayLogger } from '@coday/model'
import { PromptService } from './prompt.service'
import { isUserAdmin } from './user-groups'

/**
 * Forward declaration for PromptExecutionService to avoid circular dependency
 */
interface PromptExecutionService {
  executePrompt(
    promptId: string,
    parameters: Record<string, unknown> | string | undefined,
    username: string,
    executionMode: 'direct' | 'scheduled' | 'webhook',
    options?: { title?: string; awaitFinalAnswer?: boolean; projectName?: string }
  ): Promise<{ threadId: string; lastEvent?: any }>
}

/**
 * SchedulerService - Manages scheduled prompt execution
 *
 * Similar pattern to ThreadCleanupService:
 * - Loads all schedulers from all projects at startup
 * - Checks every 30 seconds if any scheduler should execute
 * - Executes via PromptExecutionService
 * - Updates lastRun/nextRun timestamps
 */
export class SchedulerService {
  private readonly schedulers: Map<string, Scheduler> = new Map()
  private checkInterval?: NodeJS.Timeout
  private readonly CHECK_INTERVAL_MS = 30000 // 30 seconds
  private promptExecutionService?: PromptExecutionService

  constructor(
    private readonly logger: CodayLogger,
    private readonly promptService: PromptService,
    private readonly codayConfigDir: string
  ) {}

  /**
   * Initialize execution service (called after server initialization)
   */
  initializeExecution(promptExecutionService: PromptExecutionService): void {
    this.promptExecutionService = promptExecutionService
  }

  /**
   * Initialize service and start scheduling
   */
  async initialize(): Promise<void> {
    console.log('[SCHEDULER] Initializing SchedulerService...')

    // Load all schedulers from all projects
    await this.loadAllSchedulers()

    // Start the check interval
    this.startScheduler()

    console.log(`[SCHEDULER] SchedulerService initialized with ${this.schedulers.size} schedulers`)
  }

  /**
   * Stop the scheduler (for graceful shutdown)
   */
  stop(): void {
    if (this.checkInterval) {
      clearInterval(this.checkInterval)
      this.checkInterval = undefined
      console.log('[SCHEDULER] SchedulerService stopped')
    }
  }

  /**
   * Load all schedulers from all projects
   */
  private async loadAllSchedulers(): Promise<void> {
    this.schedulers.clear()

    const projectsPath = path.join(this.codayConfigDir, 'projects')

    if (!fs.existsSync(projectsPath)) {
      return
    }

    const projectDirs = fs
      .readdirSync(projectsPath, { withFileTypes: true })
      .filter((dirent) => dirent.isDirectory())
      .map((dirent) => dirent.name)

    for (const projectName of projectDirs) {
      try {
        const projectSchedulers = await this.loadProjectSchedulers(projectName)
        for (const scheduler of projectSchedulers) {
          this.schedulers.set(scheduler.id, scheduler)
        }
      } catch (error) {
        console.error(`[SCHEDULER] Failed to load schedulers for project ${projectName}:`, error)
      }
    }
  }

  /**
   * Load schedulers for a specific project
   */
  private async loadProjectSchedulers(projectName: string): Promise<Scheduler[]> {
    const schedulersDir = this.getSchedulersDir(projectName)

    if (!fs.existsSync(schedulersDir)) {
      return []
    }

    const files = fs.readdirSync(schedulersDir)
    const schedulers: Scheduler[] = []

    for (const file of files) {
      if (!file.endsWith('.yml')) continue

      try {
        const filePath = path.join(schedulersDir, file)
        const content = fs.readFileSync(filePath, 'utf-8')
        const scheduler = yaml.parse(content) as Scheduler

        // Calculate nextRun, skipping missed occurrences
        const result = this.calculateNextRunSkippingMissed(scheduler)
        const occurrenceCountChanged = result.occurrenceCount !== scheduler.occurrenceCount
        scheduler.nextRun = result.nextRun
        scheduler.occurrenceCount = result.occurrenceCount

        // If we skipped occurrences, save the updated scheduler
        if (occurrenceCountChanged) {
          await this.saveScheduler(scheduler, projectName)
        }

        schedulers.push(scheduler)
      } catch (error) {
        console.error(`[SCHEDULER] Failed to load scheduler from ${file}:`, error)
      }
    }

    return schedulers
  }

  /**
   * Start the scheduler that checks every 30 seconds
   */
  private startScheduler(): void {
    this.checkInterval = setInterval(() => {
      this.checkAndExecuteSchedulers()
    }, this.CHECK_INTERVAL_MS)

    // Also check immediately on startup
    this.checkAndExecuteSchedulers()
  }

  /**
   * Check all schedulers and execute those that should run
   */
  private async checkAndExecuteSchedulers(): Promise<void> {
    for (const scheduler of this.schedulers.values()) {
      if (!scheduler.enabled) continue

      // Check if scheduler should execute now
      if (shouldExecuteNow(scheduler.schedule, scheduler.nextRun ?? null, scheduler.occurrenceCount ?? 0)) {
        this.executeScheduler(scheduler).catch((error) => {
          console.error(`[SCHEDULER] Failed to execute scheduler ${scheduler.id}:`, error)
        })
      }
    }
  }

  /**
   * Execute a scheduler internally (scheduled or manual)
   * @param scheduler - Scheduler to execute
   * @param projectName - Project name where scheduler is stored
   * @param mode - 'scheduled' or 'manual'
   * @returns Thread ID created by prompt execution
   * @throws Error if prompt not found or execution fails
   */
  private async executeSchedulerInternal(
    scheduler: Scheduler,
    projectName: string,
    mode: 'scheduled' | 'manual'
  ): Promise<string> {
    if (!this.promptExecutionService) {
      throw new Error('PromptExecutionService not initialized')
    }

    console.log(`[SCHEDULER] Executing scheduler "${scheduler.name}" (${scheduler.id}) [${mode}]`)

    // Execute prompt with scheduler parameters
    // Use scheduler's createdBy as username for execution
    // Convert parameters: if it's {PARAMETERS: "value"}, extract the string
    let parameters: Record<string, unknown> | string | undefined = scheduler.parameters
    if (
      scheduler.parameters &&
      typeof scheduler.parameters === 'object' &&
      Object.keys(scheduler.parameters).length === 1 &&
      'PARAMETERS' in scheduler.parameters
    ) {
      // Simple mode: extract the string value
      parameters = String(scheduler.parameters.PARAMETERS)
    }

    const result = await this.promptExecutionService.executePrompt(
      scheduler.promptId,
      parameters,
      scheduler.createdBy,
      'scheduled',
      {
        title: mode === 'scheduled' ? `Scheduled: ${scheduler.name}` : `Manual: ${scheduler.name}`,
        awaitFinalAnswer: false, // async execution
        projectName, // Pass project name from scheduler context
      }
    )

    console.log(`[SCHEDULER] Scheduler "${scheduler.name}" executed successfully. Thread: ${result.threadId}`)

    return result.threadId
  }

  /**
   * Execute a scheduler (called by scheduler loop)
   */
  private async executeScheduler(scheduler: Scheduler): Promise<void> {
    const executedAt = new Date().toISOString()

    console.log(`[SCHEDULER] Executing scheduler "${scheduler.name}" (${scheduler.id})`)

    // Find project name first (needed for saving)
    const projectName = this.findProjectForScheduler(scheduler.id)
    if (!projectName) {
      console.error(`[SCHEDULER] Cannot find project for scheduler ${scheduler.id}, skipping execution`)
      return
    }

    let success: boolean
    let threadId: string | undefined
    let error: string | undefined

    // Update scheduler state
    scheduler.lastRun = executedAt
    scheduler.occurrenceCount = (scheduler.occurrenceCount ?? 0) + 1
    scheduler.nextRun = calculateNextRun(scheduler.schedule, new Date(), scheduler.occurrenceCount)

    try {
      // Save updated scheduler with explicit project name
      await this.saveScheduler(scheduler, projectName)
      console.log(`[SCHEDULER] Next execution for "${scheduler.name}": ${scheduler.nextRun}`)

      // Update in-memory cache with the updated scheduler
      this.schedulers.set(scheduler.id, scheduler)

      threadId = await this.executeSchedulerInternal(scheduler, projectName, 'scheduled')
      success = true
    } catch (err) {
      success = false
      error = err instanceof Error ? err.message : String(err)
      console.error(`[SCHEDULER] Scheduler "${scheduler.name}" failed:`, err)
    }

    // Log execution result via CodayLogger
    this.logger.logTriggerExecution({
      triggerId: scheduler.id,
      triggerName: scheduler.name,
      webhookUuid: scheduler.promptId,
      projectName,
      success,
      threadId,
      error,
    })
  }

  /**
   * Public method to validate interval schedules (used by routes)
   * Delegates to interval-schedule.utils for actual validation
   */
  validateSchedule(schedule: IntervalSchedule): { valid: boolean; error?: string } {
    return validateIntervalSchedule(schedule)
  }

  /**
   * Calculate next run time, skipping missed occurrences
   */
  private calculateNextRunSkippingMissed(scheduler: Scheduler): { nextRun: string | null; occurrenceCount: number } {
    const now = new Date()
    let occurrenceCount = scheduler.occurrenceCount ?? 0
    let nextRun = scheduler.nextRun

    // If no nextRun, calculate from now
    if (!nextRun) {
      return {
        nextRun: calculateNextRun(scheduler.schedule, now, occurrenceCount),
        occurrenceCount,
      }
    }

    // If nextRun is in the future, keep it
    if (new Date(nextRun) >= now) {
      return { nextRun, occurrenceCount }
    }

    // nextRun is in the past - skip missed occurrences
    const maxIterations = 1000
    let iterations = 0
    let skippedCount = 0

    while (iterations < maxIterations) {
      occurrenceCount++
      skippedCount++

      nextRun = calculateNextRun(scheduler.schedule, now, occurrenceCount)

      if (!nextRun) {
        if (skippedCount > 0) {
          console.log(
            `[SCHEDULER] Scheduler "${scheduler.name}" (${scheduler.id}) expired after skipping ${skippedCount} missed occurrence(s)`
          )
        }
        return { nextRun: null, occurrenceCount }
      }

      if (new Date(nextRun) >= now) {
        if (skippedCount > 0) {
          console.log(
            `[SCHEDULER] Scheduler "${scheduler.name}" (${scheduler.id}) skipped ${skippedCount} missed occurrence(s), next run: ${nextRun}`
          )
        }
        return { nextRun, occurrenceCount }
      }

      iterations++
    }

    console.warn(
      `[SCHEDULER] Could not find future nextRun for scheduler ${scheduler.id} after ${maxIterations} iterations`
    )
    return { nextRun: null, occurrenceCount }
  }

  /**
   * Get schedulers directory path for a project
   */
  private getSchedulersDir(projectName: string): string {
    return path.join(this.codayConfigDir, 'projects', projectName, 'schedulers')
  }

  /**
   * Get scheduler file path
   */
  private getSchedulerFilePath(projectName: string, schedulerId: string): string {
    return path.join(this.getSchedulersDir(projectName), `${schedulerId}.yml`)
  }

  /**
   * Find project name for a scheduler ID
   */
  private findProjectForScheduler(schedulerId: string): string | null {
    const projectsPath = path.join(this.codayConfigDir, 'projects')

    if (!fs.existsSync(projectsPath)) {
      return null
    }

    const projectDirs = fs
      .readdirSync(projectsPath, { withFileTypes: true })
      .filter((dirent) => dirent.isDirectory())
      .map((dirent) => dirent.name)

    for (const projectName of projectDirs) {
      const schedulerPath = this.getSchedulerFilePath(projectName, schedulerId)
      if (fs.existsSync(schedulerPath)) {
        return projectName
      }
    }

    return null
  }

  // ==================== CRUD Operations ====================

  /**
   * Check if a user can access a scheduler (owner or CODAY_ADMIN)
   */
  private canAccessScheduler(scheduler: Scheduler, username: string): boolean {
    if (scheduler.createdBy === username) {
      return true
    }
    return isUserAdmin(username, this.codayConfigDir)
  }

  /**
   * List all schedulers for a project with access control
   */
  async listSchedulers(projectName: string, username: string): Promise<SchedulerInfo[]> {
    const schedulers = await this.loadProjectSchedulers(projectName)

    return schedulers
      .filter((scheduler) => this.canAccessScheduler(scheduler, username))
      .map((scheduler) => ({
        id: scheduler.id,
        name: scheduler.name,
        enabled: scheduler.enabled,
        promptId: scheduler.promptId,
        schedule: scheduler.schedule,
        parameters: scheduler.parameters,
        lastRun: scheduler.lastRun,
        nextRun: scheduler.nextRun,
        createdBy: scheduler.createdBy,
      }))
  }

  /**
   * Get a specific scheduler with access control
   */
  async getScheduler(projectName: string, schedulerId: string, username?: string): Promise<Scheduler | null> {
    const filePath = this.getSchedulerFilePath(projectName, schedulerId)

    if (!fs.existsSync(filePath)) {
      return null
    }

    try {
      const content = fs.readFileSync(filePath, 'utf-8')
      const scheduler = yaml.parse(content) as Scheduler

      const result = this.calculateNextRunSkippingMissed(scheduler)
      scheduler.nextRun = result.nextRun
      scheduler.occurrenceCount = result.occurrenceCount

      if (username && !this.canAccessScheduler(scheduler, username)) {
        console.log(`[SCHEDULER] Access denied for user ${username} to scheduler ${schedulerId}`)
        return null
      }

      return scheduler
    } catch (error) {
      console.error(`[SCHEDULER] Failed to load scheduler ${schedulerId}:`, error)
      return null
    }
  }

  /**
   * Create a new scheduler
   */
  async createScheduler(
    projectName: string,
    data: {
      name: string
      promptId: string
      schedule: IntervalSchedule
      parameters?: Record<string, unknown>
      enabled?: boolean
    },
    username: string
  ): Promise<Scheduler> {
    // Validate schedule
    const validation = this.validateSchedule(data.schedule)
    if (!validation.valid) {
      throw new Error(`Invalid schedule: ${validation.error}`)
    }

    // Verify prompt exists (use getById to find it across all projects)
    const promptResult = await this.promptService.getById(data.promptId)
    if (!promptResult) {
      throw new Error(`Prompt not found: ${data.promptId}`)
    }

    const scheduler: Scheduler = {
      id: randomUUID(),
      name: data.name,
      enabled: data.enabled ?? true,
      promptId: data.promptId,
      schedule: data.schedule,
      parameters: data.parameters,
      createdBy: username,
      createdAt: new Date().toISOString(),
      nextRun: calculateNextRun(data.schedule, new Date(), 0),
      occurrenceCount: 0,
    }

    await this.saveScheduler(scheduler, projectName)

    // Add to in-memory cache
    this.schedulers.set(scheduler.id, scheduler)

    console.log(`[SCHEDULER] Created scheduler "${scheduler.name}" (${scheduler.id}) for project ${projectName}`)

    return scheduler
  }

  /**
   * Update a scheduler with access control
   */
  async updateScheduler(
    projectName: string,
    schedulerId: string,
    updates: {
      name?: string
      enabled?: boolean
      promptId?: string
      schedule?: IntervalSchedule
      parameters?: Record<string, unknown>
    },
    username: string
  ): Promise<Scheduler> {
    const scheduler = await this.getScheduler(projectName, schedulerId, username)

    if (!scheduler) {
      throw new Error(`Scheduler not found or access denied: ${schedulerId}`)
    }

    console.log(`[SCHEDULER] Updating scheduler ${schedulerId} by user ${username}`)

    if (updates.name !== undefined) scheduler.name = updates.name
    if (updates.enabled !== undefined) scheduler.enabled = updates.enabled
    if (updates.parameters !== undefined) scheduler.parameters = updates.parameters

    if (updates.promptId !== undefined) {
      const promptResult = await this.promptService.getById(updates.promptId)
      if (!promptResult) {
        throw new Error(`Prompt not found: ${updates.promptId}`)
      }
      scheduler.promptId = updates.promptId
    }

    if (updates.schedule !== undefined) {
      const validation = this.validateSchedule(updates.schedule)
      if (!validation.valid) {
        throw new Error(`Invalid schedule: ${validation.error}`)
      }
      scheduler.schedule = updates.schedule
      scheduler.occurrenceCount = 0
      scheduler.nextRun = calculateNextRun(updates.schedule, new Date(), 0)
    }

    await this.saveScheduler(scheduler, projectName)
    this.schedulers.set(scheduler.id, scheduler)

    console.log(`[SCHEDULER] Updated scheduler "${scheduler.name}" (${scheduler.id})`)

    return scheduler
  }

  /**
   * Delete a scheduler with access control
   */
  async deleteScheduler(projectName: string, schedulerId: string, username: string): Promise<boolean> {
    const scheduler = await this.getScheduler(projectName, schedulerId, username)

    if (!scheduler) {
      return false
    }

    const filePath = this.getSchedulerFilePath(projectName, schedulerId)
    fs.unlinkSync(filePath)
    this.schedulers.delete(schedulerId)

    console.log(`[SCHEDULER] Deleted scheduler ${schedulerId} by user ${username}`)

    return true
  }

  /**
   * Enable a scheduler with access control
   */
  async enableScheduler(projectName: string, schedulerId: string, username: string): Promise<Scheduler> {
    return this.updateScheduler(projectName, schedulerId, { enabled: true }, username)
  }

  /**
   * Disable a scheduler with access control
   */
  async disableScheduler(projectName: string, schedulerId: string, username: string): Promise<Scheduler> {
    return this.updateScheduler(projectName, schedulerId, { enabled: false }, username)
  }

  /**
   * Manually execute a scheduler now (for testing) with access control
   */
  async runSchedulerNow(projectName: string, schedulerId: string, username: string): Promise<string> {
    const scheduler = await this.getScheduler(projectName, schedulerId, username)

    if (!scheduler) {
      throw new Error(`Scheduler not found or access denied: ${schedulerId}`)
    }

    const threadId = await this.executeSchedulerInternal(scheduler, projectName, 'manual')

    // Update lastRun but don't change nextRun (keep scheduled time)
    scheduler.lastRun = new Date().toISOString()
    await this.saveScheduler(scheduler, projectName)
    this.schedulers.set(scheduler.id, scheduler)

    // Log execution via CodayLogger
    this.logger.logTriggerExecution({
      triggerId: scheduler.id,
      triggerName: scheduler.name,
      webhookUuid: scheduler.promptId,
      projectName,
      success: true,
      threadId,
    })

    return threadId
  }

  /**
   * Save scheduler to disk
   */
  private async saveScheduler(scheduler: Scheduler, projectName?: string | null): Promise<void> {
    if (!projectName) {
      projectName = this.findProjectForScheduler(scheduler.id)
      if (!projectName) {
        throw new Error(`Cannot find project for scheduler ${scheduler.id}`)
      }
    }

    const schedulersDir = this.getSchedulersDir(projectName)
    fs.mkdirSync(schedulersDir, { recursive: true })

    const filePath = this.getSchedulerFilePath(projectName, scheduler.id)
    const content = yaml.stringify(scheduler)

    fs.writeFileSync(filePath, content, 'utf-8')
  }
}

import * as fs from 'fs'
import * as path from 'path'
import * as yaml from 'yaml'
import { randomUUID } from 'node:crypto'
import type { Trigger, TriggerInfo, IntervalSchedule } from '@coday/model'
import { validateIntervalSchedule, calculateNextRun, shouldExecuteNow } from '@coday/utils'
import { CodayLogger } from '@coday/model'
import { WebhookService } from './webhook.service'

/**
 * TriggerService - Manages scheduled webhook execution
 *
 * Similar pattern to ThreadCleanupService:
 * - Loads all triggers from all projects at startup
 * - Checks every minute if any trigger should execute
 * - Executes via WebhookService
 * - Updates lastRun/nextRun timestamps
 */
export class TriggerService {
  private readonly triggers: Map<string, Trigger> = new Map()
  private checkInterval?: NodeJS.Timeout
  private readonly CHECK_INTERVAL_MS = 30000 // 1 minute

  constructor(
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService,
    private readonly codayConfigDir: string
  ) {}

  /**
   * Initialize service and start scheduling
   */
  async initialize(): Promise<void> {
    console.log('[TRIGGER] Initializing TriggerService...')

    // Load all triggers from all projects
    await this.loadAllTriggers()

    // Start the check interval
    this.startScheduler()

    console.log(`[TRIGGER] TriggerService initialized with ${this.triggers.size} triggers`)
  }

  /**
   * Stop the scheduler (for graceful shutdown)
   */
  stop(): void {
    if (this.checkInterval) {
      clearInterval(this.checkInterval)
      this.checkInterval = undefined
      console.log('[TRIGGER] TriggerService stopped')
    }
  }

  /**
   * Load all triggers from all projects
   */
  private async loadAllTriggers(): Promise<void> {
    this.triggers.clear()

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
        const projectTriggers = await this.loadProjectTriggers(projectName)
        for (const trigger of projectTriggers) {
          this.triggers.set(trigger.id, trigger)
        }
      } catch (error) {
        console.error(`[TRIGGER] Failed to load triggers for project ${projectName}:`, error)
      }
    }
  }

  /**
   * Load triggers for a specific project
   */
  private async loadProjectTriggers(projectName: string): Promise<Trigger[]> {
    const triggersDir = this.getTriggersDir(projectName)

    if (!fs.existsSync(triggersDir)) {
      return []
    }

    const files = fs.readdirSync(triggersDir)
    const triggers: Trigger[] = []

    for (const file of files) {
      if (!file.endsWith('.yml')) continue

      try {
        const filePath = path.join(triggersDir, file)
        const content = fs.readFileSync(filePath, 'utf-8')
        const trigger = yaml.parse(content) as Trigger

        // Calculate nextRun, skipping missed occurrences
        const result = this.calculateNextRunSkippingMissed(trigger)
        const occurrenceCountChanged = result.occurrenceCount !== trigger.occurrenceCount
        trigger.nextRun = result.nextRun
        trigger.occurrenceCount = result.occurrenceCount

        // If we skipped occurrences, save the updated trigger
        if (occurrenceCountChanged) {
          await this.saveTrigger(trigger, projectName)
        }

        triggers.push(trigger)
      } catch (error) {
        console.error(`[TRIGGER] Failed to load trigger from ${file}:`, error)
      }
    }

    return triggers
  }

  /**
   * Start the scheduler that checks every minute
   */
  private startScheduler(): void {
    this.checkInterval = setInterval(() => {
      this.checkAndExecuteTriggers()
    }, this.CHECK_INTERVAL_MS)

    // Also check immediately on startup
    this.checkAndExecuteTriggers()
  }

  /**
   * Check all triggers and execute those that should run
   */
  private async checkAndExecuteTriggers(): Promise<void> {
    for (const trigger of this.triggers.values()) {
      if (!trigger.enabled) continue

      // Check if trigger should execute now
      if (shouldExecuteNow(trigger.schedule, trigger.nextRun ?? null, trigger.occurrenceCount ?? 0)) {
        this.executeTrigger(trigger).catch((error) => {
          console.error(`[TRIGGER] Failed to execute trigger ${trigger.id}:`, error)
        })
      }
    }
  }

  /**
   * Execute a trigger internally (scheduled or manual)
   * @param trigger - Trigger to execute
   * @param mode - 'scheduled' or 'manual'
   * @returns Thread ID created by webhook execution
   * @throws Error if webhook not found or execution fails
   */
  private async executeTriggerInternal(trigger: Trigger, mode: 'scheduled' | 'manual'): Promise<string> {
    console.log(`[TRIGGER] Executing trigger "${trigger.name}" (${trigger.id}) [${mode}]`)

    // Execute webhook with trigger parameters
    // Use trigger's createdBy as username for execution (not webhook's createdBy)
    const result = await this.webhookService.executeWebhook(
      trigger.webhookUuid,
      trigger.parameters || {},
      trigger.createdBy,
      mode === 'scheduled' ? `Scheduled: ${trigger.name}` : `Manual: ${trigger.name}`,
      false // async execution
    )

    console.log(`[TRIGGER] Trigger "${trigger.name}" executed successfully. Thread: ${result.threadId}`)

    return result.threadId
  }

  /**
   * Execute a trigger (called by scheduler)
   */
  private async executeTrigger(trigger: Trigger): Promise<void> {
    const executedAt = new Date().toISOString()

    console.log(`[TRIGGER] Executing trigger "${trigger.name}" (${trigger.id})`)

    // Find project name first (needed for saving)
    const projectName = this.findProjectForTrigger(trigger.id)
    if (!projectName) {
      console.error(`[TRIGGER] Cannot find project for trigger ${trigger.id}, skipping execution`)
      return
    }

    let success: boolean
    let threadId: string | undefined
    let error: string | undefined

    // Update trigger state
    trigger.lastRun = executedAt
    trigger.occurrenceCount = (trigger.occurrenceCount ?? 0) + 1
    trigger.nextRun = calculateNextRun(trigger.schedule, new Date(), trigger.occurrenceCount)

    try {
      // Save updated trigger with explicit project name
      await this.saveTrigger(trigger, projectName)
      console.log(`[TRIGGER] Next execution for "${trigger.name}": ${trigger.nextRun}`)

      // Update in-memory cache with the updated trigger
      this.triggers.set(trigger.id, trigger)

      threadId = await this.executeTriggerInternal(trigger, 'scheduled')
      success = true
    } catch (err) {
      success = false
      error = err instanceof Error ? err.message : String(err)
      console.error(`[TRIGGER] Trigger "${trigger.name}" failed:`, err)
    }

    // Log execution result via CodayLogger
    this.logger.logTriggerExecution({
      triggerId: trigger.id,
      triggerName: trigger.name,
      webhookUuid: trigger.webhookUuid,
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
   *
   * If nextRun is in the past, we increment the occurrence counter for each
   * missed occurrence until we find a future date or the trigger expires.
   *
   * @param trigger - Trigger to calculate next run for
   * @returns Object with nextRun and updated occurrenceCount
   */
  private calculateNextRunSkippingMissed(trigger: Trigger): { nextRun: string | null; occurrenceCount: number } {
    const now = new Date()
    let occurrenceCount = trigger.occurrenceCount ?? 0
    let nextRun = trigger.nextRun

    // If no nextRun, calculate from now
    if (!nextRun) {
      return {
        nextRun: calculateNextRun(trigger.schedule, now, occurrenceCount),
        occurrenceCount,
      }
    }

    // If nextRun is in the future, keep it
    if (new Date(nextRun) >= now) {
      return { nextRun, occurrenceCount }
    }

    // nextRun is in the past - skip missed occurrences
    const maxIterations = 1000 // Prevent infinite loop
    let iterations = 0
    let skippedCount = 0

    while (iterations < maxIterations) {
      // Count this as a missed occurrence
      occurrenceCount++
      skippedCount++

      // Calculate next occurrence with updated counter
      nextRun = calculateNextRun(trigger.schedule, now, occurrenceCount)

      // If null, trigger is expired
      if (!nextRun) {
        if (skippedCount > 0) {
          console.log(
            `[TRIGGER] Trigger "${trigger.name}" (${trigger.id}) expired after skipping ${skippedCount} missed occurrence(s)`
          )
        }
        return { nextRun: null, occurrenceCount }
      }

      // If future date found, return it
      if (new Date(nextRun) >= now) {
        if (skippedCount > 0) {
          console.log(
            `[TRIGGER] Trigger "${trigger.name}" (${trigger.id}) skipped ${skippedCount} missed occurrence(s), next run: ${nextRun}`
          )
        }
        return { nextRun, occurrenceCount }
      }

      iterations++
    }

    // Safety: if we couldn't find a future date, consider expired
    console.warn(`[TRIGGER] Could not find future nextRun for trigger ${trigger.id} after ${maxIterations} iterations`)
    return { nextRun: null, occurrenceCount }
  }

  /**
   * Get triggers directory path for a project
   */
  private getTriggersDir(projectName: string): string {
    return path.join(this.codayConfigDir, 'projects', projectName, 'triggers')
  }

  /**
   * Get trigger file path
   */
  private getTriggerFilePath(projectName: string, triggerId: string): string {
    return path.join(this.getTriggersDir(projectName), `${triggerId}.yml`)
  }

  /**
   * Find project name for a trigger ID
   */
  private findProjectForTrigger(triggerId: string): string | null {
    const projectsPath = path.join(this.codayConfigDir, 'projects')

    if (!fs.existsSync(projectsPath)) {
      return null
    }

    const projectDirs = fs
      .readdirSync(projectsPath, { withFileTypes: true })
      .filter((dirent) => dirent.isDirectory())
      .map((dirent) => dirent.name)

    for (const projectName of projectDirs) {
      const triggerPath = this.getTriggerFilePath(projectName, triggerId)
      if (fs.existsSync(triggerPath)) {
        return projectName
      }
    }

    return null
  }

  // ==================== CRUD Operations ====================

  /**
   * List all triggers for a project owned by a specific user
   * @param projectName - Project name
   * @param username - User to filter triggers by
   * @returns List of triggers owned by the user
   */
  async listTriggers(projectName: string, username: string): Promise<TriggerInfo[]> {
    const triggers = await this.loadProjectTriggers(projectName)

    // Filter by user ownership
    return triggers
      .filter((trigger) => trigger.createdBy === username)
      .map((trigger) => ({
        id: trigger.id,
        name: trigger.name,
        enabled: trigger.enabled,
        webhookUuid: trigger.webhookUuid,
        schedule: trigger.schedule,
        parameters: trigger.parameters,
        lastRun: trigger.lastRun,
        nextRun: trigger.nextRun,
      }))
  }

  /**
   * Get a specific trigger with ownership verification
   * @param projectName - Project name
   * @param triggerId - Trigger ID
   * @param username - User requesting the trigger (optional, for ownership check)
   * @returns Trigger if found and owned by user, null otherwise
   */
  async getTrigger(projectName: string, triggerId: string, username?: string): Promise<Trigger | null> {
    const filePath = this.getTriggerFilePath(projectName, triggerId)

    if (!fs.existsSync(filePath)) {
      return null
    }

    try {
      const content = fs.readFileSync(filePath, 'utf-8')
      const trigger = yaml.parse(content) as Trigger

      // Ensure nextRun is up to date, skipping missed occurrences
      const result = this.calculateNextRunSkippingMissed(trigger)
      trigger.nextRun = result.nextRun
      trigger.occurrenceCount = result.occurrenceCount

      // If username provided, verify ownership
      if (username && trigger.createdBy !== username) {
        return null // User doesn't own this trigger
      }

      return trigger
    } catch (error) {
      console.error(`[TRIGGER] Failed to load trigger ${triggerId}:`, error)
      return null
    }
  }

  /**
   * Create a new trigger
   */
  async createTrigger(
    projectName: string,
    data: {
      name: string
      webhookUuid: string
      schedule: IntervalSchedule
      parameters?: Record<string, unknown>
      enabled?: boolean
    },
    username: string
  ): Promise<Trigger> {
    // Validate schedule
    const validation = this.validateSchedule(data.schedule)
    if (!validation.valid) {
      throw new Error(`Invalid schedule: ${validation.error}`)
    }

    // Verify webhook exists (use getByUuid to find it across all projects)
    const webhookResult = await this.webhookService.getByUuid(data.webhookUuid)
    if (!webhookResult) {
      throw new Error(`Webhook not found: ${data.webhookUuid}`)
    }

    const trigger: Trigger = {
      id: randomUUID(),
      name: data.name,
      enabled: data.enabled ?? true,
      webhookUuid: data.webhookUuid,
      schedule: data.schedule,
      parameters: data.parameters,
      createdBy: username,
      createdAt: new Date().toISOString(),
      nextRun: calculateNextRun(data.schedule, new Date(), 0),
      occurrenceCount: 0,
    }

    await this.saveTrigger(trigger, projectName)

    // Add to in-memory cache
    this.triggers.set(trigger.id, trigger)

    console.log(`[TRIGGER] Created trigger "${trigger.name}" (${trigger.id}) for project ${projectName}`)

    return trigger
  }

  /**
   * Update a trigger with ownership verification
   * @param projectName - Project name
   * @param triggerId - Trigger ID
   * @param updates - Fields to update
   * @param username - User requesting the update
   * @returns Updated trigger
   * @throws Error if trigger not found or user doesn't own it
   */
  async updateTrigger(
    projectName: string,
    triggerId: string,
    updates: {
      name?: string
      enabled?: boolean
      webhookUuid?: string
      schedule?: IntervalSchedule
      parameters?: Record<string, unknown>
    },
    username: string
  ): Promise<Trigger> {
    const trigger = await this.getTrigger(projectName, triggerId, username)

    if (!trigger) {
      throw new Error(`Trigger not found or access denied: ${triggerId}`)
    }

    // Update fields
    if (updates.name !== undefined) trigger.name = updates.name
    if (updates.enabled !== undefined) trigger.enabled = updates.enabled
    if (updates.parameters !== undefined) trigger.parameters = updates.parameters

    // If webhook changed, verify it exists (use getByUuid to find it across all projects)
    if (updates.webhookUuid !== undefined) {
      const webhookResult = await this.webhookService.getByUuid(updates.webhookUuid)
      if (!webhookResult) {
        throw new Error(`Webhook not found: ${updates.webhookUuid}`)
      }
      trigger.webhookUuid = updates.webhookUuid
    }

    // If schedule changed, validate and recalculate nextRun
    if (updates.schedule !== undefined) {
      const validation = this.validateSchedule(updates.schedule)
      if (!validation.valid) {
        throw new Error(`Invalid schedule: ${validation.error}`)
      }
      trigger.schedule = updates.schedule
      trigger.occurrenceCount = 0 // Reset count on schedule change
      trigger.nextRun = calculateNextRun(updates.schedule, new Date(), 0)
    }

    await this.saveTrigger(trigger, projectName)

    // Update in-memory cache
    this.triggers.set(trigger.id, trigger)

    console.log(`[TRIGGER] Updated trigger "${trigger.name}" (${trigger.id})`)

    return trigger
  }

  /**
   * Delete a trigger with ownership verification
   * @param projectName - Project name
   * @param triggerId - Trigger ID
   * @param username - User requesting the deletion
   * @returns true if deleted, false if not found or access denied
   */
  async deleteTrigger(projectName: string, triggerId: string, username: string): Promise<boolean> {
    const trigger = await this.getTrigger(projectName, triggerId, username)

    if (!trigger) {
      return false // Not found or access denied
    }

    const filePath = this.getTriggerFilePath(projectName, triggerId)

    fs.unlinkSync(filePath)

    // Remove from in-memory cache
    this.triggers.delete(triggerId)

    console.log(`[TRIGGER] Deleted trigger ${triggerId}`)

    return true
  }

  /**
   * Enable a trigger with ownership verification
   */
  async enableTrigger(projectName: string, triggerId: string, username: string): Promise<Trigger> {
    return this.updateTrigger(projectName, triggerId, { enabled: true }, username)
  }

  /**
   * Disable a trigger with ownership verification
   */
  async disableTrigger(projectName: string, triggerId: string, username: string): Promise<Trigger> {
    return this.updateTrigger(projectName, triggerId, { enabled: false }, username)
  }

  /**
   * Manually execute a trigger now (for testing) with ownership verification
   * @param projectName - Project name
   * @param triggerId - Trigger ID
   * @param username - User requesting the execution
   * @returns Thread ID created by webhook execution
   * @throws Error if trigger not found or user doesn't own it
   */
  async runTriggerNow(projectName: string, triggerId: string, username: string): Promise<string> {
    const trigger = await this.getTrigger(projectName, triggerId, username)

    if (!trigger) {
      throw new Error(`Trigger not found or access denied: ${triggerId}`)
    }

    const threadId = await this.executeTriggerInternal(trigger, 'manual')

    // Update lastRun but don't change nextRun (keep scheduled time)
    trigger.lastRun = new Date().toISOString()
    await this.saveTrigger(trigger, projectName)
    this.triggers.set(trigger.id, trigger)

    // Log execution via CodayLogger
    this.logger.logTriggerExecution({
      triggerId: trigger.id,
      triggerName: trigger.name,
      webhookUuid: trigger.webhookUuid,
      projectName,
      success: true,
      threadId,
    })

    return threadId
  }

  /**
   * Save trigger to disk
   */
  private async saveTrigger(trigger: Trigger, projectName?: string | null): Promise<void> {
    // Find project if not provided
    if (!projectName) {
      projectName = this.findProjectForTrigger(trigger.id)
      if (!projectName) {
        throw new Error(`Cannot find project for trigger ${trigger.id}`)
      }
    }

    const triggersDir = this.getTriggersDir(projectName)
    fs.mkdirSync(triggersDir, { recursive: true })

    const filePath = this.getTriggerFilePath(projectName, trigger.id)
    const content = yaml.stringify(trigger)

    fs.writeFileSync(filePath, content, 'utf-8')
  }
}

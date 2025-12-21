import * as fs from 'fs'
import * as path from 'path'
import * as yaml from 'yaml'
import { randomUUID } from 'node:crypto'
import type { Trigger, TriggerInfo } from '../model/trigger'
import type { CodayLogger } from '@coday/service/coday-logger'
import type { WebhookService } from './webhook.service'
import { validateIntervalSchedule, calculateNextRun, shouldExecuteNow } from '../util/interval-schedule.utils'
import type { IntervalSchedule } from '../model/trigger'

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
  private triggers: Map<string, Trigger> = new Map()
  private checkInterval?: NodeJS.Timeout
  private readonly CHECK_INTERVAL_MS = 60000 // 1 minute

  constructor(
    private logger: CodayLogger,
    private webhookService: WebhookService,
    private webhooksDir: string
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

    const projectsDir = path.dirname(this.webhooksDir) // Get .coday directory
    const projectsPath = path.join(projectsDir, 'projects')

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

        // Calculate nextRun if not present or if in the past
        if (!trigger.nextRun || (trigger.nextRun && new Date(trigger.nextRun) < new Date())) {
          trigger.nextRun = calculateNextRun(trigger.schedule, new Date(), trigger.occurrenceCount || 0)
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
      if (shouldExecuteNow(trigger.schedule, trigger.nextRun || null, trigger.occurrenceCount || 0)) {
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

    // Get webhook to verify it exists
    const webhook = await this.webhookService.get(trigger.webhookUuid)
    if (!webhook) {
      throw new Error(`Webhook not found: ${trigger.webhookUuid}`)
    }

    // Execute webhook with trigger parameters
    // Use webhook's createdBy as username for trigger execution
    const result = await this.webhookService.executeWebhook(
      trigger.webhookUuid,
      trigger.parameters || {},
      webhook.createdBy,
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

    let success = false
    let threadId: string | undefined
    let error: string | undefined

    try {
      threadId = await this.executeTriggerInternal(trigger, 'scheduled')
      success = true
    } catch (err) {
      success = false
      error = err instanceof Error ? err.message : String(err)
      console.error(`[TRIGGER] Trigger "${trigger.name}" failed:`, err)
    }

    // Update trigger state
    trigger.lastRun = executedAt
    trigger.occurrenceCount = (trigger.occurrenceCount || 0) + 1
    trigger.nextRun = calculateNextRun(trigger.schedule, new Date(), trigger.occurrenceCount)

    // Save updated trigger
    await this.saveTrigger(trigger)

    // Log execution result via CodayLogger
    this.logger.logTriggerExecution({
      triggerId: trigger.id,
      triggerName: trigger.name,
      webhookUuid: trigger.webhookUuid,
      projectName: this.findProjectForTrigger(trigger.id) || 'unknown',
      success,
      threadId,
      error,
    })

    console.log(`[TRIGGER] Next execution for "${trigger.name}": ${trigger.nextRun}`)
  }

  /**
   * Public method to validate interval schedules (used by routes)
   * Delegates to interval-schedule.utils for actual validation
   */
  validateSchedule(schedule: IntervalSchedule): { valid: boolean; error?: string } {
    return validateIntervalSchedule(schedule)
  }

  /**
   * Get triggers directory path for a project
   */
  private getTriggersDir(projectName: string): string {
    const codayDir = path.dirname(this.webhooksDir)
    return path.join(codayDir, 'projects', projectName, 'triggers')
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
    const codayDir = path.dirname(this.webhooksDir)
    const projectsPath = path.join(codayDir, 'projects')

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

      // Ensure nextRun is up to date
      if (!trigger.nextRun || (trigger.nextRun && new Date(trigger.nextRun) < new Date())) {
        trigger.nextRun = calculateNextRun(trigger.schedule, new Date(), trigger.occurrenceCount || 0)
      }

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

    // Verify webhook exists
    const webhook = await this.webhookService.get(data.webhookUuid)
    if (!webhook) {
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

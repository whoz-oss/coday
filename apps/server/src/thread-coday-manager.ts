import { Response } from 'express'
import { CodayOptions } from '@coday/options'
import { CodayLogger } from '@coday/service/coday-logger'
import { WebhookService } from '@coday/service/webhook.service'
import { debugLog } from './log'
import { ThreadService } from './services/thread.service'
import { ProjectService } from './services/project.service'
import { ThreadInstance } from './thread-instance/thread-instance.interface'
import { LocalThreadInstance } from './thread-instance/local-thread-instance'
import { AgentOSThreadInstance } from './thread-instance/agentos-thread-instance'
import { McpInstancePool } from '@coday/integration/mcp/mcp-instance-pool'

/**
 * Legacy type alias for backward compatibility
 * @deprecated Use IThreadInstance instead
 */
export type ThreadCodayInstance = ThreadInstance

/**
 * Manages thread execution instances indexed by threadId.
 * Supports both local Coday and remote AgentOS backends.
 */
export class ThreadCodayManager {
  private readonly instances: Map<string, ThreadInstance> = new Map()
  private readonly useAgentOS: boolean
  private readonly heartbeatInterval: NodeJS.Timeout

  // AgentOS configuration (POC - hardcoded defaults)
  private static readonly AGENTOS_URL = process.env.AGENTOS_URL ?? 'http://localhost:8080'
  private static readonly AGENTOS_DEFAULT_PROJECT_ID = '00000000-0000-0000-0000-000000000000'

  static readonly HEARTBEAT_INTERVAL = 30_000 // 30 seconds

  constructor(
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService,
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly mcpPool: McpInstancePool
  ) {
    // Read AgentOS toggle from environment (only variable needed for POC)
    this.useAgentOS = process.env.USE_AGENTOS === 'true'

    debugLog('THREAD_CODAY_MANAGER', `Backend: ${this.useAgentOS ? 'AgentOS' : 'Local Coday'}`)
    if (this.useAgentOS) {
      debugLog('THREAD_CODAY_MANAGER', `AgentOS URL: ${ThreadCodayManager.AGENTOS_URL}`)
      debugLog(
        'THREAD_CODAY_MANAGER',
        `AgentOS Default Project ID: ${ThreadCodayManager.AGENTOS_DEFAULT_PROJECT_ID} (POC - single project)`
      )
    }

    // Start global heartbeat mechanism
    this.heartbeatInterval = setInterval(() => this.sendHeartbeats(), ThreadCodayManager.HEARTBEAT_INTERVAL)
    debugLog('THREAD_CODAY_MANAGER', 'Heartbeat mechanism started')
  }

  /**
   * Send heartbeats to all active instances with SSE connections
   */
  private sendHeartbeats(): void {
    for (const instance of this.instances.values()) {
      if (instance.connectionCount > 0) {
        instance.sendHeartbeat()
      }
    }
  }

  /**
   * Handle instance timeout (called by ThreadCodayInstance)
   */
  private handleInstanceTimeout = async (threadId: string): Promise<void> => {
    debugLog('THREAD_CODAY_MANAGER', `Handling timeout for thread ${threadId}`)
    await this.cleanup(threadId)
  }

  /**
   * Get or create a thread instance for a specific thread
   * @param threadId Thread identifier
   * @param projectName Project name
   * @param username User identifier
   * @param options Coday options (must include project and thread)
   * @param response SSE response object
   * @returns ThreadInstance
   */
  getOrCreate(
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions,
    response: Response
  ): ThreadInstance {
    let instance = this.instances.get(threadId)

    if (!instance) {
      debugLog('THREAD_CODAY_MANAGER', `Creating new instance for thread ${threadId}`)

      if (this.useAgentOS) {
        instance = new AgentOSThreadInstance(
          threadId,
          projectName,
          username,
          ThreadCodayManager.AGENTOS_URL,
          ThreadCodayManager.AGENTOS_DEFAULT_PROJECT_ID,
          this.handleInstanceTimeout
        )
      } else {
        instance = new LocalThreadInstance(
          threadId,
          projectName,
          username,
          options,
          this.logger,
          this.webhookService,
          this.projectService,
          this.threadService,
          this.mcpPool,
          this.handleInstanceTimeout
        )
      }

      this.instances.set(threadId, instance)
    } else {
      debugLog('THREAD_CODAY_MANAGER', `Reusing existing instance for thread ${threadId}`)
    }

    // Add this SSE connection to the instance
    instance.addConnection(response)

    return instance
  }

  /**
   * Create a thread instance for a specific thread without SSE connection
   * Used for webhook and other non-SSE scenarios
   * @param threadId Thread identifier
   * @param projectName Project name
   * @param username User identifier
   * @param options Coday options (must include project and thread)
   * @returns ThreadInstance
   */
  createWithoutConnection(
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions
  ): ThreadInstance {
    let instance = this.instances.get(threadId)

    if (!instance) {
      debugLog('THREAD_CODAY_MANAGER', `Creating new instance for thread ${threadId} (no SSE connection)`)

      if (this.useAgentOS) {
        instance = new AgentOSThreadInstance(
          threadId,
          projectName,
          username,
          ThreadCodayManager.AGENTOS_URL,
          ThreadCodayManager.AGENTOS_DEFAULT_PROJECT_ID,
          this.handleInstanceTimeout
        )
      } else {
        instance = new LocalThreadInstance(
          threadId,
          projectName,
          username,
          options,
          this.logger,
          this.webhookService,
          this.projectService,
          this.threadService,
          this.mcpPool,
          this.handleInstanceTimeout
        )
      }

      instance.markAsOneshot() // Mark as oneshot for shorter timeout
      this.instances.set(threadId, instance)
    } else {
      debugLog('THREAD_CODAY_MANAGER', `Reusing existing instance for thread ${threadId}`)
    }

    return instance
  }

  /**
   * Get an existing instance by threadId
   * @param threadId Thread identifier
   * @returns IThreadInstance or undefined
   */
  get(threadId: string): ThreadInstance | undefined {
    return this.instances.get(threadId)
  }

  /**
   * Remove a connection from a thread instance
   * If no connections remain, the instance is kept alive for potential reconnections
   * @param threadId Thread identifier
   * @param response Response object to remove
   */
  removeConnection(threadId: string, response: Response): void {
    const instance = this.instances.get(threadId)
    if (instance) {
      instance.removeConnection(response)

      // Note: We intentionally keep the instance alive even with 0 connections
      // for potential reconnections. Cleanup will be handled by a future
      // timeout mechanism or explicit cleanup call.

      if (instance.connectionCount === 0) {
        debugLog('THREAD_CODAY', `Thread ${threadId} has no active connections but instance kept alive`)
      }
    }
  }

  /**
   * Stop the Coday run for a specific thread
   * @param threadId Thread identifier
   */
  stop(threadId: string): void {
    const instance = this.instances.get(threadId)
    if (instance) {
      instance.stop()
    }
  }

  /**
   * Cleanup and remove a thread instance
   * @param threadId Thread identifier
   */
  async cleanup(threadId: string): Promise<void> {
    const instance = this.instances.get(threadId)
    if (instance) {
      await instance.cleanup()
      this.instances.delete(threadId)
      debugLog('THREAD_CODAY', `Removed instance for thread ${threadId}`)
    }
  }

  /**
   * Get statistics about managed instances
   */
  getStats(): { total: number; withConnections: number } {
    let withConnections = 0

    for (const instance of this.instances.values()) {
      if (instance.connectionCount > 0) {
        withConnections++
      }
    }

    return {
      total: this.instances.size,
      withConnections,
    }
  }

  /**
   * Shutdown all thread instances
   * Used during graceful server shutdown
   */
  async shutdown(): Promise<void> {
    debugLog('THREAD_CODAY_MANAGER', `Shutting down ${this.instances.size} thread instances`)

    // Clear heartbeat interval
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval)
      debugLog('THREAD_CODAY_MANAGER', 'Heartbeat mechanism stopped')
    }

    const cleanupPromises: Promise<void>[] = []
    for (const [threadId, instance] of this.instances.entries()) {
      debugLog('THREAD_CODAY_MANAGER', `Cleaning up thread ${threadId}`)
      cleanupPromises.push(instance.cleanup())
    }

    await Promise.all(cleanupPromises)
    this.instances.clear()
    debugLog('THREAD_CODAY_MANAGER', 'All thread instances cleaned up')
  }
}

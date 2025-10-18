import { Response } from 'express'
import { Coday } from '@coday/core'
import { ServerInteractor } from '@coday/model/server-interactor'
import { CodayOptions } from '@coday/options'
import { UserService } from '@coday/service/user.service'
import { ProjectService } from '@coday/service/project.service'
import { IntegrationService } from '@coday/service/integration.service'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { MemoryService } from '@coday/service/memory.service'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { CodayLogger } from '@coday/service/coday-logger'
import { WebhookService } from '@coday/service/webhook.service'
import { debugLog } from './log'

/**
 * Represents a Coday instance associated with a specific thread.
 * Manages the lifecycle and SSE connections for a single thread.
 */
class ThreadCodayInstance {
  private connections: Set<Response> = new Set()
  coday?: Coday

  constructor(
    public readonly threadId: string,
    public readonly projectName: string,
    public readonly username: string,
    private readonly options: CodayOptions,
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService
  ) {}

  /**
   * Add an SSE connection to this thread instance
   * @param response Express response object for SSE
   */
  addConnection(response: Response): void {
    this.connections.add(response)
    debugLog('THREAD_CODAY', `Added SSE connection to thread ${this.threadId} (total: ${this.connections.size})`)
  }

  /**
   * Remove an SSE connection from this thread instance
   * @param response Express response object to remove
   */
  removeConnection(response: Response): void {
    this.connections.delete(response)
    debugLog(
      'THREAD_CODAY',
      `Removed SSE connection from thread ${this.threadId} (remaining: ${this.connections.size})`
    )
  }

  /**
   * Get the number of active SSE connections
   */
  get connectionCount(): number {
    return this.connections.size
  }

  /**
   * Start the Coday instance for this thread
   * @returns true if new instance was created, false if already running
   */
  startCoday(): boolean {
    if (this.coday) {
      debugLog('THREAD_CODAY', `Coday already running for thread ${this.threadId}`)
      return false
    }

    debugLog('THREAD_CODAY', `Creating Coday instance for thread ${this.threadId}`)

    // Create services for this Coday instance
    const interactor = new ServerInteractor(this.threadId)
    const user = new UserService(this.options.configDir, this.username, interactor)
    const project = new ProjectService(interactor, this.options.configDir)
    const integration = new IntegrationService(project, user)
    const integrationConfig = new IntegrationConfigService(user, project, interactor)
    const memory = new MemoryService(project, user)
    const mcp = new McpConfigService(user, project, interactor)

    // Subscribe to interactor events and broadcast to all SSE connections
    interactor.events.subscribe((event) => {
      this.broadcastEvent(event)
    })

    // Create Coday instance
    this.coday = new Coday(interactor, this.options, {
      user,
      project,
      integration,
      integrationConfig,
      memory,
      mcp,
      logger: this.logger,
      webhook: this.webhookService,
    })

    // Start Coday run
    this.coday
      .run()
      .catch((error) => {
        debugLog('THREAD_CODAY', `Error during Coday run for thread ${this.threadId}:`, error)
        console.error(`Coday run failed for thread ${this.threadId}:`, error)
      })
      .finally(() => {
        debugLog('THREAD_CODAY', `Coday run finished for thread ${this.threadId}`)
        // Note: We keep the instance alive for potential reconnections
      })

    return true
  }

  /**
   * Broadcast an event to all connected SSE clients
   * @param event Event to broadcast
   */
  private broadcastEvent(event: any): void {
    const data = `data: ${JSON.stringify(event)}\n\n`

    // Send to all active connections
    for (const connection of this.connections) {
      try {
        if (!connection.writableEnded) {
          connection.write(data)
        } else {
          // Connection is closed, remove it
          this.connections.delete(connection)
        }
      } catch (error) {
        debugLog('THREAD_CODAY', `Error broadcasting to connection:`, error)
        this.connections.delete(connection)
      }
    }
  }

  /**
   * Stop the current Coday run
   */
  stop(): void {
    this.coday?.stop()
  }

  /**
   * Cleanup and destroy the Coday instance
   */
  async cleanup(): Promise<void> {
    debugLog('THREAD_CODAY', `Cleaning up thread ${this.threadId}`)

    // Close all SSE connections
    for (const connection of this.connections) {
      try {
        connection.end()
      } catch (error) {
        debugLog('THREAD_CODAY', `Error closing connection:`, error)
      }
    }
    this.connections.clear()

    // Kill Coday instance
    if (this.coday) {
      await this.coday.kill()
      this.coday = undefined
    }
  }
}

/**
 * Manages Coday instances indexed by threadId.
 * Provides a registry of active thread-based Coday instances with SSE connection management.
 */
export class ThreadCodayManager {
  private instances: Map<string, ThreadCodayInstance> = new Map()

  constructor(
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService
  ) {}

  /**
   * Get or create a Coday instance for a specific thread
   * @param threadId Thread identifier
   * @param projectName Project name
   * @param username User identifier
   * @param options Coday options (must include project and thread)
   * @param response SSE response object
   * @returns ThreadCodayInstance
   */
  getOrCreate(
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions,
    response: Response
  ): ThreadCodayInstance {
    let instance = this.instances.get(threadId)

    if (!instance) {
      debugLog('THREAD_CODAY', `Creating new instance for thread ${threadId}`)
      instance = new ThreadCodayInstance(threadId, projectName, username, options, this.logger, this.webhookService)
      this.instances.set(threadId, instance)
    } else {
      debugLog('THREAD_CODAY', `Reusing existing instance for thread ${threadId}`)
    }

    // Add this SSE connection to the instance
    instance.addConnection(response)

    return instance
  }

  /**
   * Get an existing instance by threadId
   * @param threadId Thread identifier
   * @returns ThreadCodayInstance or undefined
   */
  get(threadId: string): ThreadCodayInstance | undefined {
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
   * Shutdown all thread instances
   * Used during graceful server shutdown
   */
  async shutdown(): Promise<void> {
    debugLog('THREAD_CODAY', `Shutting down ${this.instances.size} thread instances`)

    const cleanupPromises: Promise<void>[] = []
    for (const [threadId, instance] of this.instances.entries()) {
      debugLog('THREAD_CODAY', `Cleaning up thread ${threadId}`)
      cleanupPromises.push(instance.cleanup())
    }

    await Promise.all(cleanupPromises)
    this.instances.clear()
    debugLog('THREAD_CODAY', 'All thread instances cleaned up')
  }
}

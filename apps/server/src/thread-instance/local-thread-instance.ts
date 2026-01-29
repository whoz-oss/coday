import { Response } from 'express'
import { Coday } from '@coday/core'
import { ServerInteractor } from '@coday/model/server-interactor'
import { CodayOptions } from '@coday/options'
import { UserService } from '@coday/service/user.service'
import { ProjectStateService } from '@coday/service/project-state.service'
import { IntegrationService } from '@coday/service/integration.service'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { MemoryService } from '@coday/service/memory.service'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { CodayLogger } from '@coday/service/coday-logger'
import { WebhookService } from '@coday/service/webhook.service'
import { HeartBeatEvent } from '@coday/coday-events'
import { debugLog } from '../log'
import { ThreadService } from '../services/thread.service'
import { ProjectService } from '../services/project.service'
import { ThreadInstance } from './thread-instance.interface'
import { McpInstancePool } from '@coday/integration/mcp/mcp-instance-pool'

/**
 * Local Coday execution instance for a thread.
 * Manages the lifecycle and SSE connections for a single thread using local Coday runtime.
 */
export class LocalThreadInstance implements ThreadInstance {
  private readonly connections: Set<Response> = new Set()
  private lastActivity: number = Date.now()
  private disconnectTimeout?: NodeJS.Timeout
  private inactivityTimeout?: NodeJS.Timeout
  private isOneshot: boolean = false
  coday?: Coday

  // Timeouts configuration
  static readonly DISCONNECT_TIMEOUT = 5 * 60 * 1000 // 5 minutes after last connection closed
  static readonly INTERACTIVE_TIMEOUT = 8 * 60 * 60 * 1000 // 8 hours for interactive sessions
  static readonly ONESHOT_TIMEOUT = 30 * 60 * 1000 // 30 minutes for oneshot (webhook) sessions

  constructor(
    public readonly threadId: string,
    public readonly projectName: string,
    public readonly username: string,
    private readonly options: CodayOptions,
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService,
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly mcpPool: McpInstancePool,
    private readonly onTimeout: (threadId: string) => void
  ) {
    // Start inactivity timeout
    this.resetInactivityTimeout()
  }

  get connectionCount(): number {
    return this.connections.size
  }

  addConnection(response: Response): void {
    if (this.connections.has(response)) {
      return
    }

    this.connections.add(response)
    this.updateActivity()
    this.isOneshot = false
    debugLog('LOCAL_THREAD', `Added SSE connection to thread ${this.threadId} (total: ${this.connections.size})`)

    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
      debugLog('LOCAL_THREAD', `Cleared disconnect timeout for thread ${this.threadId}`)
    }

    if (this.coday) {
      debugLog('LOCAL_THREAD', `Replaying thread history for new connection to ${this.threadId}`)
      this.replayThreadHistory(response)
    }
  }

  private async replayThreadHistory(response: Response): Promise<void> {
    if (!this.coday) return

    try {
      const thread = this.coday.context?.aiThread
      if (!thread) {
        debugLog('LOCAL_THREAD', `No thread found for replay in ${this.threadId}`)
        return
      }

      const result = await thread.getMessages(undefined, undefined)
      const messages = result.messages
      debugLog('LOCAL_THREAD', `Replaying ${messages.length} messages for thread ${this.threadId}`)

      for (const message of messages) {
        const data = `data: ${JSON.stringify(message)}\n\n`
        if (!response.writableEnded) {
          response.write(data)
        }
      }
    } catch (error) {
      debugLog('LOCAL_THREAD', `Error replaying thread history:`, error)
    }
  }

  removeConnection(response: Response): void {
    this.connections.delete(response)
    debugLog(
      'LOCAL_THREAD',
      `Removed SSE connection from thread ${this.threadId} (remaining: ${this.connections.size})`
    )

    if (this.connections.size === 0 && !this.disconnectTimeout) {
      debugLog('LOCAL_THREAD', `No connections remaining for thread ${this.threadId}, starting disconnect timeout`)
      this.disconnectTimeout = setTimeout(() => {
        debugLog('LOCAL_THREAD', `Disconnect timeout reached for thread ${this.threadId}`)
        this.onTimeout(this.threadId)
      }, LocalThreadInstance.DISCONNECT_TIMEOUT)
    }
  }

  private updateActivity(): void {
    this.lastActivity = Date.now()
    this.resetInactivityTimeout()
  }

  private resetInactivityTimeout(): void {
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
    }

    const timeout = this.isOneshot ? LocalThreadInstance.ONESHOT_TIMEOUT : LocalThreadInstance.INTERACTIVE_TIMEOUT

    this.inactivityTimeout = setTimeout(() => {
      const inactiveTime = Date.now() - this.lastActivity
      debugLog(
        'LOCAL_THREAD',
        `Inactivity timeout reached for thread ${this.threadId} after ${Math.round(inactiveTime / 1000)}s`
      )
      this.onTimeout(this.threadId)
    }, timeout)
  }

  markAsOneshot(): void {
    this.isOneshot = true
    this.resetInactivityTimeout()
  }

  getInactiveTime(): number {
    return Date.now() - this.lastActivity
  }

  async prepareInstance(): Promise<boolean> {
    this.updateActivity()
    if (this.coday) {
      debugLog('LOCAL_THREAD', `Coday already running for thread ${this.threadId}`)
      return false
    }

    debugLog('LOCAL_THREAD', `Creating Coday instance for thread ${this.threadId}`)

    const interactor = new ServerInteractor(this.threadId)
    const user = new UserService(this.options.configDir, this.username, interactor)
    const project = new ProjectStateService(interactor, this.projectService, this.options.configDir)
    const integration = new IntegrationService(project, user)
    const integrationConfig = new IntegrationConfigService(user, project, interactor)
    const memory = new MemoryService(project, user)
    const mcp = new McpConfigService(user, project, interactor)

    interactor.events.subscribe((event) => {
      this.broadcastEvent(event)
    })

    this.coday = new Coday(interactor, this.options, {
      user,
      project,
      integration,
      integrationConfig,
      memory,
      mcp,
      mcpPool: this.mcpPool,
      thread: this.threadService,
      logger: this.logger,
      webhook: this.webhookService,
    })

    return true
  }

  async startExecution(): Promise<boolean> {
    this.updateActivity()
    const wasCreated = await this.prepareInstance()

    if (!this.coday) {
      return false
    }

    this.coday
      .run()
      .catch((error) => {
        debugLog('LOCAL_THREAD', `Error during Coday run for thread ${this.threadId}:`, error)
        console.error(`Coday run failed for thread ${this.threadId}:`, error)
      })
      .finally(() => {
        debugLog('LOCAL_THREAD', `Coday run finished for thread ${this.threadId}`)
      })

    return wasCreated
  }

  sendHeartbeat(): void {
    if (this.connections.size === 0) {
      return
    }

    try {
      const heartBeatEvent = new HeartBeatEvent({})
      this.broadcastEvent(heartBeatEvent)
    } catch (error) {
      debugLog('LOCAL_THREAD', `Error sending heartbeat for thread ${this.threadId}:`, error)
    }
  }

  private broadcastEvent(event: any): void {
    const data = `data: ${JSON.stringify(event)}\n\n`

    for (const connection of this.connections) {
      try {
        if (!connection.writableEnded) {
          connection.write(data)
        } else {
          this.connections.delete(connection)
        }
      } catch (error) {
        debugLog('LOCAL_THREAD', `Error broadcasting to connection:`, error)
        this.connections.delete(connection)
      }
    }
  }

  stop(): void {
    this.coday?.stop()
  }

  async cleanup(): Promise<void> {
    debugLog('LOCAL_THREAD', `Cleaning up thread ${this.threadId}`)

    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
    }
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
      this.inactivityTimeout = undefined
    }

    for (const connection of this.connections) {
      try {
        connection.end()
      } catch (error) {
        debugLog('LOCAL_THREAD', `Error closing connection:`, error)
      }
    }
    this.connections.clear()

    if (this.coday) {
      try {
        await this.coday.kill()
      } catch (error) {
        debugLog('LOCAL_THREAD', `Error during Coday kill:`, error)
      }
      this.coday = undefined
    }
  }
}

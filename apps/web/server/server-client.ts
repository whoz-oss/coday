import { Response } from 'express'
import { ServerInteractor } from '@coday/model/server-interactor'
import { Coday } from '@coday/core'

import { HeartBeatEvent } from '@coday/coday-events'
import { catchError, filter, first, map, Observable, of, Subscription, timeout } from 'rxjs'
import { CodayOptions } from '@coday/options'
import { UserService } from '@coday/service/user.service'
import { ProjectService } from '@coday/service/project.service'
import { IntegrationService } from '@coday/service/integration.service'
import { IntegrationConfigService } from '@coday/service/integration-config.service'
import { MemoryService } from '@coday/service/memory.service'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { WebhookService } from '@coday/service/webhook.service'
import { CodayLogger } from '@coday/service/coday-logger'
import { SessionState } from '@coday/model/session-state'
import { firstValueFrom } from 'rxjs'
import { debugLog } from './log'
import { ThreadSummary } from '@coday/ai-thread/ai-thread.types'

export class ServerClient {
  private readonly heartbeatInterval: NodeJS.Timeout
  private terminationTimeout?: NodeJS.Timeout
  private lastConnected: number = Date.now()
  coday?: Coday

  static readonly SESSION_TIMEOUT = 8 * 60 * 60 * 1000 // 8 hours in milliseconds
  static readonly HEARTBEAT_INTERVAL = 10_000 // 10 seconds

  constructor(
    private readonly clientId: string,
    private response: Response | null,
    private readonly interactor: ServerInteractor,
    private readonly options: CodayOptions,
    private readonly username: string,
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService
  ) {
    // Subscribe to interactor events
    if (response) {
      this.subscription = this.interactor.events.subscribe((event) => {
        const data = `data: ${JSON.stringify(event)}\n\n`
        this.response?.write(data)
      })
    }
    this.heartbeatInterval = setInterval(() => this.sendHeartbeat(), ServerClient.HEARTBEAT_INTERVAL)
  }

  /**
   * Update client connection with new response object.
   * Called when client reconnects with same clientId.
   */
  private subscription?: Subscription

  updateLastConnection(): void {
    this.lastConnected = Date.now()
 
    if (this.terminationTimeout) {
      clearTimeout(this.terminationTimeout)
    }
    this.terminationTimeout = setTimeout(() => {
      const idleTime = Date.now() - this.lastConnected
        debugLog(
          'CLIENT',
          `Session expired for client ${this.clientId} after ${Math.round(idleTime / 1000)}s of inactivity`
        )
        this.cleanup()
    }, ServerClient.SESSION_TIMEOUT)
  }

  reconnect(response: Response): void {
    debugLog('CLIENT', `Client ${this.clientId} reconnecting`)
    this.response = response
    this.updateLastConnection()

    if (this.terminationTimeout) {
      debugLog('CLIENT', `Clearing termination timeout for client ${this.clientId}`)
      clearTimeout(this.terminationTimeout)
      delete this.terminationTimeout
    }

    // Replay thread messages if we have an active Coday instance
    if (this.coday) {
      debugLog('CLIENT', `Replaying messages for client ${this.clientId}`)
      this.coday.replay()
    } else {
      debugLog('CLIENT', `No Coday instance to replay for client ${this.clientId}`)
    }
  }

  /**
   * Start or resume Coday instance.
   * Returns true if new instance was created, false if existing instance was used.
   */
  startCoday(): boolean {
    debugLog('CODAY', `Attempting to start Coday for client ${this.clientId}`)
    if (this.coday) {
      debugLog('CODAY', `Coday already running for client ${this.clientId}`)
      return false
    }
    const user = new UserService(this.options.configDir, this.username, this.interactor)
    const project = new ProjectService(this.interactor, this.options.configDir)
    const integration = new IntegrationService(project, user)
    const integrationConfig = new IntegrationConfigService(user, project, this.interactor)
    const memory = new MemoryService(project, user)
    const mcp = new McpConfigService(user, project, this.interactor)

    debugLog('CODAY', `Creating new Coday instance for client ${this.clientId}`)
    this.coday = new Coday(this.interactor, this.options, {
      user,
      project,
      integration,
      integrationConfig,
      memory,
      mcp,
      logger: this.logger,
      webhook: this.webhookService,
    })
    this.coday.run().finally(() => {
      debugLog('CODAY', `Coday run finished for client ${this.clientId}`)
      this.terminate(true)
    })
    return true
  }

  /**
   * Terminate the client connection.
   * If immediate is true, cleanup everything now.
   * Otherwise schedule cleanup after SESSION_TIMEOUT.
   */
  terminate(immediate: boolean = false): void {
    debugLog('CLIENT', `Terminating client ${this.clientId} (immediate: ${immediate})`)

    // Clear heartbeat interval
    clearInterval(this.heartbeatInterval)
    this.response?.end()

    if (immediate) {
      debugLog('CLIENT', `Immediate cleanup for client ${this.clientId}`)
      this.cleanup()
      return
    }

    // Cleanup conversation resources but keep Coday alive for reconnection
    if (this.coday) {
      debugLog('CODAY', `Cleaning up conversation for client ${this.clientId}`)
      this.coday.cleanup().catch((error) => {
        debugLog('CODAY', `Error during conversation cleanup for client ${this.clientId}:`, error)
      })
    }
  }

  /**
   * Stop the current Coday run if any
   */
  stop(): void {
    this.coday?.stop()
  }

  /**
   * Check if client has been inactive longer than SESSION_TIMEOUT
   */
  isExpired(): boolean {
    return Date.now() - this.lastConnected >= ServerClient.SESSION_TIMEOUT
  }

  private sendHeartbeat(): void {
    try {
      const heartBeatEvent = new HeartBeatEvent({})
      this.interactor.sendEvent(heartBeatEvent)
    } catch (error) {
      debugLog('HEARTBEAT', `Error sending heartbeat to client ${this.clientId}:`, error)
      this.terminate()
    }
  }

  /**
   * Get the interactor instance for this client
   */
  getInteractor(): ServerInteractor {
    return this.interactor
  }

  /**
   * Get an event by its ID from the current thread
   * @param eventId The ID (timestamp) of the event to retrieve
   * @returns The event if found, undefined otherwise
   */
  getEventById(eventId: string): any {
    if (!this.coday?.context?.aiThread) {
      return undefined
    }

    return this.coday.context.aiThread.getEventById(eventId)
  }

  /**
   * Get the current session state including projects and threads information
   * @returns Promise<SessionState> The current session state
   */
  async getSessionState(): Promise<SessionState> {
    const sessionState: SessionState = {
      projects: {
        list: null,
        current: null,
        canCreate: true
      },
      threads: {
        list: null,
        current: null
      }
    }

    // Check if project is locked by options
    const isProjectLocked = this.options.project !== undefined
    const projectService = this.coday?.getServices()?.project

    if (projectService) {
      // Set project information
      if (isProjectLocked) {
        // Project is locked by options - only show the selected project
        sessionState.projects.canCreate = false
        const currentProject = projectService.selectedProject
        if (currentProject) {
          sessionState.projects.list = [{ name: currentProject.name }]
          sessionState.projects.current = currentProject.name
        } else {
          sessionState.projects.list = []
        }
      } else {
        // Project selection is open - show all available projects
        sessionState.projects.canCreate = true
        if (projectService.projects) {
          sessionState.projects.list = projectService.projects.map(name => ({ name }))
        }
        const currentProject = projectService.selectedProject
        sessionState.projects.current = currentProject?.name || null
      }

      // Set thread information (only if project is selected)
      const aiThreadService = this.coday?.aiThreadService
      if (sessionState.projects.current && aiThreadService) {
        try {
          const currentThread = aiThreadService.getCurrentThread()
          sessionState.threads.current = currentThread?.id || null

          // Get thread list
          const threadList = await firstValueFrom(aiThreadService.list()) as ThreadSummary[]
          sessionState.threads.list = threadList.map((thread: ThreadSummary) => ({
            id: thread.id,
            name: thread.name,
            modifiedDate: thread.modifiedDate
          }))
        } catch (error) {
          console.warn('Error fetching thread list:', error)
          // Keep threads as null on error
        }
      }
    }

    return sessionState
  }

  getThreadId(): Observable<string | undefined> {
    const source = this.coday?.aiThreadService?.activeThread

    if (!source) return of(undefined)

    return source.pipe(
      map((thread) => thread?.id),
      filter((id): id is string => !!id), // Filter out falsy values and type guard
      first(), // Take the first truthy value and complete
      timeout(5000), // Timeout after 5 seconds
      catchError(() => of(undefined)) // Return undefined on timeout or error
    )
  }

  /**
   * Complete cleanup of client resources.
   * Destroys the Coday instance and all associated resources.
   */
  private cleanup(): void {
    debugLog('CLIENT', `Starting full cleanup for client ${this.clientId}`)
    this.subscription?.unsubscribe()

    if (this.coday) {
      debugLog('CODAY', `Killing Coday instance for client ${this.clientId}`)
      this.coday.kill().catch((error) => {
        debugLog('CODAY', `Error during Coday kill for client ${this.clientId}:`, error)
      })
      delete this.coday
    }

    if (this.terminationTimeout) {
      debugLog('CLIENT', `Clearing termination timeout during cleanup for client ${this.clientId}`)
      clearTimeout(this.terminationTimeout)
      delete this.terminationTimeout
    }

    debugLog('CLIENT', `Full cleanup completed for client ${this.clientId}`)
  }
}

/**
 * Manages all active server clients
 */
export class ServerClientManager {
  private readonly clients: Map<string, ServerClient> = new Map()

  constructor(
    private readonly logger: CodayLogger,
    private readonly webhookService: WebhookService
  ) {}

  /**
   * Get or create a client for the given clientId
   */
  getOrCreate(clientId: string, response: Response | null, options: CodayOptions, username: string): ServerClient {
    const existingClient = this.clients.get(clientId)
    if (existingClient) {
      if (response) {
        existingClient.reconnect(response)
      }
      return existingClient
    }

    const interactor = new ServerInteractor(clientId)
    const client = new ServerClient(clientId, response, interactor, options, username, this.logger, this.webhookService)
    this.clients.set(clientId, client)
    return client
  }

  /**
   * Get a client by id if it exists
   */
  get(clientId: string): ServerClient | undefined {
    return this.clients.get(clientId)
  }

  /**
   * Remove a client from the manager
   */
  remove(clientId: string): void {
    this.clients.delete(clientId)
  }

  /**
   * Clean up expired clients
   */
  cleanupExpired(): void {
    for (const [clientId, client] of this.clients.entries()) {
      if (client.isExpired()) {
        client.terminate(true)
        this.clients.delete(clientId)
      }
    }
  }

  /**
   * Shutdown all clients and cleanup resources
   * Used during graceful server shutdown
   */
  shutdown(): void {
    debugLog('MANAGER', `Shutting down ${this.clients.size} clients`)
    
    for (const [clientId, client] of this.clients.entries()) {
      debugLog('MANAGER', `Terminating client ${clientId}`)
      client.terminate(true)
    }
    
    this.clients.clear()
    debugLog('MANAGER', 'All clients terminated')
  }
}

import { Response } from 'express'
import { Coday } from '@coday/core'
import { AiClientProvider } from '@coday/integrations-ai'
import {
  ServerInteractor,
  CodayOptions,
  CodayLogger,
  HeartBeatEvent,
  InviteEvent,
  InviteEventDefault,
  ChoiceEvent,
  ThreadUpdateEvent,
  OAuthCallbackEvent,
} from '@coday/model'
import { ProjectEventManager } from './project-event-manager'
import {
  UserService,
  ProjectStateService,
  IntegrationService,
  IntegrationConfigService,
  MemoryService,
  McpConfigService,
  PromptService,
  ProjectService,
  ThreadService,
} from '@coday/service'
import { ThreadPostProcessor } from './thread-post-processor'
import { debugLog } from './log'
import { McpInstancePool } from '@coday/mcp'
import { AgentService } from '@coday/agent'

/**
 * Represents a Coday instance associated with a specific thread.
 * Manages the lifecycle and SSE connections for a single thread.
 */
class ThreadCodayInstance {
  private readonly connections: Set<Response> = new Set()
  private lastActivity: number = Date.now()
  private inactivityTimeout?: NodeJS.Timeout
  private isOneshot: boolean = false
  private isReplaying: boolean = false
  coday?: Coday

  // Timeouts configuration
  static readonly INTERACTIVE_TIMEOUT = 8 * 60 * 60 * 1000 // 8 hours for interactive sessions
  static readonly ONESHOT_TIMEOUT = 30 * 60 * 1000 // 30 minutes for oneshot (webhook) sessions

  constructor(
    public readonly threadId: string,
    public readonly projectName: string,
    public readonly username: string,
    private readonly options: CodayOptions,
    private readonly logger: CodayLogger,
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly promptService: PromptService,
    private readonly mcpPool: McpInstancePool,
    private readonly onTimeout: (threadId: string) => void,
    private readonly projectEventManager: ProjectEventManager | undefined,
    private readonly setPendingInviteCb: (threadId: string) => void,
    private readonly hasPendingInviteCb: (threadId: string) => boolean
  ) {
    // Start inactivity timeout
    this.resetInactivityTimeout()
  }

  /**
   * Add an SSE connection to this thread instance
   * @param response Express response object for SSE
   */
  addConnection(response: Response): void {
    if (this.connections.has(response)) {
      // do nothing, connection already registered
      return
    }

    this.connections.add(response)
    this.updateActivity()
    this.isOneshot = false // Mark as interactive session
    debugLog('THREAD_CODAY', `Added SSE connection to thread ${this.threadId} (total: ${this.connections.size})`)

    // If Coday is already running, replay the thread history for this new connection
    if (this.coday) {
      debugLog('THREAD_CODAY', `Replaying thread history for new connection to ${this.threadId}`)
      this.replayThreadHistory(response)
    }
  }

  /**
   * Replay the thread history for a specific connection
   * @param response Express response object for SSE
   */
  private async replayThreadHistory(response: Response): Promise<void> {
    if (!this.coday) return

    try {
      // Get the thread from Coday
      const thread = this.coday.context?.aiThread
      if (!thread) {
        debugLog('THREAD_CODAY', `No thread found for replay in ${this.threadId}`)
        return
      }

      // Get all messages from the thread (it's async)
      const result = await thread.getMessages(undefined, undefined)
      const messages = result.messages
      debugLog('THREAD_CODAY', `Replaying ${messages.length} messages for thread ${this.threadId}`)

      // Send each message to the new connection.
      // Skip InviteEvent and ChoiceEvent — historical ones are already loaded via REST.
      // The active pending invite (if any) is re-emitted separately below.
      for (const message of messages) {
        if (message.type === 'invite' || message.type === 'choice') continue
        const data = `data: ${JSON.stringify(message)}\n\n`
        if (!response.writableEnded) {
          response.write(data)
        }
      }

      // Re-emit the active pending invite/choice so the frontend knows the agent
      // is waiting for a response. Only replay if the thread still has a pending invite
      // (i.e. the user has not yet answered). This prevents stale invites from being
      // shown after the user has already responded.
      const hasPendingInvite = this.hasPendingInviteCb(this.threadId)
      if (hasPendingInvite) {
        // Replay the invite but suppress the SET in broadcastEvent —
        // the registry already knows about this invite, we're just re-sending it to the new SSE client.
        this.isReplaying = true
        try {
          this.coday.interactor.replayLastInvite()
        } finally {
          this.isReplaying = false
        }
      }
    } catch (error) {
      debugLog('THREAD_CODAY', `Error replaying thread history:`, error)
    }
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
   * Update last activity timestamp and reset inactivity timeout
   */
  private updateActivity(): void {
    this.lastActivity = Date.now()
    this.resetInactivityTimeout()
  }

  /**
   * Reset the inactivity timeout based on session type
   */
  private resetInactivityTimeout(): void {
    // Note: this timeout is only reset on activity events (connection, prepare, start),
    // NOT on disconnect. A disconnection does not extend the instance's lifetime.
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
    }

    const timeout = this.isOneshot ? ThreadCodayInstance.ONESHOT_TIMEOUT : ThreadCodayInstance.INTERACTIVE_TIMEOUT

    this.inactivityTimeout = setTimeout(() => {
      const inactiveTime = Date.now() - this.lastActivity
      debugLog(
        'THREAD_CODAY',
        `Inactivity timeout reached for thread ${this.threadId} after ${Math.round(inactiveTime / 1000)}s`
      )
      this.onTimeout(this.threadId)
    }, timeout)
  }

  /**
   * Mark this instance as oneshot (webhook without SSE)
   */
  markAsOneshot(): void {
    this.isOneshot = true
    this.resetInactivityTimeout()
  }

  /**
   * Get time since last activity in milliseconds
   */
  getInactiveTime(): number {
    return Date.now() - this.lastActivity
  }

  /**
   * Prepare the Coday instance without starting the run
   * Useful for webhooks where we need to subscribe to events before starting
   * @returns true if new instance was created, false if already exists
   */
  prepareCoday(): boolean {
    this.updateActivity()
    if (this.coday) {
      debugLog('THREAD_CODAY', `Coday already running for thread ${this.threadId}`)
      return false
    }

    debugLog('THREAD_CODAY', `Creating Coday instance for thread ${this.threadId}`)
    console.log(
      `[THREAD_CODAY] Preparing instance for thread '${this.threadId}' (project: ${this.projectName}, user: ${this.username})`
    )

    // Create services for this Coday instance
    const interactor = new ServerInteractor(this.threadId)
    const user = new UserService(this.options.configDir, this.username, interactor)
    const project = new ProjectStateService(interactor, this.projectService, this.options.configDir)
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
      projectService: this.projectService,
      integration,
      integrationConfig,
      memory,
      mcp,
      mcpPool: this.mcpPool,
      thread: this.threadService,
      prompt: this.promptService,
      logger: this.logger,
      options: this.options,
    })
    console.log(`[THREAD_CODAY] Instance created for thread '${this.threadId}'`)

    // Note: toolbox is now accessible via coday.services.agent.toolbox
    // after agent service initialization

    return true
  }

  /**
   * Start the Coday instance for this thread
   * @returns true if new instance was created and started, false if already running
   */
  startCoday(): boolean {
    this.updateActivity()
    const wasCreated = this.prepareCoday()

    if (!this.coday) {
      return false
    }

    // Only start run() if this is a freshly created instance.
    // If the instance already existed (wasCreated=false), Coday is already running
    // and we must NOT call run() again — doing so would replay the initial prompts.
    if (!wasCreated) {
      debugLog('THREAD_CODAY', `Instance already running for thread ${this.threadId}, skipping run()`)
      return false
    }

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

    return wasCreated
  }

  /**
   * Send heartbeat to all connected SSE clients
   */
  sendHeartbeat(): void {
    if (this.connections.size === 0) {
      return
    }

    try {
      const heartBeatEvent = new HeartBeatEvent({})
      this.broadcastEvent(heartBeatEvent)
    } catch (error) {
      debugLog('THREAD_CODAY', `Error sending heartbeat for thread ${this.threadId}:`, error)
    }
  }

  /**
   * Broadcast an event to all connected SSE clients
   * @param event Event to broadcast
   */
  private broadcastEvent(event: any): void {
    // Track pendingInvite flag: set when a real InviteEvent/ChoiceEvent is emitted.
    // Uses in-memory registry (synchronous) — no disk write, no race conditions.
    // Skip during replay — the registry already has the correct state; we're just re-sending to a new SSE client.
    if (
      !this.isReplaying &&
      ((event instanceof InviteEvent && event.invite !== InviteEventDefault) || event instanceof ChoiceEvent)
    ) {
      this.setPendingInviteCb(this.threadId)
      this.projectEventManager?.broadcast(this.projectName, new ThreadUpdateEvent({ threadId: this.threadId }))
    }

    // If this is a ThreadUpdateEvent with a name, update the thread service cache
    if (event instanceof ThreadUpdateEvent && (event.name || event.summary)) {
      debugLog('THREAD_CODAY', `Updating thread cache for ${this.threadId} name/summary`)
      // Update the thread service cache asynchronously (don't block event broadcasting)
      this.threadService
        .updateThread(this.projectName, this.threadId, { name: event.name, summary: event.summary })
        .catch((error) => {
          debugLog('THREAD_CODAY', `Error updating thread cache:`, error)
        })
      // Notify project-level SSE clients so Mission Control refreshes automatically
      this.projectEventManager?.broadcast(this.projectName, event)
    }

    // Propagate InviteEvent (real user questions) and ChoiceEvent to the project-level SSE.
    // InviteEventDefault is the main-loop prompt — skip it (not a real user question).
    // ThinkingEvent is NOT propagated to project SSE: it would override waiting-you status.
    if ((event instanceof InviteEvent && event.invite !== InviteEventDefault) || event instanceof ChoiceEvent) {
      // Ensure the event carries the threadId so the frontend can map it back
      const enriched = { ...event, threadId: this.threadId }
      this.projectEventManager?.broadcast(this.projectName, enriched)
    }

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
   * Handle OAuth callback by routing to the appropriate integration
   */
  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (!this.coday) {
      debugLog('THREAD_CODAY', `Cannot handle OAuth callback: Coday not initialized for thread ${this.threadId}`)
      return
    }

    // Access toolbox through agent service
    const agentService = this.coday.services.agent
    if (!agentService) {
      debugLog('THREAD_CODAY', `Cannot handle OAuth callback: Agent service not initialized`)
      return
    }

    const toolbox = (<AgentService>this.coday.services.agent)?.toolbox
    if (!toolbox) {
      debugLog('THREAD_CODAY', `Cannot handle OAuth callback: Toolbox not initialized`)
      return
    }

    debugLog('THREAD_CODAY', `Routing OAuth callback for ${event.integrationName}`)
    await toolbox.handleOAuthCallback(event)
  }

  /**
   * Cleanup and destroy the Coday instance
   */
  async cleanup(): Promise<void> {
    debugLog('THREAD_CODAY', `Cleaning up thread ${this.threadId}`)

    // Clear all timeouts
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
      this.inactivityTimeout = undefined
    }

    // Close all SSE connections
    for (const connection of this.connections) {
      try {
        connection.end()
      } catch (error) {
        debugLog('THREAD_CODAY', `Error closing connection:`, error)
      }
    }
    this.connections.clear()

    // Run post-processing before killing Coday (need AiClient + thread still alive)
    if (this.coday) {
      const thread = this.coday.context?.aiThread
      const aiClientProvider: AiClientProvider | undefined = this.coday.aiClientProvider
      const aiClient = aiClientProvider?.getClient(undefined, 'SMALL') ?? aiClientProvider?.getClient(undefined)
      if (thread && aiClient) {
        const processor = new ThreadPostProcessor(aiClient, this.threadService)
        processor.process(thread, this.projectName)
      } else {
        debugLog('THREAD_CODAY', `Skipping post-processing for thread ${this.threadId}: no thread or AI client`)
      }
    }

    // Kill Coday instance (this will trigger cleanup of agents and MCP servers)
    if (this.coday) {
      try {
        await this.coday.kill()
      } catch (error) {
        debugLog('THREAD_CODAY', `Error during Coday kill:`, error)
      }
      this.coday = undefined
    }
  }
}

/**
 * Manages Coday instances indexed by threadId.
 * Provides a registry of active thread-based Coday instances with SSE connection management.
 */
export class ThreadCodayManager {
  private readonly instances: Map<string, ThreadCodayInstance> = new Map()
  private readonly heartbeatInterval: NodeJS.Timeout
  readonly projectEventManager = new ProjectEventManager()

  /** In-memory registry of threads with a pending (unanswered) InviteEvent or ChoiceEvent. */
  private readonly pendingInvites = new Set<string>()

  static readonly HEARTBEAT_INTERVAL = 30_000 // 30 seconds

  constructor(
    private readonly logger: CodayLogger,
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly promptService: PromptService,
    private readonly mcpPool: McpInstancePool
  ) {
    // Start global heartbeat mechanism
    this.heartbeatInterval = setInterval(() => this.sendHeartbeats(), ThreadCodayManager.HEARTBEAT_INTERVAL)
    debugLog('THREAD_CODAY_MANAGER', 'Heartbeat mechanism started')

    // Replay active invite/thinking state when a new project-level SSE client connects
    this.projectEventManager.onNewConnection = (projectName, res) => {
      this.replayActiveStatusForProject(projectName, res)
    }
  }

  /** Mark a thread as having a pending invite (synchronous). */
  setPendingInvite(threadId: string): void {
    this.pendingInvites.add(threadId)
  }

  /** Clear the pending invite flag for a thread (synchronous). */
  clearPendingInvite(threadId: string): void {
    this.pendingInvites.delete(threadId)
  }

  /** Returns true if the thread has an unanswered pending invite. */
  hasPendingInvite(threadId: string): boolean {
    return this.pendingInvites.has(threadId)
  }

  /**
   * Replay the last active InviteEvent (if any) for all running threads of a project
   * to a newly connected project-level SSE client.
   */
  private replayActiveStatusForProject(projectName: string, res: Response): void {
    for (const instance of this.instances.values()) {
      if (instance.projectName !== projectName) continue
      // Only replay if the registry confirms a pending invite (source of truth)
      if (!this.pendingInvites.has(instance.threadId)) continue
      const lastInvite = instance.coday?.interactor?.getLastInviteEvent()
      if (lastInvite) {
        const enriched = { ...lastInvite, threadId: instance.threadId }
        const data = `data: ${JSON.stringify(enriched)}\n\n`
        try {
          if (!res.writableEnded) res.write(data)
        } catch {
          // ignore write errors on the new connection
        }
      }
    }
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
      instance = new ThreadCodayInstance(
        threadId,
        projectName,
        username,
        options,
        this.logger,
        this.projectService,
        this.threadService,
        this.promptService,
        this.mcpPool,
        this.handleInstanceTimeout,
        this.projectEventManager,
        (id) => this.setPendingInvite(id),
        (id) => this.hasPendingInvite(id)
      )
      this.instances.set(threadId, instance)
    } else {
      debugLog('THREAD_CODAY', `Reusing existing instance for thread ${threadId}`)
    }

    // Add this SSE connection to the instance
    instance.addConnection(response)

    return instance
  }

  /**
   * Create a Coday instance for a specific thread without SSE connection
   * Used for webhook and other non-SSE scenarios
   * @param threadId Thread identifier
   * @param projectName Project name
   * @param username User identifier
   * @param options Coday options (must include project and thread)
   * @returns ThreadCodayInstance
   */
  createWithoutConnection(
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions
  ): ThreadCodayInstance {
    let instance = this.instances.get(threadId)

    if (!instance) {
      debugLog('THREAD_CODAY', `Creating new instance for thread ${threadId} (no SSE connection)`)
      instance = new ThreadCodayInstance(
        threadId,
        projectName,
        username,
        options,
        this.logger,
        this.projectService,
        this.threadService,
        this.promptService,
        this.mcpPool,
        this.handleInstanceTimeout,
        this.projectEventManager,
        (id) => this.setPendingInvite(id),
        (id) => this.hasPendingInvite(id)
      )
      instance.markAsOneshot() // Mark as oneshot for shorter timeout
      this.instances.set(threadId, instance)
    } else {
      debugLog('THREAD_CODAY', `Reusing existing instance for thread ${threadId}`)
    }

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

      // Note: We intentionally keep the instance alive even with 0 connections.
      // Clients can reconnect and receive full history replay.
      // Cleanup is handled by INTERACTIVE_TIMEOUT (8h) or ONESHOT_TIMEOUT (30min).

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
      // First cleanup the Coday instance (stops agents, tools, etc.)
      await instance.cleanup()

      // Then release MCP instances from the pool
      // (must be after instance cleanup to avoid race conditions with toolbox)
      await this.mcpPool.releaseThread(threadId)

      this.pendingInvites.delete(threadId)
      this.instances.delete(threadId)
      debugLog('THREAD_CODAY', `Removed instance for thread ${threadId}`)
    }
  }

  /**
   * Get statistics about managed instances
   */
  getStats(): { total: number; withConnections: number; oneshot: number } {
    let withConnections = 0
    let oneshot = 0

    for (const instance of this.instances.values()) {
      if (instance.connectionCount > 0) {
        withConnections++
      }
      if (instance['isOneshot']) {
        oneshot++
      }
    }

    return {
      total: this.instances.size,
      withConnections,
      oneshot,
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
      cleanupPromises.push(
        instance
          .cleanup()
          .then(() => this.mcpPool.releaseThread(threadId))
          .catch((error) => {
            debugLog('THREAD_CODAY_MANAGER', `Error cleaning up thread ${threadId}:`, error)
          })
      )
    }

    await Promise.all(cleanupPromises)
    this.instances.clear()
    this.pendingInvites.clear()

    // Final safety net: shutdown any remaining MCP instances
    await this.mcpPool.shutdown()

    // Close project-level SSE connections
    this.projectEventManager.shutdown()

    debugLog('THREAD_CODAY_MANAGER', 'All thread instances cleaned up')
  }
}

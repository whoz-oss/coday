import { Response } from 'express'
import { Coday } from '@coday/core'
import { ServerInteractor } from '@coday/model'
import { CodayOptions } from '@coday/model'
import { UserService } from '@coday/service'
import { ProjectStateService } from '@coday/service'
import { IntegrationService } from '@coday/service'
import { IntegrationConfigService } from '@coday/service'
import { MemoryService } from '@coday/service'
import { McpConfigService } from '@coday/service'
import { PromptService } from '@coday/service'
import { CodayLogger } from '@coday/model'
import { HeartBeatEvent, ThreadUpdateEvent, OAuthCallbackEvent, MessageEvent, ThinkingEvent } from '@coday/model'
import { debugLog } from './log'
import { ProjectService } from '@coday/service'
import { ThreadService } from '@coday/service'
import { McpInstancePool } from '@coday/mcp'
import { AgentService } from '@coday/agent'

/**
 * Represents a Coday instance associated with a specific thread.
 * Manages the lifecycle and SSE connections for a single thread.
 */
class ThreadCodayInstance {
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
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly promptService: PromptService,
    private readonly mcpPool: McpInstancePool,
    private readonly onTimeout: (threadId: string) => void
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

    // Clear disconnect timeout if reconnecting
    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
      debugLog('THREAD_CODAY', `Cleared disconnect timeout for thread ${this.threadId}`)
    }

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

      // Send each message to the new connection
      for (const message of messages) {
        const data = `data: ${JSON.stringify(message)}\n\n`
        if (!response.writableEnded) {
          response.write(data)
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

    // If no more connections, start disconnect timeout
    if (this.connections.size === 0 && !this.disconnectTimeout) {
      debugLog('THREAD_CODAY', `No connections remaining for thread ${this.threadId}, starting disconnect timeout`)
      this.disconnectTimeout = setTimeout(() => {
        debugLog('THREAD_CODAY', `Disconnect timeout reached for thread ${this.threadId}`)
        this.onTimeout(this.threadId)
      }, ThreadCodayInstance.DISCONNECT_TIMEOUT)
    }
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
    // Skip Slack forwarding for replayed events (they're already in Slack)
    const isReplayedEvent = event._isReplayed === true
    // If this is a ThreadUpdateEvent with a name, update the thread service cache
    if (event instanceof ThreadUpdateEvent && event.name) {
      debugLog('THREAD_CODAY', `Updating thread cache for ${this.threadId} with name: ${event.name}`)
      // Update the thread service cache asynchronously (don't block event broadcasting)
      this.threadService.updateThread(this.projectName, this.threadId, { name: event.name }).catch((error) => {
        debugLog('THREAD_CODAY', `Error updating thread cache:`, error)
      })
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

    // Forward events to Slack if configured (non-blocking)
    // Skip forwarding for replayed events to avoid duplicates
    if (!isReplayedEvent) {
      void this.forwardEventToSlack(event).catch((error) => {
        debugLog('THREAD_CODAY', `Error forwarding event to Slack:`, error)
      })
    }
  }

  // Track thinking message timestamps per thread
  private slackThinkingMessages: Map<string, { channel: string; ts: string; threadTs?: string }> = new Map()
  // Track the original Slack message that started the conversation (for threading)
  private slackOriginalMessages: Map<string, { channel: string; ts: string; isDM: boolean }> = new Map()

  private async postSlackMessage(
    token: string,
    channel: string,
    text: string,
    threadTs?: string
  ): Promise<{ ts?: string }> {
    const body: Record<string, any> = { channel, text }
    if (threadTs) body.thread_ts = threadTs

    const response = await fetch('https://slack.com/api/chat.postMessage', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify(body),
    })

    const data = (await response.json()) as { ok: boolean; error?: string; ts?: string }
    if (!data.ok) {
      throw new Error(data.error || 'Slack API error')
    }

    return { ts: data.ts }
  }

  private async postSlackMessageWithBlocks(
    token: string,
    channel: string,
    text: string,
    blocks: any[],
    threadTs?: string
  ): Promise<{ ts?: string }> {
    const body: Record<string, any> = { channel, text, blocks }
    if (threadTs) body.thread_ts = threadTs

    const response = await fetch('https://slack.com/api/chat.postMessage', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify(body),
    })

    const data = (await response.json()) as { ok: boolean; error?: string; ts?: string }
    if (!data.ok) {
      throw new Error(data.error || 'Slack API error')
    }

    return { ts: data.ts }
  }

  private async updateSlackMessage(
    token: string,
    channel: string,
    ts: string,
    text: string,
    blocks?: any[]
  ): Promise<void> {
    const body: Record<string, any> = { channel, ts, text }
    if (blocks) body.blocks = blocks

    const response = await fetch('https://slack.com/api/chat.update', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json; charset=utf-8',
      },
      body: JSON.stringify(body),
    })

    const data = (await response.json()) as { ok: boolean; error?: string }
    if (!data.ok) {
      throw new Error(data.error || 'Slack API error')
    }
  }

  private async forwardEventToSlack(event: unknown): Promise<void> {
    const project = this.projectService.getProject(this.projectName)
    const slackConfig = project?.config.integration?.SLACK as
      | {
          apiKey?: string
          forwardEvents?: boolean
          notifyChannel?: string
          threadMap?: Record<string, string>
        }
      | undefined

    if (!slackConfig?.apiKey) return

    const thread = await this.threadService.getThread(this.projectName, this.threadId)
    const isSlackOriginated = thread?.name?.startsWith('slack:')
    const threadMap = slackConfig.threadMap || {}

    // Handle ThinkingEvent - show thinking indicator
    if (event instanceof ThinkingEvent) {
      // Find the Slack channel for this thread
      let channel: string | undefined
      let threadTs: string | undefined

      if (isSlackOriginated) {
        channel = Object.keys(threadMap).find((key) => threadMap[key] === this.threadId)
      } else {
        if (!slackConfig.forwardEvents) return
        const existingThreadKey = Object.keys(threadMap).find((key) => threadMap[key] === this.threadId)
        if (existingThreadKey) {
          const [channelId, threadTsValue] = existingThreadKey.split(':')
          channel = channelId
          threadTs = threadTsValue
        } else {
          channel = slackConfig.notifyChannel
        }
      }

      if (!channel) return

      const existingThinkingMsg = this.slackThinkingMessages.get(this.threadId)

      // If we already have a thinking message, update it to show thinking indicator
      if (existingThinkingMsg) {
        try {
          // Update with blocks to show thinking status as context
          const blocks = [
            {
              type: 'context',
              elements: [
                {
                  type: 'mrkdwn',
                  text: ':hourglass_flowing_sand: _Thinking..._',
                },
              ],
            },
          ]

          await this.updateSlackMessage(
            slackConfig.apiKey,
            existingThinkingMsg.channel,
            existingThinkingMsg.ts,
            ':hourglass_flowing_sand: _Thinking_',
            blocks
          )
        } catch (error) {
          debugLog('THREAD_CODAY', `Error updating thinking indicator:`, error)
        }
      } else {
        // Post new thinking message with blocks
        try {
          const blocks = [
            {
              type: 'context',
              elements: [
                {
                  type: 'mrkdwn',
                  text: ':hourglass_flowing_sand: _Thinking..._',
                },
              ],
            },
          ]

          const response = await this.postSlackMessageWithBlocks(
            slackConfig.apiKey,
            channel,
            ':hourglass_flowing_sand: _Thinking_',
            blocks,
            threadTs
          )

          if (response.ts) {
            this.slackThinkingMessages.set(this.threadId, {
              channel,
              ts: response.ts,
              threadTs,
            })
          }
        } catch (error) {
          debugLog('THREAD_CODAY', `Error posting thinking message:`, error)
        }
      }
      return
    }

    // Handle MessageEvent - post actual response
    if (!(event instanceof MessageEvent)) return
    if (event.role !== 'assistant') return

    const text = event.getTextContent()
    if (!text) return

    // Import markdown-to-slack converter
    const { markdownToSlack } = await import('@coday/utils')
    const slackFormattedText = markdownToSlack(text)

    // Update thinking message if it exists, otherwise post new message
    const thinkingMsg = this.slackThinkingMessages.get(this.threadId)
    if (thinkingMsg) {
      try {
        // Update the existing message with the actual response
        await this.updateSlackMessage(slackConfig.apiKey, thinkingMsg.channel, thinkingMsg.ts, slackFormattedText)
        this.slackThinkingMessages.delete(this.threadId)
        // Early return since we updated the existing message
        return
      } catch (error) {
        debugLog('THREAD_CODAY', `Error updating thinking message:`, error)
        // Fall through to post new message if update fails
      }
    }

    // SCENARIO 1: Respond back to Slack-originated threads
    // These threads were created from incoming Slack messages and should respond to the same channel
    if (isSlackOriginated) {
      // Find the Slack channel this thread is mapped to (key is just the channel ID)
      const slackChannel = Object.keys(threadMap).find((key) => threadMap[key] === this.threadId)
      if (slackChannel) {
        // Get the original message info
        const originalMsg = this.slackOriginalMessages.get(this.threadId)
        // For DMs, don't thread replies - just post as regular messages
        // For channels, thread replies to keep conversation organized
        const threadTs = originalMsg?.isDM ? undefined : originalMsg?.ts
        await this.postSlackMessage(slackConfig.apiKey, slackChannel, slackFormattedText, threadTs)
      }
      return
    }

    // SCENARIO 2: Forward events to notification channel (for non-Slack threads)
    // This requires forwardEvents to be explicitly enabled
    if (!slackConfig.forwardEvents) return

    const existingThreadKey = Object.keys(threadMap).find((key) => threadMap[key] === this.threadId)

    let channel: string | undefined
    let threadTs: string | undefined

    if (existingThreadKey) {
      const [channelId, threadTsValue] = existingThreadKey.split(':')
      channel = channelId
      threadTs = threadTsValue
    } else {
      channel = slackConfig.notifyChannel
    }

    if (!channel) return

    const response = await this.postSlackMessage(slackConfig.apiKey, channel, slackFormattedText, threadTs)

    if (!existingThreadKey && response.ts && project) {
      const newThreadKey = `${channel}:${response.ts}`
      const updatedMap = { ...threadMap, [newThreadKey]: this.threadId }
      const updatedConfig = { ...slackConfig, threadMap: updatedMap }
      const updatedIntegration = { ...(project.config.integration || {}), SLACK: updatedConfig }
      this.projectService.updateProjectConfig(this.projectName, { ...project.config, integration: updatedIntegration })
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
   * Set the original Slack message that started this conversation (for threading)
   * @param channel Slack channel ID
   * @param ts Message timestamp
   * @param isDM Whether this is a direct message channel
   */
  setSlackOriginalMessage(channel: string, ts: string, isDM: boolean = false): void {
    this.slackOriginalMessages.set(this.threadId, { channel, ts, isDM })
  }

  /**
   * Cleanup and destroy the Coday instance
   */
  async cleanup(): Promise<void> {
    debugLog('THREAD_CODAY', `Cleaning up thread ${this.threadId}`)

    // Clear all timeouts
    if (this.disconnectTimeout) {
      clearTimeout(this.disconnectTimeout)
      this.disconnectTimeout = undefined
    }
    if (this.inactivityTimeout) {
      clearTimeout(this.inactivityTimeout)
      this.inactivityTimeout = undefined
    }

    // Clean up any remaining thinking messages in Slack
    const thinkingMsg = this.slackThinkingMessages.get(this.threadId)
    if (thinkingMsg) {
      try {
        const project = this.projectService.getProject(this.projectName)
        const slackConfig = project?.config.integration?.SLACK as { apiKey?: string } | undefined
        if (slackConfig?.apiKey) {
          // Update the message to remove thinking indicator
          await this.updateSlackMessage(
            slackConfig.apiKey,
            thinkingMsg.channel,
            thinkingMsg.ts,
            '_Processing interrupted_'
          )
        }
      } catch (error) {
        debugLog('THREAD_CODAY', `Error cleaning up thinking message:`, error)
      }
      this.slackThinkingMessages.delete(this.threadId)
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
        this.handleInstanceTimeout
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
        this.handleInstanceTimeout
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
      // Release MCP instances used by this thread
      await this.mcpPool.releaseThread(threadId)

      await instance.cleanup()
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
      cleanupPromises.push(instance.cleanup())
    }

    await Promise.all(cleanupPromises)
    this.instances.clear()

    // Shutdown MCP instance pool
    await this.mcpPool.shutdown()

    debugLog('THREAD_CODAY_MANAGER', 'All thread instances cleaned up')
  }
}

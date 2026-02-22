import { SocketModeClient } from '@slack/socket-mode'
import { LogLevel, WebClient } from '@slack/web-api'
import { ProjectService, ThreadService } from '@coday/service'
import { CodayOptions, AnswerEvent, InviteEvent } from '@coday/model'
import { filter, take } from 'rxjs'

type SlackLogger = (scope: string, message: string, ...args: unknown[]) => void

type ThreadCodayInstance = {
  prepareCoday: () => boolean
  setSlackOriginalMessage: (channel: string, ts: string, isDM?: boolean) => void
  coday?: {
    run: () => Promise<void>
    interactor: {
      events: any
      sendEvent: (event: any) => void
      replayLastInvite: () => void
    }
  }
}

type ThreadCodayManagerLike = {
  get: (threadId: string) => ThreadCodayInstance | undefined
  createWithoutConnection: (
    threadId: string,
    projectName: string,
    username: string,
    options: CodayOptions
  ) => ThreadCodayInstance
  cleanup: (threadId: string) => Promise<void>
}

type SlackIntegrationConfig = {
  apiKey?: string // Bot Token (xoxb-)
  appToken?: string // App-Level Token (xapp-) - Required for Socket Mode
  signingSecret?: string // Not used in Socket Mode but kept for compatibility
  project?: string
  username?: string
  requireMention?: boolean
  botUserId?: string
  respondInThread?: boolean
  channelAllowlist?: string[]
  threadMap?: Record<string, string>
  socketMode?: boolean // Enable Socket Mode
  autoCreateThreads?: boolean // Auto-create Coday threads for new Slack channels (default: false)
}

export function stripBotMention(text: string, botUserId?: string): string {
  if (!botUserId) return text
  return text.replace(new RegExp(`<@${botUserId}>`, 'g'), '').trim()
}

export function shouldHandleMessage(
  event: {
    type?: string
    subtype?: string
    text?: string
    channel?: string
    ts?: string
    bot_id?: string
    channel_type?: string
  },
  config: SlackIntegrationConfig
): { allowed: boolean; reason?: string; isChannelJoin?: boolean } {
  if (!event) return { allowed: false, reason: 'No event provided' }
  if (event.type !== 'message') return { allowed: false, reason: `Event type is '${event.type}', not 'message'` }

  // Allow channel_join subtype to initiate new threads
  if (event.subtype === 'channel_join') {
    return { allowed: true, isChannelJoin: true }
  }

  // Allow message_changed subtype for edits, but reject others (like message_deleted)
  if (event.subtype && event.subtype !== 'message_changed') {
    return { allowed: false, reason: `Event subtype '${event.subtype}' is not allowed` }
  }

  if (!event.text) return { allowed: false, reason: 'No text in message' }
  if (!event.channel) return { allowed: false, reason: 'No channel in message' }
  if (!event.ts) return { allowed: false, reason: 'No timestamp in message' }
  if (event.bot_id) return { allowed: false, reason: `Message is from a bot (bot_id: ${event.bot_id})` }

  if (Array.isArray(config.channelAllowlist) && config.channelAllowlist.length > 0) {
    if (!config.channelAllowlist.includes(event.channel)) {
      return {
        allowed: false,
        reason: `Channel '${event.channel}' not in allowlist [${config.channelAllowlist.join(', ')}]`,
      }
    }
  }

  if (config.requireMention && event.channel_type !== 'im') {
    if (!config.botUserId) {
      return { allowed: false, reason: 'requireMention is enabled but botUserId is not configured' }
    }
    if (!event.text.includes(`<@${config.botUserId}>`)) {
      return {
        allowed: false,
        reason: `requireMention is enabled and bot <@${config.botUserId}> was not mentioned in: "${event.text}"`,
      }
    }
  }

  return { allowed: true }
}

export function buildThreadKey(channel: string, threadTs?: string, messageTs?: string, channelType?: string): string {
  // For DMs (channelType === 'im'), always return just the channel ID.
  // DMs are inherently continuous conversations — thread_ts must not create a new key.
  if (channelType === 'im') {
    return channel
  }

  // For non-DM channels: threaded replies get their own key
  if (threadTs && threadTs !== messageTs) {
    return `${channel}:${threadTs}`
  }

  return channel
}

export class SlackSocketModeManager {
  private clients: Map<string, SocketModeClient> = new Map()
  private webClients: Map<string, WebClient> = new Map()

  constructor(
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly threadCodayManager: ThreadCodayManagerLike,
    private readonly codayOptions: CodayOptions,
    private readonly logger: SlackLogger = console.log
  ) {}

  /**
   * Initialize Socket Mode connections for all projects with Socket Mode enabled.
   */
  async initialize(): Promise<void> {
    this.logger('SLACK_SOCKET', 'Initializing Socket Mode connections...')

    const projects = this.projectService.listProjects()

    for (const project of projects) {
      const projectInfo = this.projectService.getProject(project.name)
      const config = (projectInfo?.config.integration?.SLACK || {}) as SlackIntegrationConfig

      if (!config.socketMode) {
        continue
      }

      if (!config.appToken) {
        this.logger('SLACK_SOCKET', `Project ${project.name}: Socket Mode enabled but no appToken configured`)
        continue
      }

      if (!config.apiKey) {
        this.logger('SLACK_SOCKET', `Project ${project.name}: Socket Mode enabled but no apiKey (bot token) configured`)
        continue
      }

      if (!config.username) {
        this.logger('SLACK_SOCKET', `Project ${project.name}: Socket Mode enabled but no username configured`)
        continue
      }

      try {
        await this.connectProject(project.name, config)
      } catch (error) {
        this.logger('SLACK_SOCKET', `Failed to connect project ${project.name}:`, error)
      }
    }
  }

  /**
   * Connect a project to Slack using Socket Mode
   */
  private async connectProject(projectName: string, config: SlackIntegrationConfig): Promise<void> {
    if (this.clients.has(projectName)) {
      this.logger('SLACK_SOCKET', `Project ${projectName} already connected`)
      return
    }

    this.logger('SLACK_SOCKET', `Connecting project ${projectName} via Socket Mode...`)

    // Create Socket Mode client with App-Level Token
    const socketClient = new SocketModeClient({
      appToken: config.appToken!,
      logLevel: this.codayOptions.debug ? LogLevel.DEBUG : LogLevel.INFO,
    })

    // Create Web API client with Bot Token
    const webClient = new WebClient(config.apiKey!)

    // Store clients
    this.clients.set(projectName, socketClient)
    this.webClients.set(projectName, webClient)

    // Handle events
    socketClient.on('message', async ({ body, ack }) => {
      try {
        this.logger('SLACK_SOCKET', 'Message received')
        // Acknowledge the event immediately
        await ack()

        // Reload config from project to get latest threadMap
        const currentProject = this.projectService.getProject(projectName)
        const currentConfig = (currentProject?.config.integration?.SLACK || {}) as SlackIntegrationConfig

        // Handle different event types
        await this.handleEvent(projectName, currentConfig, body.event, webClient)
      } catch (error) {
        this.logger('SLACK_SOCKET', `Error handling message for ${projectName}:`, error)
      }
    })

    // Handle connection events
    socketClient.on('connected', () => {
      this.logger('SLACK_SOCKET', `✓ Project ${projectName} connected via Socket Mode`)
    })

    socketClient.on('disconnected', () => {
      this.logger('SLACK_SOCKET', `Project ${projectName} disconnected`)
    })

    socketClient.on('error', (error) => {
      this.logger('SLACK_SOCKET', `Project ${projectName} error:`, error)
    })

    // Start the connection
    await socketClient.start()
  }

  /**
   * Handle a Slack event
   */
  private async handleEvent(
    projectName: string,
    config: SlackIntegrationConfig,
    event: any,
    webClient: WebClient
  ): Promise<void> {
    this.logger(
      'SLACK_SOCKET',
      `Event received for ${projectName}: type=${event.type}, subtype=${event.subtype}, channel_type=${event.channel_type}`
    )

    const filterResult = shouldHandleMessage(event, config)
    if (!filterResult.allowed) {
      this.logger('SLACK_SOCKET', `Message filtered out: ${filterResult.reason}`)
      return
    }

    this.logger('SLACK_SOCKET', 'Message passed filters, proceeding to handle')

    const channel = event.channel!
    const threadKey = buildThreadKey(channel, event.thread_ts, event.ts, event.channel_type)

    this.logger('SLACK_SOCKET', `Channel: ${channel}, ThreadKey: ${threadKey}`)
    this.logger('SLACK_SOCKET', `Current threadMap keys: [${Object.keys(config.threadMap || {}).join(', ')}]`)

    // Handle channel_join event - create thread and send greeting
    if (filterResult.isChannelJoin) {
      this.logger('SLACK_SOCKET', `Handling channel_join event for channel ${channel}`)

      // Check if thread already exists
      let threadId = config.threadMap?.[threadKey]

      if (threadId) {
        this.logger('SLACK_SOCKET', `Thread already exists for channel ${channel}: ${threadId}`)
        return
      }

      // Create new thread
      this.logger('SLACK_SOCKET', `Creating new thread for channel_join in ${channel}`)

      // Fetch channel name from Slack API
      let channelName = channel
      try {
        const channelInfo = await webClient.conversations.info({ channel })
        if (channelInfo.ok && channelInfo.channel?.name) {
          channelName = channelInfo.channel.name
          this.logger('SLACK_SOCKET', `Resolved channel name: ${channelName}`)
        }
      } catch (error) {
        this.logger(
          'SLACK_SOCKET',
          `Could not fetch channel name, using ID: ${error instanceof Error ? error.message : String(error)}`
        )
      }

      const threadName = `slack:${channelName}`
      const thread = await this.threadService.createThread(projectName, config.username!, threadName)
      threadId = thread.id

      this.logger('SLACK_SOCKET', `Created new thread: ${threadId} with name: ${threadName}`)

      // Update thread map in project config
      const project = this.projectService.getProject(projectName)
      if (project) {
        const updatedMap = { ...(config.threadMap || {}), [threadKey]: threadId }
        const updatedConfig = { ...config, threadMap: updatedMap }
        const updatedIntegration = { ...(project.config.integration || {}), SLACK: updatedConfig }
        this.projectService.updateProjectConfig(projectName, { ...project.config, integration: updatedIntegration })

        this.logger('SLACK_SOCKET', `Updated threadMap, now has keys: [${Object.keys(updatedMap).join(', ')}]`)
      }

      // Send greeting message directly to the channel
      this.logger('SLACK_SOCKET', `Sending greeting message to channel ${channel}`)

      const greetingMessage =
        "Hello! 👋 I'm Coday, your AI assistant. I'm here to help with tasks, answer questions, and collaborate with your team. Feel free to mention me or ask me anything!"

      try {
        await webClient.chat.postMessage({
          channel: channel,
          text: greetingMessage,
        })
        this.logger('SLACK_SOCKET', `Greeting sent successfully to channel ${channel}`)
      } catch (error) {
        this.logger(
          'SLACK_SOCKET',
          `Failed to send greeting: ${error instanceof Error ? error.message : String(error)}`
        )
      }

      this.logger('SLACK_SOCKET', `Channel join handled, thread ${threadId} is ready for messages`)
      return
    }

    // Find or create Coday thread
    let threadId = config.threadMap?.[threadKey]

    if (!threadId) {
      // Check if auto-creation is enabled
      if (!config.autoCreateThreads) {
        this.logger(
          'SLACK_SOCKET',
          `No existing thread for key "${threadKey}" and autoCreateThreads is disabled, ignoring message`
        )
        return
      }

      this.logger('SLACK_SOCKET', `No existing thread found for key "${threadKey}", creating new thread`)

      // Fetch channel name from Slack API for better readability
      let channelName = channel // fallback to channel ID
      try {
        const channelInfo = await webClient.conversations.info({ channel })
        if (channelInfo.ok && channelInfo.channel?.name) {
          channelName = channelInfo.channel.name
          this.logger('SLACK_SOCKET', `Resolved channel name: ${channelName}`)
        }
      } catch (error) {
        this.logger(
          'SLACK_SOCKET',
          `Could not fetch channel name, using ID: ${error instanceof Error ? error.message : String(error)}`
        )
      }

      const threadName = `slack:${channelName}`
      const thread = await this.threadService.createThread(projectName, config.username!, threadName)
      threadId = thread.id

      this.logger('SLACK_SOCKET', `Created new thread: ${threadId} with name: ${threadName}`)

      // Update thread map in project config
      const project = this.projectService.getProject(projectName)
      if (project) {
        const updatedMap = { ...(config.threadMap || {}), [threadKey]: threadId }
        const updatedConfig = { ...config, threadMap: updatedMap }
        const updatedIntegration = { ...(project.config.integration || {}), SLACK: updatedConfig }
        this.projectService.updateProjectConfig(projectName, { ...project.config, integration: updatedIntegration })

        this.logger('SLACK_SOCKET', `Updated threadMap, now has keys: [${Object.keys(updatedMap).join(', ')}]`)
      }
    } else {
      this.logger('SLACK_SOCKET', `Found existing thread: ${threadId} for key "${threadKey}"`)
    }

    const prompt = stripBotMention(event.text!, config.botUserId)
    if (!prompt) {
      return
    }

    // Get or create a Coday instance for this thread (without SSE connection)
    // This ensures we reuse the same instance and maintain conversation context
    let instance = this.threadCodayManager.get(threadId)

    if (!instance) {
      this.logger('SLACK_SOCKET', `Creating Coday instance for thread ${threadId}`)

      const slackOptions: CodayOptions = {
        ...this.codayOptions,
        oneshot: false, // Not oneshot - keep instance alive
        project: projectName,
        thread: threadId,
        prompts: [prompt], // Add the message as initial prompt
      }

      instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, config.username!, slackOptions)
      instance.prepareCoday()
      instance.setSlackOriginalMessage(channel, event.ts!, event.channel_type === 'im')

      // Start the Coday run in the background
      instance.coday!.run().catch((error) => {
        this.logger('SLACK_SOCKET', `Error in Coday run: ${error instanceof Error ? error.message : String(error)}`)
      })
    } else {
      // Instance already exists - need to send message to it
      instance.setSlackOriginalMessage(channel, event.ts!, event.channel_type === 'im')

      if (!instance.coday) {
        this.logger('SLACK_SOCKET', `Failed to get Coday instance for thread ${threadId}`)
        return
      }

      this.logger('SLACK_SOCKET', `Sending message to existing thread ${threadId}`)

      // Subscribe FIRST, then replay
      // This is important because replay happens synchronously
      const invitePromise = new Promise<InviteEvent>((resolve, reject) => {
        const timeoutHandle = setTimeout(() => reject(new Error('Timeout waiting for InviteEvent')), 1000)

        instance!
          .coday!.interactor.events.pipe(
            filter((event): event is InviteEvent => event instanceof InviteEvent),
            take(1)
          )
          .subscribe({
            next: (inviteEvent: InviteEvent) => {
              clearTimeout(timeoutHandle)
              resolve(inviteEvent)
            },
            error: reject,
          })
      })

      // Now replay the last invite (will emit immediately if it exists)
      this.logger('SLACK_SOCKET', `Calling replayLastInvite()...`)
      instance.coday.interactor.replayLastInvite()
      this.logger('SLACK_SOCKET', `replayLastInvite() called`)

      // Wait for the invite and respond
      invitePromise
        .then((inviteEvent: InviteEvent) => {
          this.logger('SLACK_SOCKET', `✓ Received InviteEvent with timestamp: ${inviteEvent.timestamp}`)
          this.logger('SLACK_SOCKET', `Building answer with parentKey...`)
          const answerEvent = inviteEvent.buildAnswer(prompt)
          this.logger('SLACK_SOCKET', `Sending answer event with parentKey: ${answerEvent.parentKey}`)
          instance!.coday!.interactor.sendEvent(answerEvent)
        })
        .catch((error: Error) => {
          this.logger('SLACK_SOCKET', `✗ Could not get InviteEvent: ${error.message}`)
          this.logger('SLACK_SOCKET', `Fallback: sending AnswerEvent without parentKey`)
          instance!.coday!.interactor.sendEvent(new AnswerEvent({ answer: prompt }))
        })
    }

    // Note: Response will be sent to Slack automatically via forwardEventToSlack
    // in ThreadCodayInstance when the assistant generates a response
  }

  /**
   * Disconnect all Socket Mode connections
   */
  async shutdown(): Promise<void> {
    this.logger('SLACK_SOCKET', `Shutting down ${this.clients.size} Socket Mode connections...`)

    const disconnectPromises: Promise<void>[] = []

    for (const [projectName, client] of this.clients.entries()) {
      this.logger('SLACK_SOCKET', `Disconnecting project ${projectName}...`)
      disconnectPromises.push(client.disconnect())
    }

    await Promise.all(disconnectPromises)

    this.clients.clear()
    this.webClients.clear()

    this.logger('SLACK_SOCKET', 'All Socket Mode connections closed')
  }
}

import express from 'express'
import crypto from 'node:crypto'
import { SocketModeClient } from '@slack/socket-mode'
import { LogLevel, WebClient } from '@slack/web-api'
import { ProjectService, ThreadService } from '@coday/service'
import {
  CanalBridge,
  CanalThreadHandle,
  ChoiceEvent,
  CommunicationCanal,
  CodayEvent,
  CodayOptions,
  MessageEvent,
  ThinkingEvent,
} from '@coday/model'
import { shouldHandleMessage, buildThreadKey, stripBotMention } from './slack-socket-mode'

// ─── Types ────────────────────────────────────────────────────────────────────

type SlackLogger = (scope: string, message: string, ...args: unknown[]) => void

export type SlackIntegrationConfig = {
  apiKey?: string
  appToken?: string
  signingSecret?: string
  project?: string
  username?: string
  requireMention?: boolean
  botUserId?: string
  respondInThread?: boolean
  channelAllowlist?: string[]
  threadMap?: Record<string, string>
  socketMode?: boolean
  autoCreateThreads?: boolean
  forwardEvents?: boolean
  notifyChannel?: string
}

const SLACK_SIGNATURE_HEADER = 'x-slack-signature'
const SLACK_TIMESTAMP_HEADER = 'x-slack-request-timestamp'
const FIVE_MINUTES_SECONDS = 60 * 5

// ─── Slack API helpers ────────────────────────────────────────────────────────

async function postSlackMessage(
  token: string,
  channel: string,
  text: string,
  threadTs?: string
): Promise<{ ts?: string }> {
  const body: Record<string, any> = { channel, text }
  if (threadTs) body.thread_ts = threadTs
  const response = await fetch('https://slack.com/api/chat.postMessage', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  })
  const data = (await response.json()) as { ok: boolean; error?: string; ts?: string }
  if (!data.ok) throw new Error(data.error || 'Slack API error')
  return { ts: data.ts }
}

async function postSlackMessageWithBlocks(
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
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  })
  const data = (await response.json()) as { ok: boolean; error?: string; ts?: string }
  if (!data.ok) throw new Error(data.error || 'Slack API error')
  return { ts: data.ts }
}

async function updateSlackMessage(
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
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  })
  const data = (await response.json()) as { ok: boolean; error?: string }
  if (!data.ok) throw new Error(data.error || 'Slack API error')
}

// ─── Signature verification ───────────────────────────────────────────────────

function getRawBody(req: express.Request): string {
  const raw = (req as any).rawBody
  return raw && Buffer.isBuffer(raw) ? raw.toString('utf8') : ''
}

function verifySlackSignature(rawBody: string, signingSecret: string, signature: string, timestamp: string): boolean {
  if (!signature || !timestamp) return false
  const ts = Number(timestamp)
  if (!Number.isFinite(ts)) return false
  if (Math.abs(Math.floor(Date.now() / 1000) - ts) > FIVE_MINUTES_SECONDS) return false
  const hmac = crypto.createHmac('sha256', signingSecret).update(`v0:${timestamp}:${rawBody}`).digest('hex')
  try {
    return crypto.timingSafeEqual(Buffer.from(`v0=${hmac}`), Buffer.from(signature))
  } catch {
    return false
  }
}

// ─── Choice resolution helper ────────────────────────────────────────────────

/**
 * Resolve a user's Slack reply to a ChoiceEvent option.
 * Accepts either the option value directly, or a 1-based numeric index.
 * Falls back to the raw input if no match is found.
 */
export function resolveChoice(input: string, options: string[]): string {
  const trimmed = input.trim()

  // Direct match (case-insensitive)
  const directMatch = options.find((opt) => opt.toLowerCase() === trimmed.toLowerCase())
  if (directMatch) return directMatch

  // Numeric index (1-based)
  const index = parseInt(trimmed, 10)
  if (!isNaN(index) && index >= 1 && index <= options.length) {
    return options[index - 1]!
  }

  // Fallback: return the raw input so the agent can handle it
  return trimmed
}

// ─── Per-thread Slack state ───────────────────────────────────────────────────

interface SlackThreadState {
  projectName: string
  thinkingMessage?: { channel: string; ts: string; threadTs?: string }
  originalMessage?: { channel: string; ts: string; threadTs?: string; isDM: boolean }
  unsubscribe?: () => void
  handle: CanalThreadHandle
  /** Pending ChoiceEvent waiting for a user selection, if any */
  pendingChoiceEvent?: ChoiceEvent
  /** The Slack message that contains the choice buttons, so we can update it after selection */
  pendingChoiceMessage?: { channel: string; ts: string }
}

// ─── SlackCanal ───────────────────────────────────────────────────────────────

/**
 * Slack adapter implementing the CommunicationCanal port.
 *
 * Absorbs all Slack-specific code previously in:
 * - ThreadCodayInstance (forwardEventToSlack, Slack API calls, thinking indicators)
 * - slack.routes.ts (HTTP webhook handling)
 * - SlackSocketModeManager (Socket Mode connections)
 *
 * The core (ThreadCodayManager) is now completely free of Slack knowledge.
 * Event forwarding uses the adapter-subscribes pattern: this class subscribes to
 * thread events; the core never calls outward.
 */
export class SlackCanal implements CommunicationCanal {
  readonly name = 'slack'

  private bridge?: CanalBridge
  private socketClients: Map<string, SocketModeClient> = new Map()
  private webClients: Map<string, WebClient> = new Map()
  // threadId → per-thread Slack state including event subscription
  private threadStates: Map<string, SlackThreadState> = new Map()

  constructor(
    private readonly app: express.Application,
    private readonly projectService: ProjectService,
    private readonly threadService: ThreadService,
    private readonly codayOptions: CodayOptions,
    private readonly logger: SlackLogger = console.log
  ) {}

  /**
   * Register HTTP routes on the Express app.
   * Must be called BEFORE the catch-all route in server.ts.
   * This is a separate step from initialize() which starts connections.
   */
  registerRoutes(): void {
    this.registerHttpRoutes()
  }

  async initialize(bridge: CanalBridge): Promise<void> {
    this.bridge = bridge
    this.logger('SLACK_CANAL', 'Initializing Slack canal (starting connections)...')
    await this.initializeSocketMode()
    this.logger('SLACK_CANAL', 'Slack canal initialized')
  }

  async shutdown(): Promise<void> {
    this.logger('SLACK_CANAL', `Shutting down Slack canal (${this.socketClients.size} Socket Mode connections)...`)

    // Unsubscribe from all thread events
    for (const state of this.threadStates.values()) {
      state.unsubscribe?.()
    }
    this.threadStates.clear()

    // Disconnect all Socket Mode clients
    await Promise.all([...this.socketClients.values()].map((c) => c.disconnect()))
    this.socketClients.clear()
    this.webClients.clear()

    this.logger('SLACK_CANAL', 'Slack canal shut down')
  }

  // ─── Block action interaction handler ─────────────────────────────────────

  /**
   * Handle a Slack block_actions payload (button click).
   * Shared between Socket Mode and HTTP webhook paths.
   * Returns the chosen value if handled, undefined otherwise.
   */
  private async handleBlockAction(payload: any, config: SlackIntegrationConfig, _projectName: string): Promise<void> {
    const actions: any[] = payload.actions || []
    for (const action of actions) {
      const actionId: string = action.action_id || ''
      if (!actionId.startsWith('choice__')) continue

      // action_id format: choice__{threadId}__{optionValue}
      const withoutPrefix = actionId.slice('choice__'.length)
      const separatorIndex = withoutPrefix.indexOf('__')
      if (separatorIndex === -1) continue

      const threadId = withoutPrefix.slice(0, separatorIndex)
      const chosenValue = action.value as string

      const state = this.threadStates.get(threadId)
      if (!state) {
        this.logger('SLACK_CANAL', `No state found for threadId ${threadId} from block action`)
        continue
      }

      if (!state.pendingChoiceEvent) {
        this.logger('SLACK_CANAL', `No pending choice for thread ${threadId}, ignoring button click`)
        continue
      }

      this.logger('SLACK_CANAL', `Button clicked: "${chosenValue}" for thread ${threadId}`)

      // Replace the buttons with a plain confirmation message
      if (state.pendingChoiceMessage && config.apiKey) {
        const question = state.pendingChoiceEvent.optionalQuestion
          ? `${state.pendingChoiceEvent.optionalQuestion}\n${state.pendingChoiceEvent.invite}`
          : state.pendingChoiceEvent.invite
        try {
          await updateSlackMessage(
            config.apiKey,
            state.pendingChoiceMessage.channel,
            state.pendingChoiceMessage.ts,
            `${question}\n✅ *${chosenValue}*`
          )
        } catch (err) {
          this.logger('SLACK_CANAL', `Error updating choice message after selection:`, err)
        }
        state.pendingChoiceMessage = undefined
      }

      state.pendingChoiceEvent = undefined
      this.bridge!.sendChoice(threadId, chosenValue)
    }
  }

  // ─── HTTP Webhook ───────────────────────────────────────────────────────────

  private registerHttpRoutes(): void {
    this.logger('SLACK_CANAL', 'Registering Slack HTTP routes at /api/slack/events')

    this.app.get('/api/slack/health', (_req, res) => {
      res.status(200).json({ status: 'ok', timestamp: new Date().toISOString() })
    })

    // Interactivity endpoint — receives button clicks (block_actions) from Slack
    // Must be set in Slack App settings: Interactivity & Shortcuts → Request URL
    this.app.post('/api/slack/interactions', async (req, res) => {
      // Slack sends interactions as application/x-www-form-urlencoded with a `payload` field
      let payload: any
      try {
        const raw = typeof req.body?.payload === 'string' ? req.body.payload : JSON.stringify(req.body)
        payload = JSON.parse(raw)
      } catch {
        res.status(400).send('Invalid payload')
        return
      }

      if (payload.type !== 'block_actions') {
        res.status(200).send('') // ACK unsupported types
        return
      }

      // Verify signature using the team's matching project config
      const rawBody = getRawBody(req)
      const signature = req.headers[SLACK_SIGNATURE_HEADER] as string
      const timestamp = req.headers[SLACK_TIMESTAMP_HEADER] as string

      const projects = this.projectService.listProjects()
      let matchedProject: string | null = null
      let matchedConfig: SlackIntegrationConfig | null = null
      for (const p of projects) {
        const info = this.projectService.getProject(p.name)
        const cfg = (info?.config.integration?.SLACK || {}) as SlackIntegrationConfig
        if (cfg.signingSecret && verifySlackSignature(rawBody, cfg.signingSecret, signature, timestamp)) {
          matchedProject = p.name
          matchedConfig = cfg
          break
        }
      }

      // ACK immediately — Slack requires a response within 3 seconds
      res.status(200).send('')

      if (!matchedProject || !matchedConfig || !this.bridge) return

      await this.handleBlockAction(payload, matchedConfig, matchedProject).catch((err) =>
        this.logger('SLACK_CANAL', `Error handling block action:`, err)
      )
    })

    this.app.post('/api/slack/events', async (req, res) => {
      const rawBody = getRawBody(req)
      let payload: any
      try {
        payload = JSON.parse(rawBody)
      } catch {
        res.status(400).send('Invalid JSON')
        return
      }

      const signature = req.headers[SLACK_SIGNATURE_HEADER] as string
      const timestamp = req.headers[SLACK_TIMESTAMP_HEADER] as string

      // Match project by signature
      const projects = this.projectService.listProjects()
      let matchedProject: string | null = null
      for (const p of projects) {
        const info = this.projectService.getProject(p.name)
        const cfg = (info?.config.integration?.SLACK || {}) as SlackIntegrationConfig
        if (cfg.signingSecret && verifySlackSignature(rawBody, cfg.signingSecret, signature, timestamp)) {
          matchedProject = p.name
          break
        }
      }

      if (!matchedProject) {
        res.status(401).send('Invalid Slack signature')
        return
      }

      if (payload.type === 'url_verification') {
        res.status(200).send({ challenge: payload.challenge })
        return
      }

      res.status(200).send('OK') // ACK early

      if (!this.bridge) {
        this.logger('SLACK_CANAL', 'Bridge not initialized yet, ignoring event')
        return
      }

      const project = this.projectService.getProject(matchedProject)
      if (!project) return
      const config = (project.config.integration?.SLACK || {}) as SlackIntegrationConfig
      if (!config.apiKey || !config.username) return

      const event = payload.event
      if (!event) return

      const filterResult = shouldHandleMessage(event, config)
      if (!filterResult.allowed) return

      await this.handleIncomingMessage(matchedProject, config, event).catch((err) =>
        this.logger('SLACK_CANAL', `Error handling webhook event:`, err)
      )
    })
  }

  // ─── Socket Mode ─────────────────────────────────────────────────────────────

  private async initializeSocketMode(): Promise<void> {
    const projects = this.projectService.listProjects()
    for (const p of projects) {
      const info = this.projectService.getProject(p.name)
      const config = (info?.config.integration?.SLACK || {}) as SlackIntegrationConfig
      if (!config.socketMode || !config.appToken || !config.apiKey || !config.username) continue
      try {
        await this.connectProjectSocketMode(p.name, config)
      } catch (error) {
        this.logger('SLACK_CANAL', `Failed to connect project ${p.name}:`, error)
      }
    }
  }

  private async connectProjectSocketMode(projectName: string, config: SlackIntegrationConfig): Promise<void> {
    if (this.socketClients.has(projectName)) return

    this.logger('SLACK_CANAL', `Connecting project ${projectName} via Socket Mode...`)

    const socketClient = new SocketModeClient({
      appToken: config.appToken!,
      logLevel: this.codayOptions.debug ? LogLevel.DEBUG : LogLevel.INFO,
    })
    const webClient = new WebClient(config.apiKey!)
    this.socketClients.set(projectName, socketClient)
    this.webClients.set(projectName, webClient)

    socketClient.on('message', async ({ body, ack }) => {
      try {
        await ack()
        const currentProject = this.projectService.getProject(projectName)
        const currentConfig = (currentProject?.config.integration?.SLACK || {}) as SlackIntegrationConfig
        await this.handleSocketModeEvent(projectName, currentConfig, body.event, webClient)
      } catch (error) {
        this.logger('SLACK_CANAL', `Error handling Socket Mode message for ${projectName}:`, error)
      }
    })

    // Handle button clicks (block_actions) via Socket Mode
    socketClient.on('interactive', async ({ body, ack }: { body: any; ack: () => Promise<void> }) => {
      try {
        await ack()
        if (body.type !== 'block_actions') return
        const currentProject = this.projectService.getProject(projectName)
        const currentConfig = (currentProject?.config.integration?.SLACK || {}) as SlackIntegrationConfig
        await this.handleBlockAction(body, currentConfig, projectName)
      } catch (error) {
        this.logger('SLACK_CANAL', `Error handling Socket Mode block_action for ${projectName}:`, error)
      }
    })

    socketClient.on('connected', () => this.logger('SLACK_CANAL', `✓ ${projectName} connected via Socket Mode`))
    socketClient.on('disconnected', () => this.logger('SLACK_CANAL', `${projectName} disconnected`))
    socketClient.on('error', (err) => this.logger('SLACK_CANAL', `${projectName} error:`, err))

    await socketClient.start()
  }

  private async handleSocketModeEvent(
    projectName: string,
    config: SlackIntegrationConfig,
    event: any,
    webClient: WebClient
  ): Promise<void> {
    const filterResult = shouldHandleMessage(event, config)
    if (!filterResult.allowed) return

    const channel = event.channel!
    const threadKey = buildThreadKey(channel, event.thread_ts, event.ts, event.channel_type)

    if (filterResult.isChannelJoin) {
      await this.handleChannelJoin(projectName, config, channel, threadKey, webClient)
      return
    }

    await this.handleIncomingMessage(projectName, config, event)
  }

  private async handleChannelJoin(
    projectName: string,
    config: SlackIntegrationConfig,
    channel: string,
    threadKey: string,
    webClient: WebClient
  ): Promise<void> {
    if (config.threadMap?.[threadKey]) return // already exists

    let channelName = channel
    try {
      const info = await webClient.conversations.info({ channel })
      if (info.ok && info.channel?.name) channelName = info.channel.name
    } catch {}

    const handle = await this.bridge!.getOrCreateThread(
      projectName,
      config.username!,
      threadKey,
      `slack:${channelName}`,
      {} // No initial prompt for channel_join; greeting is sent via Slack API
    )

    this.attachThreadState(handle, projectName, config)
    this.persistThreadMap(projectName, config, threadKey, handle.threadId)

    try {
      await webClient.chat.postMessage({
        channel,
        text: "Hello! 👋 I'm Coday, your AI assistant. Feel free to mention me or ask me anything!",
      })
    } catch (error) {
      this.logger('SLACK_CANAL', `Failed to send greeting:`, error)
    }
  }

  // ─── Incoming message handling ────────────────────────────────────────────

  private async handleIncomingMessage(projectName: string, config: SlackIntegrationConfig, event: any): Promise<void> {
    const channel = event.channel!
    const threadKey = buildThreadKey(channel, event.thread_ts, event.ts, event.channel_type)
    const isDM = event.channel_type === 'im'

    // Extract and validate the prompt early so we can use it in thread creation
    const prompt = stripBotMention(event.text!, config.botUserId)
    if (!prompt) return

    let threadId = config.threadMap?.[threadKey]
    let state = threadId ? this.threadStates.get(threadId) : undefined

    if (!threadId) {
      if (!config.autoCreateThreads) {
        this.logger('SLACK_CANAL', `autoCreateThreads disabled, ignoring message for key "${threadKey}"`)
        return
      }

      // Resolve channel name
      let channelName = channel
      try {
        const response = await fetch(`https://slack.com/api/conversations.info?channel=${channel}`, {
          headers: { Authorization: `Bearer ${config.apiKey}` },
        })
        const data = (await response.json()) as { ok: boolean; channel?: { name?: string } }
        if (data.ok && data.channel?.name) channelName = data.channel.name
      } catch {}

      // Create the thread, passing the prompt as the initial message for the run loop
      const handle = await this.bridge!.getOrCreateThread(
        projectName,
        config.username!,
        threadKey,
        `slack:${channelName}`,
        { initialPrompt: prompt }
      )

      threadId = handle.threadId
      state = this.attachThreadState(handle, projectName, config)
      this.persistThreadMap(projectName, config, threadKey, threadId)

      this.logger('SLACK_CANAL', `Created thread ${threadId} for key "${threadKey}"`)

      // Record original message and return — run() already has the prompt via initialPrompt
      state.originalMessage = { channel, ts: event.ts!, threadTs: event.thread_ts, isDM }
      return
    } else if (!state) {
      // Thread exists in config but no state (e.g., after server restart) — re-attach without creating a new thread
      this.logger('SLACK_CANAL', `Re-attaching to existing thread ${threadId}`)
      const handle = this.bridge!.getExistingThread(threadId, projectName, config.username!)
      state = this.attachThreadState(handle, projectName, config)
    }

    // Record the original Slack message for threading replies
    state.originalMessage = { channel, ts: event.ts!, threadTs: event.thread_ts, isDM }

    // If there is a pending choice, interpret the user's message as a choice selection
    if (state.pendingChoiceEvent) {
      const choice = resolveChoice(prompt, state.pendingChoiceEvent.options)
      state.pendingChoiceEvent = undefined
      this.bridge!.sendChoice(threadId, choice)
    } else {
      // Inject the message into the existing running Coday thread
      this.bridge!.sendMessage(threadId, prompt)
    }
  }

  // ─── Thread state & event subscription ───────────────────────────────────

  /**
   * Attach Slack state to a thread handle and subscribe to its outbound events.
   * This is the key inversion: SlackCanal subscribes; the core never calls out.
   */
  private attachThreadState(
    handle: CanalThreadHandle,
    projectName: string,
    config: SlackIntegrationConfig
  ): SlackThreadState {
    const existing = this.threadStates.get(handle.threadId)
    if (existing) return existing

    const state: SlackThreadState = { projectName, handle }

    const unsubscribe = handle.onEvent((event) => {
      // Skip replayed events to avoid Slack duplicates
      if ((event as any)._isReplayed) return
      this.forwardEventToSlack(event, handle.threadId, projectName, config, state).catch((err) =>
        this.logger('SLACK_CANAL', `Error forwarding event to Slack:`, err)
      )
    })

    state.unsubscribe = unsubscribe
    this.threadStates.set(handle.threadId, state)
    return state
  }

  // ─── Outbound event forwarding (moved from ThreadCodayInstance) ───────────

  private async forwardEventToSlack(
    event: CodayEvent,
    threadId: string,
    projectName: string,
    config: SlackIntegrationConfig,
    state: SlackThreadState
  ): Promise<void> {
    if (!config.apiKey) return

    const thread = await this.threadService.getThread(projectName, threadId)
    const isSlackOriginated = thread?.name?.startsWith('slack:')
    const threadMap = config.threadMap || {}

    // ThinkingEvent — show thinking indicator
    if (event instanceof ThinkingEvent) {
      let channel: string | undefined
      let threadTs: string | undefined

      if (isSlackOriginated) {
        channel = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
      } else {
        if (!config.forwardEvents) return
        const existingKey = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
        if (existingKey) {
          ;[channel, threadTs] = existingKey.split(':')
        } else {
          channel = config.notifyChannel
        }
      }

      if (!channel) return

      const thinkingBlocks = [
        { type: 'context', elements: [{ type: 'mrkdwn', text: ':hourglass_flowing_sand: _Thinking..._' }] },
      ]

      if (state.thinkingMessage) {
        try {
          await updateSlackMessage(
            config.apiKey,
            state.thinkingMessage.channel,
            state.thinkingMessage.ts,
            ':hourglass_flowing_sand: _Thinking_',
            thinkingBlocks
          )
        } catch (err) {
          this.logger('SLACK_CANAL', `Error updating thinking indicator:`, err)
        }
      } else {
        try {
          // For DMs, post as top-level message (no thread_ts) so it's visible in the main DM view
          const effectiveThreadTs = isSlackOriginated && state.originalMessage?.isDM ? undefined : threadTs
          const response = await postSlackMessageWithBlocks(
            config.apiKey,
            channel,
            ':hourglass_flowing_sand: _Thinking_',
            thinkingBlocks,
            effectiveThreadTs
          )
          if (response.ts) state.thinkingMessage = { channel, ts: response.ts, threadTs: effectiveThreadTs }
        } catch (err) {
          this.logger('SLACK_CANAL', `Error posting thinking message:`, err)
        }
      }
      return
    }

    // ChoiceEvent — send options as interactive buttons and track pending choice
    if (event instanceof ChoiceEvent) {
      let channel: string | undefined
      let threadTs: string | undefined

      const threadMap = config.threadMap || {}

      if (isSlackOriginated) {
        channel = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
        // For DMs, do NOT set threadTs — post as top-level message so it's visible in the main DM view
        if (!state.originalMessage?.isDM && state.originalMessage?.ts) {
          threadTs = state.originalMessage.threadTs
        }
      } else {
        if (!config.forwardEvents) return
        const existingKey = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
        if (existingKey) {
          ;[channel, threadTs] = existingKey.split(':')
        } else {
          channel = config.notifyChannel
        }
      }

      if (!channel) return

      // Track the pending choice so the button interaction can be routed correctly
      state.pendingChoiceEvent = event

      // Format question text
      const question = event.optionalQuestion ? `${event.optionalQuestion}\n${event.invite}` : event.invite

      // Build Block Kit button blocks — each option becomes a button
      // action_id encodes threadId so the interaction handler can route back
      // Slack limits: button text max 75 chars, action_id max 255 chars
      const BUTTON_TEXT_MAX = 75
      const ACTION_ID_MAX = 255
      const blocks = [
        {
          type: 'section',
          text: { type: 'mrkdwn', text: question },
        },
        {
          type: 'actions',
          elements: event.options.map((opt) => {
            const buttonText = opt.length > BUTTON_TEXT_MAX ? opt.slice(0, BUTTON_TEXT_MAX - 1) + '…' : opt
            const rawActionId = `choice__${threadId}__${opt}`
            const actionId = rawActionId.length > ACTION_ID_MAX ? rawActionId.slice(0, ACTION_ID_MAX) : rawActionId
            return {
              type: 'button',
              text: { type: 'plain_text', text: buttonText, emoji: true },
              value: opt,
              action_id: actionId,
            }
          }),
        },
      ]

      // Fallback text for notifications
      const fallbackText = `${question}\n${event.options.map((o, i) => `${i + 1}. ${o}`).join('\n')}`

      try {
        const response = await postSlackMessageWithBlocks(config.apiKey, channel, fallbackText, blocks, threadTs)
        // Store the message ts so we can replace the buttons with the chosen value after selection
        if (response.ts) {
          state.pendingChoiceMessage = { channel, ts: response.ts }
        }
      } catch (err) {
        this.logger('SLACK_CANAL', `Error posting choice buttons to Slack:`, err)
      }
      return
    }

    // MessageEvent (assistant only)
    if (!(event instanceof MessageEvent) || event.role !== 'assistant') return

    const text = event.getTextContent()
    if (!text) return

    const { markdownToSlack } = await import('@coday/utils')
    const slackText = markdownToSlack(text)

    // Update thinking message if present
    if (state.thinkingMessage) {
      try {
        await updateSlackMessage(config.apiKey, state.thinkingMessage.channel, state.thinkingMessage.ts, slackText)
        state.thinkingMessage = undefined
        return
      } catch (err) {
        this.logger('SLACK_CANAL', `Error updating thinking message with response:`, err)
      }
    }

    // SCENARIO 1: Slack-originated thread → respond to originating channel
    if (isSlackOriginated) {
      const slackChannel = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
      if (slackChannel) {
        if (state.originalMessage?.isDM) {
          // For DMs: post as top-level message (no thread_ts) so it's visible in the main DM view on all devices
          await postSlackMessage(config.apiKey, slackChannel, slackText)
        } else {
          // For regular channels/threads, reply in-thread using the original message ts
          const replyThreadTs = state.originalMessage?.ts
          await postSlackMessage(config.apiKey, slackChannel, slackText, replyThreadTs)
        }
      }
      return
    }

    // SCENARIO 2: Forward events for non-Slack threads
    if (!config.forwardEvents) return

    const existingKey = Object.keys(threadMap).find((key) => threadMap[key] === threadId)
    let channel: string | undefined
    let threadTs: string | undefined

    if (existingKey) {
      ;[channel, threadTs] = existingKey.split(':')
    } else {
      channel = config.notifyChannel
    }

    if (!channel) return

    const project = this.projectService.getProject(projectName)
    const response = await postSlackMessage(config.apiKey, channel, slackText, threadTs)

    if (!existingKey && response.ts && project) {
      const newKey = `${channel}:${response.ts}`
      const updatedMap = { ...threadMap, [newKey]: threadId }
      const updatedCfg = { ...config, threadMap: updatedMap }
      const updatedInt = { ...(project.config.integration || {}), SLACK: updatedCfg }
      this.projectService.updateProjectConfig(projectName, { ...project.config, integration: updatedInt })
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private persistThreadMap(
    projectName: string,
    config: SlackIntegrationConfig,
    threadKey: string,
    threadId: string
  ): void {
    const project = this.projectService.getProject(projectName)
    if (!project) return
    const updatedMap = { ...(config.threadMap || {}), [threadKey]: threadId }
    const updatedCfg = { ...config, threadMap: updatedMap }
    const updatedInt = { ...(project.config.integration || {}), SLACK: updatedCfg }
    this.projectService.updateProjectConfig(projectName, { ...project.config, integration: updatedInt })
    // Keep local config in sync
    config.threadMap = updatedMap
  }
}

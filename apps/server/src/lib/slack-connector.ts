import { MessagingGatewayService } from '@coday/service'
import type { MessagingConnector, MessagingInboundEvent } from '@coday/model'
import { debugLog } from './log'

// ---------------------------------------------------------------------------
// Internal types
// ---------------------------------------------------------------------------

interface SlackApiResponse {
  ok: boolean
  error?: string
  [key: string]: unknown
}

interface ChannelState {
  /** When true, the connector ignores new mentions in this channel */
  paused: boolean
  /** Messages queued while the channel is currently processing */
  pendingMessages: QueuedMessage[]
  /** Whether we are currently waiting for the gateway to complete */
  processing: boolean
}

interface QueuedMessage {
  username: string
  message: string
  replyContext: Record<string, string>
  eventType?: string
  timestamp: string
}

// ---------------------------------------------------------------------------
// SlackConnector
// ---------------------------------------------------------------------------

/**
 * SlackConnector — maintains a Socket Mode WebSocket connection to Slack and
 * forwards app_mention events to the MessagingGatewayService.
 *
 * Implements MessagingConnector so the gateway can route outbound messages
 * back through this connector without knowing it's Slack.
 *
 * Thread map persistence (conversationKey → Coday threadId) is handled by the
 * MessagingGatewayService, not this connector.
 *
 * Configuration is read from the project's SLACK integration config (project.yaml):
 *   apiKey         — xoxb-... bot token
 *   appToken       — xapp-... app-level token (Socket Mode)
 *   defaultProject — default Coday project name
 *   channels       — optional channel ID → project name mapping
 */
export class SlackConnector implements MessagingConnector {
  readonly source = 'SLACK' as const

  private channelStates: Map<string, ChannelState> = new Map()
  private userEmailCache: Map<string, string> = new Map() // slackUserId -> email
  private userDisplayCache: Map<string, string> = new Map() // slackUserId -> display name
  private botUserId?: string
  private ws?: WebSocket
  private running = false
  private reconnectTimer?: ReturnType<typeof setTimeout>

  constructor(
    private readonly botToken: string,
    private readonly appToken: string,
    private readonly defaultProject: string,
    private readonly channelProjectMap: Record<string, string>,
    private readonly messagingGateway: MessagingGatewayService
  ) {}

  // -------------------------------------------------------------------------
  // MessagingConnector implementation
  // -------------------------------------------------------------------------

  async sendMessage(replyContext: Record<string, string>, text: string): Promise<void> {
    const body: Record<string, unknown> = {
      channel: replyContext.channel,
      text,
    }
    if (replyContext.thread_ts) {
      body.thread_ts = replyContext.thread_ts
    }
    await this.slackApi('chat.postMessage', {}, 'POST', body)
  }

  // -------------------------------------------------------------------------
  // Public lifecycle API
  // -------------------------------------------------------------------------

  async start(): Promise<void> {
    if (this.running) return
    this.running = true

    // Resolve the bot's own user ID so we can detect its messages later
    try {
      const authResult = await this.slackApi<{ user_id: string }>('auth.test')
      this.botUserId = authResult.user_id
      debugLog('SLACK', `Bot user ID: ${this.botUserId}`)
    } catch (err) {
      console.error('[SLACK] Failed to resolve bot user ID:', err)
    }

    await this.connectSocketMode()
  }

  async stop(): Promise<void> {
    this.running = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = undefined
    }
    if (this.ws) {
      this.ws.close()
      this.ws = undefined
    }
    debugLog('SLACK', 'Slack connector stopped')
  }

  // -------------------------------------------------------------------------
  // Socket Mode connection
  // -------------------------------------------------------------------------

  private async connectSocketMode(): Promise<void> {
    try {
      // Get a fresh WebSocket URL from Slack
      const connResult = await this.slackApiWithToken<{ url: string }>(
        this.appToken,
        'apps.connections.open',
        {},
        'POST'
      )
      const wsUrl = connResult.url
      debugLog('SLACK', 'Opening Socket Mode connection...')

      const ws = new WebSocket(wsUrl)
      this.ws = ws

      ws.onopen = () => {
        debugLog('SLACK', 'Socket Mode connection established')
      }

      ws.onmessage = (event: MessageEvent) => {
        this.handleRawMessage(event.data as string).catch((err) => {
          console.error('[SLACK] Error handling message:', err)
        })
      }

      ws.onerror = (err: Event) => {
        console.error('[SLACK] WebSocket error:', err)
      }

      ws.onclose = () => {
        debugLog('SLACK', 'Socket Mode connection closed')
        if (this.running) {
          this.scheduleReconnect()
        }
      }
    } catch (err) {
      console.error('[SLACK] Failed to connect Socket Mode:', err)
      if (this.running) {
        this.scheduleReconnect()
      }
    }
  }

  private scheduleReconnect(): void {
    debugLog('SLACK', 'Scheduling reconnect in 10s...')
    this.reconnectTimer = setTimeout(() => {
      if (this.running) {
        this.connectSocketMode().catch((err) => console.error('[SLACK] Reconnect failed:', err))
      }
    }, 10_000)
  }

  // -------------------------------------------------------------------------
  // Raw message handling
  // -------------------------------------------------------------------------

  private async handleRawMessage(raw: string): Promise<void> {
    let envelope: Record<string, unknown>
    try {
      envelope = JSON.parse(raw)
    } catch {
      return
    }

    // Acknowledge immediately to avoid Slack retrying the event
    if (envelope.envelope_id) {
      this.ws?.send(JSON.stringify({ envelope_id: envelope.envelope_id }))
    }

    // Handle Socket Mode hello
    if (envelope.type === 'hello') {
      debugLog('SLACK', 'Socket Mode hello received')
      return
    }

    // Handle events_api payloads
    if (envelope.type === 'events_api') {
      const payload = envelope.payload as Record<string, unknown> | undefined
      const event = payload?.event as Record<string, unknown> | undefined
      if (!event) return

      await this.handleSlackEvent(event)
    }
  }

  // -------------------------------------------------------------------------
  // Event handling
  // -------------------------------------------------------------------------

  private async handleSlackEvent(event: Record<string, unknown>): Promise<void> {
    const eventType = event.type as string

    if (eventType !== 'app_mention') return

    const channel = event.channel as string
    const slackUserId = event.user as string
    const rawText = (event.text as string) || ''
    const ts = event.ts as string
    const threadTs = event.thread_ts as string | undefined

    // Strip the bot mention prefix: <@U12345> ...
    const mentionRegex = /<@[A-Z0-9]+>\s*/gi
    const cleanText = rawText.replace(mentionRegex, '').trim()

    // Handle pause/resume commands
    const lowerText = cleanText.toLowerCase()
    if (lowerText === 'pause' || lowerText === 'resume') {
      this.handlePauseResume(channel, lowerText)
      return
    }

    const state = this.getChannelState(channel)

    // Ignore if channel is paused
    if (state.paused) {
      debugLog('SLACK', `Channel ${channel} is paused, ignoring mention`)
      return
    }

    // Resolve user email
    let username: string
    try {
      username = await this.resolveUserEmail(slackUserId)
    } catch (err) {
      console.error(`[SLACK] Could not resolve email for user ${slackUserId}:`, err)
      return
    }

    const replyContext: Record<string, string> = { channel, ts }
    if (threadTs) replyContext.thread_ts = threadTs

    const queued: QueuedMessage = {
      username,
      message: cleanText,
      replyContext,
      eventType: 'mentioned_in_channel',
      timestamp: ts,
    }

    if (state.processing) {
      debugLog('SLACK', `Channel ${channel} is processing, queuing message`)
      state.pendingMessages.push(queued)
      return
    }

    await this.processMessage(channel, queued)
  }

  // -------------------------------------------------------------------------
  // Message processing
  // -------------------------------------------------------------------------

  private getConversationKey(channel: string, threadTs?: string): string {
    return threadTs ? `${channel}:${threadTs}` : channel
  }

  private async processMessage(channel: string, queued: QueuedMessage): Promise<void> {
    const state = this.getChannelState(channel)
    state.processing = true

    // Post a thinking message to indicate processing
    const thinkingTs = await this.postThinkingMessage(channel, queued.replyContext.thread_ts)

    try {
      const projectName = this.channelProjectMap[channel] ?? this.defaultProject

      // Conversation key uniquely identifies this Slack context (channel or channel:thread).
      // The gateway uses it to look up or create the associated Coday thread.
      const conversationKey = this.getConversationKey(channel, queued.replyContext.thread_ts)

      // Fetch recent channel messages as ephemeral context.
      // Slack history is injected into system instructions (not stored in thread), so there is no
      // accumulation concern — we can safely fetch the full recent context on every mention.
      const context = await this.getConversationContext(channel)
      const conversationContext = context || undefined

      const inboundEvent: MessagingInboundEvent = {
        source: 'SLACK',
        username: queued.username,
        message: queued.message,
        projectName,
        replyContext: queued.replyContext,
        eventType: queued.eventType,
        conversationContext,
        targetAgent: 'Messageay',
        conversationKey,
      }

      debugLog('SLACK', `Dispatching event for ${queued.username} in ${channel} (key: ${conversationKey})`)
      await this.messagingGateway.handleEvent(inboundEvent)
    } catch (err) {
      console.error('[SLACK] Error dispatching event:', err)
    } finally {
      // Remove the thinking message
      if (thinkingTs) await this.deleteMessage(channel, thinkingTs)
      state.processing = false
      await this.processQueue(channel)
    }
  }

  private async processQueue(channel: string): Promise<void> {
    const state = this.getChannelState(channel)
    if (state.pendingMessages.length === 0) return

    // Aggregate all queued messages into a single event
    const messages = state.pendingMessages.splice(0)
    if (messages.length === 0) return

    const last = messages[messages.length - 1]!

    let aggregatedMessage: string
    if (messages.length === 1) {
      aggregatedMessage = messages[0]!.message
    } else {
      const lines = messages.map((m) => `[${this.formatTs(m.timestamp)}] ${m.username}: ${m.message}`).join('\n')
      aggregatedMessage = `Multiple messages received while processing:\n\n${lines}`
    }

    const aggregated: QueuedMessage = {
      username: last.username,
      message: aggregatedMessage,
      replyContext: last.replyContext,
      eventType: 'mentioned_in_channel',
      timestamp: last.timestamp,
    }

    await this.processMessage(channel, aggregated)
  }

  // -------------------------------------------------------------------------
  // Conversation context
  // -------------------------------------------------------------------------

  private async getConversationContext(channel: string): Promise<string> {
    try {
      // Fetch up to 150 recent messages — no delta logic needed since history is ephemeral
      // (injected into system instructions, never stored in thread).
      const params: Record<string, string> = { channel, limit: '150' }

      const result = await this.slackApi<{ messages?: Array<Record<string, unknown>> }>('conversations.history', params)

      const messages = (result.messages ?? []).filter((msg) => {
        // Exclude the bot's own thinking indicator messages
        const text = (msg.text as string) || ''
        return !text.includes(':hourglass_flowing_sand:')
      })
      if (messages.length === 0) return ''

      // Reverse to chronological order (Slack returns newest-first)
      const lines = await Promise.all(
        messages
          .slice()
          .reverse()
          .map(async (msg) => {
            const userId = msg.user as string | undefined
            const text = (msg.text as string) || ''
            const ts = (msg.ts as string) || ''

            let displayName = userId ?? 'unknown'
            if (userId) {
              displayName = await this.resolveDisplayName(userId)
            }

            const cleanedText = await this.resolveUserMentions(text)
            return `[${this.formatTs(ts)}] ${displayName ?? 'unknown'}: ${cleanedText}`
          })
      )

      return lines.join('\n')
    } catch (err) {
      debugLog('SLACK', `Failed to fetch conversation context: ${err}`)
      return ''
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private getChannelState(channel: string): ChannelState {
    if (!this.channelStates.has(channel)) {
      this.channelStates.set(channel, { paused: false, pendingMessages: [], processing: false })
    }
    return this.channelStates.get(channel)!
  }

  private handlePauseResume(channel: string, command: 'pause' | 'resume'): void {
    const state = this.getChannelState(channel)
    state.paused = command === 'pause'
    debugLog('SLACK', `Channel ${channel} ${command}d`)
  }

  // -------------------------------------------------------------------------
  // Processing indicators
  // -------------------------------------------------------------------------

  private async postThinkingMessage(channel: string, threadTs?: string): Promise<string | undefined> {
    try {
      const body: Record<string, unknown> = { channel, text: ':hourglass_flowing_sand: _Thinking..._' }
      if (threadTs) body.thread_ts = threadTs
      const result = await this.slackApi<{ ts: string }>('chat.postMessage', {}, 'POST', body)
      return result.ts
    } catch (err) {
      debugLog('SLACK', `Failed to post thinking message: ${err}`)
      return undefined
    }
  }

  private async deleteMessage(channel: string, ts: string): Promise<void> {
    try {
      await this.slackApi('chat.delete', {}, 'POST', { channel, ts })
    } catch (err) {
      debugLog('SLACK', `Failed to delete thinking message: ${err}`)
    }
  }

  // -------------------------------------------------------------------------
  // User resolution
  // -------------------------------------------------------------------------

  private async resolveUserEmail(slackUserId: string): Promise<string> {
    const cached = this.userEmailCache.get(slackUserId)
    if (cached) return cached

    const result = await this.slackApi<{
      user: { profile: { email?: string; display_name?: string; real_name?: string } }
    }>('users.info', { user: slackUserId })
    const profile = result.user.profile
    const email = profile.email ?? `${slackUserId}@slack.unknown`
    const displayName = profile.real_name ?? profile.display_name ?? slackUserId

    this.userEmailCache.set(slackUserId, email)
    this.userDisplayCache.set(slackUserId, displayName)

    return email
  }

  private async resolveDisplayName(slackUserId: string): Promise<string> {
    // Try display cache first (populated by resolveUserEmail)
    const cached = this.userDisplayCache.get(slackUserId)
    if (cached) return cached

    try {
      await this.resolveUserEmail(slackUserId)
      return this.userDisplayCache.get(slackUserId) ?? slackUserId
    } catch {
      return slackUserId
    }
  }

  /**
   * Resolve <@U12345> mentions in text to human-readable names
   */
  private async resolveUserMentions(text: string): Promise<string> {
    const mentionPattern = /<@([A-Z0-9]+)>/g
    const matches = [...text.matchAll(mentionPattern)]
    if (matches.length === 0) return text

    let resolved = text
    for (const match of matches) {
      const userId = match[1]
      if (!userId) continue
      const displayName = await this.resolveDisplayName(userId)
      resolved = resolved.replace(match[0], `@${displayName}`)
    }
    return resolved
  }

  private formatTs(ts: string): string {
    const ms = parseFloat(ts) * 1000
    if (isNaN(ms)) return ts
    const d = new Date(ms)
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }

  // -------------------------------------------------------------------------
  // Slack API helpers
  // -------------------------------------------------------------------------

  private async slackApi<T extends Omit<SlackApiResponse, 'ok'>>(
    method: string,
    params: Record<string, string> = {},
    httpMethod: 'GET' | 'POST' = 'GET',
    body?: Record<string, unknown>
  ): Promise<T & { ok: true }> {
    return this.slackApiWithToken(this.botToken, method, params, httpMethod, body)
  }

  private async slackApiWithToken<T extends Omit<SlackApiResponse, 'ok'>>(
    token: string,
    method: string,
    params: Record<string, string> = {},
    httpMethod: 'GET' | 'POST' = 'GET',
    body?: Record<string, unknown>
  ): Promise<T & { ok: true }> {
    const url = new URL(`https://slack.com/api/${method}`)

    if (httpMethod === 'GET') {
      Object.entries(params).forEach(([k, v]) => url.searchParams.append(k, v))
    }

    const options: RequestInit = {
      method: httpMethod,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': httpMethod === 'POST' ? 'application/json; charset=utf-8' : 'application/x-www-form-urlencoded',
      },
    }

    if (httpMethod === 'POST' && body) {
      options.body = JSON.stringify(body)
    } else if (httpMethod === 'POST' && Object.keys(params).length > 0) {
      // Some Slack endpoints want POST with no body (like apps.connections.open)
      options.body = undefined
    }

    const response = await fetch(url.toString(), options)
    const data = (await response.json()) as SlackApiResponse & T

    if (!data.ok) {
      throw new Error(`Slack API error [${method}]: ${data.error ?? 'unknown error'}`)
    }

    return data as T & { ok: true }
  }
}

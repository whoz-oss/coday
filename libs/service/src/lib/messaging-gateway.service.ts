import type { CodayOptions, CodayLogger, MessagingConnector, MessagingInboundEvent } from '@coday/model'
import type { ThreadService } from './thread.service'
import fs from 'fs'
import path from 'path'

// ThreadCodayManager is in apps/server, so we use a type-only import to avoid circular dependencies
// The actual instance will be injected via initialize()
type ThreadCodayManager = any

/**
 * Persisted entry for a conversation. threadId is mandatory; additional fields
 * are connector-specific metadata stored opaquely by the gateway.
 */
export interface ConversationEntry {
  threadId: string
  [key: string]: string | undefined
}

/**
 * MessagingGatewayService — bidirectional gateway for external messaging platforms.
 *
 * Inbound: receives pre-resolved MessagingInboundEvent objects from platform connectors
 * (Slack, Discord, Teams, …), creates a one-shot Coday thread, and fires the agent.
 *
 * Outbound: platform connectors register themselves via registerConnector(); agents
 * call sendMessage() to post replies without knowing the underlying platform.
 *
 * Thread map: persists "SOURCE:conversationKey" → Coday threadId associations across
 * restarts, shared across all platforms.
 */
export class MessagingGatewayService {
  private threadCodayManager?: ThreadCodayManager
  private threadService?: ThreadService
  private codayOptions?: CodayOptions
  private logger?: CodayLogger
  private connectors: Map<string, MessagingConnector> = new Map()

  /**
   * Platform-agnostic thread map: source → { conversationKey → conversation entry }.
   * Persisted to disk so associations survive server restarts.
   * Structure: { SLACK: { C123: { threadId: 'uuid', lastResponseTs: '...' } }, ... }
   * The `meta` field is opaque — connectors can store any extra state they need.
   */
  private threadMap: Record<string, Record<string, ConversationEntry>> = {}
  private threadMapPath?: string

  // -------------------------------------------------------------------------
  // Connector registry (outbound)
  // -------------------------------------------------------------------------

  registerConnector(connector: MessagingConnector): void {
    this.connectors.set(connector.source, connector)
    console.log(`[MESSAGING_GATEWAY] Registered connector: ${connector.source}`)
  }

  /**
   * Check whether a conversation key is already mapped to a Coday thread.
   * Used by connectors to decide whether to fetch bootstrap or delta context.
   */
  hasThread(source: string, conversationKey: string): boolean {
    return !!this.threadMap[source]?.[conversationKey]
  }

  /**
   * Get the full conversation entry for a given source and key.
   * Connectors use this to retrieve persisted metadata (e.g. lastResponseTs).
   */
  getConversationEntry(source: string, conversationKey: string): ConversationEntry | undefined {
    return this.threadMap[source]?.[conversationKey]
  }

  /**
   * Update metadata on an existing conversation entry.
   * Connectors call this to persist platform-specific state (e.g. lastResponseTs).
   */
  updateConversationEntry(source: string, conversationKey: string, meta: Partial<ConversationEntry>): void {
    const entry = this.threadMap[source]?.[conversationKey]
    if (entry) {
      Object.assign(entry, meta)
      this.saveThreadMap()
    }
  }

  async sendMessage(source: string, replyContext: Record<string, string>, text: string): Promise<void> {
    const connector = this.connectors.get(source)
    if (!connector) {
      throw new Error(`No messaging connector registered for source: ${source}`)
    }
    await connector.sendMessage(replyContext, text)
  }

  // -------------------------------------------------------------------------
  // Initialization
  // -------------------------------------------------------------------------

  /**
   * Initialize execution dependencies.
   * Called after server initialization with required services.
   */
  initialize(
    threadCodayManager: ThreadCodayManager,
    threadService: ThreadService,
    codayOptions: CodayOptions,
    logger: CodayLogger
  ): void {
    this.threadCodayManager = threadCodayManager
    this.threadService = threadService
    this.codayOptions = codayOptions
    this.logger = logger
    const projectName = codayOptions.project ?? 'default'
    this.threadMapPath = path.join(codayOptions.configDir, 'projects', projectName, 'messaging-thread-map.json')
    this.loadThreadMap()
  }

  private loadThreadMap(): void {
    if (!this.threadMapPath) return
    try {
      if (fs.existsSync(this.threadMapPath)) {
        const data = JSON.parse(fs.readFileSync(this.threadMapPath, 'utf-8'))
        if (data && typeof data === 'object') {
          this.threadMap = data
          const total = Object.values(this.threadMap).reduce((sum, m) => sum + Object.keys(m).length, 0)
          console.log(`[MESSAGING_GATEWAY] Loaded ${total} thread mappings from disk`)
        }
      }
    } catch (err) {
      console.error('[MESSAGING_GATEWAY] Failed to load thread map:', err)
    }
  }

  private saveThreadMap(): void {
    if (!this.threadMapPath) return
    try {
      const dir = path.dirname(this.threadMapPath)
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
      fs.writeFileSync(this.threadMapPath, JSON.stringify(this.threadMap, null, 2))
    } catch (err) {
      console.error('[MESSAGING_GATEWAY] Failed to save thread map:', err)
    }
  }

  // -------------------------------------------------------------------------
  // Inbound event handling
  // -------------------------------------------------------------------------

  /**
   * Handle an inbound messaging event.
   *
   * Validates the event, reuses or creates a Coday thread, builds a one-shot instance
   * and awaits the agent run. Returns the threadId for the connector to persist.
   *
   * @param event - Pre-resolved inbound event from the platform connector
   * @returns { threadId } — identifier of the thread used
   */
  async handleEvent(event: MessagingInboundEvent): Promise<{ threadId: string }> {
    if (!this.threadCodayManager || !this.threadService || !this.codayOptions || !this.logger) {
      throw new Error('MessagingGatewayService not initialized. Call initialize() first.')
    }

    const { source, username, message, projectName, replyContext, conversationContext, targetAgent } = event

    if (!source || !username || !message || !projectName) {
      throw new Error('Missing required fields: source, username, message, projectName')
    }

    // Resolve the Coday thread to use for this event.
    // The thread map (keyed by "SOURCE:conversationKey") persists associations across restarts.
    // The Coday instance is always recreated (oneshot pattern), but the thread YAML persists on
    // disk so the agent loads the full conversation history on each run.
    const existingEntry = event.conversationKey ? this.threadMap[source]?.[event.conversationKey] : undefined
    const existingThreadId = existingEntry?.threadId

    let threadId: string
    if (existingThreadId) {
      // Clean up any leftover in-memory instance from a previous run
      const existingInstance = this.threadCodayManager.get(existingThreadId)
      if (existingInstance) {
        await this.threadCodayManager.cleanup(existingThreadId)
      }

      // Verify the thread file still exists on disk — it may have been deleted manually
      const existingThread = await this.threadService.getThread(projectName, existingThreadId)
      if (existingThread) {
        threadId = existingThreadId
        // Ensure the current user has access
        if (!existingThread.users.some((u: { userId: string }) => u.userId === username)) {
          await this.threadService.updateThread(projectName, threadId, {
            users: [...existingThread.users, { userId: username }],
          })
        }
      } else {
        // Thread file gone — create a fresh one and treat as new conversation (full bootstrap)
        console.log(`[MESSAGING_GATEWAY] Thread ${existingThreadId} not found on disk, creating new thread`)
        const newThread = await this.threadService.createThread(projectName, username)
        threadId = newThread.id
        if (event.conversationKey) {
          if (!this.threadMap[source]) this.threadMap[source] = {}
          this.threadMap[source][event.conversationKey] = { threadId }
          this.saveThreadMap()
        }
      }
    } else {
      // createThread already adds username to users
      const thread = await this.threadService.createThread(projectName, username)
      threadId = thread.id
    }

    // Persist the mapping so future events on the same conversation reuse this thread
    if (event.conversationKey) {
      if (!this.threadMap[source]) this.threadMap[source] = {}
      if (!this.threadMap[source][event.conversationKey]) {
        this.threadMap[source][event.conversationKey] = { threadId }
        this.saveThreadMap()
      }
    }

    // Build ephemeral context (injected into AI system instructions, not stored in thread history).
    // This carries the Slack conversation history so it never accumulates in the thread.
    const ephemeralLines = [
      `## External Conversation Context`,
      `You received a mention in a ${source} channel.`,
      `Source: ${source}`,
      `Channel: ${replyContext.channel}`,
      replyContext.thread_ts ? `Thread: ${replyContext.thread_ts} (reply in this thread)` : null,
      conversationContext
        ? `\n### Conversation history leading up to this mention\nRead this carefully — it contains the discussion context, possibly with multiple participants and viewpoints:\n\n${conversationContext}\n`
        : null,
    ]
      .filter(Boolean)
      .join('\n')

    // Build the stored prompt (only the bare mention text — no Slack history stored in thread)
    const promptLines = [
      `User ${username} said: ${message}`,
      `\nReply using MESSAGING__reply — source: ${source}, channel: ${replyContext.channel}${replyContext.thread_ts ? `, thread_ts: ${replyContext.thread_ts}` : ''}.`,
    ]
      .filter(Boolean)
      .join('\n')

    const prompt = targetAgent ? `@${targetAgent} ${promptLines}` : promptLines

    const oneShotOptions: CodayOptions = {
      ...this.codayOptions,
      oneshot: true,
      project: projectName,
      thread: threadId,
      prompts: [prompt],
      ephemeralContext: ephemeralLines,
    }

    // Create instance without an SSE connection
    const instance = this.threadCodayManager.createWithoutConnection(threadId, projectName, username, oneShotOptions)

    instance.prepareCoday()

    const coday = instance.coday!

    // Run the agent and wait for completion so the caller knows when processing is done
    try {
      await coday.run()
    } catch (error: unknown) {
      console.error('[MESSAGING_GATEWAY] Error during agent run:', error)
    }

    // Explicitly save the thread after run completes.
    // coday.stop() fires autoSave() without awaiting, then cleanup() destroys the service
    // before autoSave resolves — leaving the thread empty on disk.
    // We call autoSave directly here while the service is still alive.
    try {
      await coday.aiThreadService.autoSave()
      console.log(`[MESSAGING_GATEWAY] Thread ${threadId} saved successfully`)
    } catch (saveError: unknown) {
      console.error('[MESSAGING_GATEWAY] Failed to save thread after run:', saveError)
    }

    // Schedule in-memory instance cleanup after 30 seconds.
    // The thread YAML persists on disk, so conversation history is preserved for the next run.
    setTimeout(() => {
      this.threadCodayManager!.cleanup(threadId).catch((error: unknown) => {
        console.error('[MESSAGING_GATEWAY] Error cleaning up thread after timeout:', error)
      })
    }, 30 * 1000)

    return { threadId }
  }
}

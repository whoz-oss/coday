import { Observable, of, Subject } from 'rxjs'
import {
  CodayEvent,
  ErrorEvent,
  MessageContent,
  MessageEvent,
  SummaryEvent,
  TextContent,
  ToolRequestEvent,
  ToolResponseEvent,
} from './coday-events'
import { Agent } from './agent'
import { AiThread } from './ai-thread'
import { RunStatus, ThreadMessage } from './ai-thread.types'
import { CodayLogger } from './coday-logger'
import { AiModel } from './ai-model'
import { Interactor } from './interactor'
import { AiProviderConfig } from './ai-provider-config'

export interface CompletionOptions {
  model?: string
  maxTokens?: number
  temperature?: number
  stopSequences?: string[]
}

/**
 * Common abstraction over different AI provider APIs.
 */
export abstract class AiClient {
  abstract name: string
  public models: AiModel[] = []
  protected apiKey: string | undefined
  protected abstract interactor: Interactor
  protected killed: boolean = false
  protected thinkingInterval: number = 1000
  protected charsPerToken: number = 3 // should be 4, some margin baked in to avoid overshoot on tool call
  protected username?: string

  // Timer management for proper cleanup
  private readonly activeThinkingIntervals: Set<ReturnType<typeof setInterval>> = new Set()
  private readonly activeDelays: Set<ReturnType<typeof setTimeout>> = new Set()
  private isShuttingDown = false

  protected constructor(
    protected aiProviderConfig: AiProviderConfig,
    protected logger?: CodayLogger
  ) {
    // merge the models in, ovewrite the models by aliases
    this.apiKey = aiProviderConfig.apiKey
  }

  protected mergeModels(models: AiModel[]): void {
    const modelsByAliasOrName = new Map<string, AiModel>(models.map((m) => [m.alias ?? m.name, m]))
    this.aiProviderConfig.models?.forEach((m) => modelsByAliasOrName.set(m.alias ?? m.name, m))
    this.models = Array.from(modelsByAliasOrName.values())
  }

  /**
   * Run the AI with the given configuration and thread context.
   *
   * @param agent Complete agent configuration including model, system instructions, and tools
   * @param thread Current thread containing conversation history and managing message state
   * @returns Observable stream of events from the AI interaction (messages, tool calls, tool responses)
   */
  abstract run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>>

  /**
   * Simple completion method for generating short, focused text.
   * Used for tasks like thread naming, summaries, etc.
   *
   * @param prompt The prompt to complete
   * @param options Optional completion parameters
   * @returns The completed text
   */
  abstract complete(prompt: string, options?: CompletionOptions): Promise<string>

  /**
   * Kills the process, deprecating
   * Will be replaced by aiThread.runStatus...
   */
  kill(): void {
    this.killed = true
    this.cleanup()
  }

  /**
   * Cleanup all active timers and intervals
   * Called during shutdown or kill to prevent memory leaks
   */
  cleanup(): void {
    this.isShuttingDown = true

    // Clear all active delays
    for (const timeout of this.activeDelays) {
      clearTimeout(timeout)
    }
    this.activeDelays.clear()

    // Clear all thinking intervals
    for (const interval of this.activeThinkingIntervals) {
      clearInterval(interval)
    }
    this.activeThinkingIntervals.clear()
  }

  /**
   * Create an interruptible delay that can be cancelled during shutdown
   * @param ms Delay in milliseconds
   * @param reason Optional reason for logging
   * @returns Promise that resolves after delay or rejects if interrupted
   */
  protected async delay(ms: number, reason?: string): Promise<void> {
    if (this.isShuttingDown) {
      throw new Error('Client is shutting down')
    }

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.activeDelays.delete(timeout)
        resolve()
      }, ms)

      this.activeDelays.add(timeout)

      // Check for shutdown during delay
      if (this.isShuttingDown) {
        clearTimeout(timeout)
        this.activeDelays.delete(timeout)
        reject(new Error(`Delay interrupted by shutdown${reason ? `: ${reason}` : ''}`))
      }
    })
  }

  /**
   * Start a thinking interval with automatic cleanup tracking
   * @returns The interval handle
   */
  protected startThinkingInterval(): ReturnType<typeof setInterval> {
    const interval = setInterval(() => this.interactor.thinking(), this.thinkingInterval)
    this.activeThinkingIntervals.add(interval)
    return interval
  }

  /**
   * Stop a thinking interval and remove from tracking
   * @param interval The interval to stop
   */
  protected stopThinkingInterval(interval: ReturnType<typeof setInterval>): void {
    clearInterval(interval)
    this.activeThinkingIntervals.delete(interval)
  }

  private getCompactor(model: string, maxChars: number): (messages: ThreadMessage[]) => Promise<SummaryEvent> {
    return async (messages: ThreadMessage[]): Promise<SummaryEvent> => {
      this.interactor.debug(`🗜️ Starting compaction for ${messages.length} messages (budget: ${maxChars} chars)`)

      // If budget is too small, return standard message
      if (maxChars < 500) {
        this.interactor.warn(`⚠️ Budget too small for compaction (${maxChars} chars). ` + 'Using truncation marker.')
        return new SummaryEvent({ summary: '[Previous conversation truncated due to context window constraints]' })
      }

      // Build the initial transcript
      const fullTranscript = messages
        // without the tool request and response, hypothesis is we can do without and simply the "text"
        .filter((m) => m instanceof MessageEvent)
        .map((m) => ` - ${m.role}: ${m.getTextContent()}`)
        .join('\n')

      this.interactor.debug(
        `📝 Built transcript from ${messages.filter((m) => m instanceof MessageEvent).length} messages ` +
          `(${fullTranscript.length} chars)`
      )

      const summaryBudget = Math.max(100, Math.floor(maxChars / 20))

      // Calculate safe transcript size
      // Account for: prompt template (~150 chars) + response budget + safety margin (20%)
      const promptTemplateOverhead = 150
      const safetyMargin = 0.2
      const maxTranscriptChars = Math.max(
        0,
        Math.floor((maxChars - promptTemplateOverhead - summaryBudget) * (1 - safetyMargin))
      )

      this.interactor.debug(
        `📊 Compaction budget: summary=${summaryBudget} chars, ` +
          `max transcript=${maxTranscriptChars} chars (overhead=${promptTemplateOverhead}, margin=${Math.round(safetyMargin * 100)}%)`
      )

      // Limit transcript size to prevent context window overflow
      let transcript = fullTranscript
      let wasTruncated = false

      if (fullTranscript.length > maxTranscriptChars) {
        // Truncate from the beginning to keep most recent content
        transcript = '...' + fullTranscript.slice(-(maxTranscriptChars - 3))
        wasTruncated = true

        this.interactor.debug(
          `✂️ Transcript truncated: ${fullTranscript.length} → ${transcript.length} chars ` +
            `(removed ${fullTranscript.length - transcript.length} chars from beginning)`
        )
      }

      const prompt = `Here is a transcript of a conversation:
<transcript>${transcript}</transcript>

It can be summarized as:
<summary>
`

      this.interactor.debug(`🤖 Calling completion API for summary (model: ${model}, max tokens: ${summaryBudget})`)

      let summary: string
      try {
        const response = await this.complete(prompt, {
          model,
          maxTokens: summaryBudget,
        })

        // Extract content between <summary> tags
        const match = response.match(/<summary>([\s\S]*?)<\/summary>/)
        if (match && match[1]) {
          summary = match[1].trim()
        } else {
          // Fallback: use the entire response if no tags found
          summary = response.trim() || '...previous conversation truncated'
        }

        const truncatedInfo = wasTruncated ? ' (from truncated transcript)' : ''
        this.interactor.debug(
          `✅ Compaction successful: ${messages.length} messages → ${summary.length} chars summary${truncatedInfo}`
        )
      } catch (e) {
        summary = '[Previous conversation - compaction failed, recent messages available]'
        console.error('Compaction failed:', e)

        const errorDetails = e instanceof Error ? e.message : 'Unknown error'
        this.interactor.warn(
          `❌ Compaction failed (${errorDetails}). ` +
            `Transcript: ${transcript.length} chars, Budget: ${maxChars} chars. ` +
            'Using truncation marker.'
        )
      }

      // Return SummaryEvent
      return new SummaryEvent({ summary })
    }
  }

  /**
   * Converts messages from other agents into user messages with XML agent tags.
   * This ensures LLMs can distinguish between the current agent's messages and
   * messages from other agents in multi-agent conversations.
   *
   * Messages from the current agent keep their 'assistant' role.
   * Messages from other agents are converted to 'user' role with:
   * `<agent=AgentName>original content</agent>`
   *
   * @param messages The thread messages to process
   * @param currentAgentName The name of the currently running agent
   * @returns Messages with other-agent messages converted to tagged user messages
   */
  /**
   * Strips @agentName prefixes from user messages before sending to the LLM.
   * The @agent syntax is used for routing in the UI/handler layer but is noise
   * for the LLM: it doesn't need to know how the message was dispatched.
   *
   * Only strips a leading `@word` from text content — leaves the rest intact.
   */
  protected stripAgentPrefixes(messages: ThreadMessage[]): ThreadMessage[] {
    return messages.map((msg) => {
      if (msg instanceof MessageEvent && msg.role === 'user') {
        const strippedContent = msg.content.map((c) => {
          if (c.type === 'text') {
            const stripped = c.content.replace(/^@\S+\s*/, '')
            return stripped !== c.content ? { ...c, content: stripped } : c
          }
          return c
        })
        // Only rebuild the MessageEvent if something actually changed
        const changed = strippedContent.some((c, i) => c !== msg.content[i])
        if (changed) {
          return new MessageEvent({ ...msg, content: strippedContent })
        }
      }
      return msg
    })
  }

  protected convertAgentMessages(messages: ThreadMessage[], currentAgentName: string): ThreadMessage[] {
    return messages.map((msg) => {
      if (msg instanceof MessageEvent && msg.role === 'assistant' && msg.name && msg.name !== currentAgentName) {
        // Wrap text content in XML agent tags, preserve images as-is
        const convertedContent: MessageContent[] = msg.content.map((c) => {
          if (c.type === 'text') {
            return {
              type: 'text' as const,
              content: `<agent=${msg.name}>${c.content}</agent>`,
            }
          }
          return c
        })
        return new MessageEvent({
          ...msg,
          role: 'user',
          content: convertedContent,
        })
      }
      return msg
    })
  }

  /**
   * Filters out tool request/response pairs from the message history that are
   * not part of the current agent's toolset.
   *
   * When agents switch in a conversation, the thread may contain tool calls
   * made by previous agents. Sending those to the current agent's LLM is
   * confusing: the model thinks it has access to tools it doesn't, and may
   * try to call them or misinterpret their results.
   *
   * Pairs are always removed together to keep request/response consistency.
   *
   * @param messages The thread messages to filter
   * @param allowedToolNames Set of tool names the current agent has access to
   */
  protected filterUnknownToolCalls(messages: ThreadMessage[], allowedToolNames: Set<string>): ThreadMessage[] {
    // Collect IDs of tool requests that are NOT in the allowed set
    const foreignRequestIds = new Set<string>(
      messages
        .filter((msg) => msg instanceof ToolRequestEvent && !allowedToolNames.has(msg.name))
        .map((msg) => (msg as ToolRequestEvent).toolRequestId)
    )
    if (foreignRequestIds.size === 0) return messages
    // Remove both the foreign requests and their paired responses
    return messages.filter((msg) => {
      if (msg instanceof ToolRequestEvent) return !foreignRequestIds.has(msg.toolRequestId)
      if (msg instanceof ToolResponseEvent) return !foreignRequestIds.has(msg.toolRequestId)
      return true
    })
  }

  protected async getMessages(
    thread: AiThread,
    charBudget: number,
    model: string,
    agentName?: string,
    allowedToolNames?: Set<string>
  ): Promise<{
    messages: ThreadMessage[]
    compacted: boolean
  }> {
    const compactor = this.getCompactor(model, charBudget)
    const result = await thread.getMessages(charBudget, compactor)
    result.messages = this.stripAgentPrefixes(result.messages)
    if (allowedToolNames) {
      result.messages = this.filterUnknownToolCalls(result.messages, allowedToolNames)
    }
    if (agentName) {
      result.messages = this.convertAgentMessages(result.messages, agentName)
    }
    return result
  }

  /**
   * Builds the message event, add it to the thread, emit it and return its timestamp
   * @param thread
   * @param text
   * @param agent
   * @param subscriber
   * @protected
   */
  protected handleText(
    thread: AiThread,
    text: string | undefined,
    agent: Agent,
    subscriber: Subject<CodayEvent>
  ): string | undefined {
    if (text) {
      const content: TextContent = { type: 'text', content: text }
      const messageEvent = new MessageEvent({
        role: 'assistant',
        content: [content],
        name: agent.name,
      })
      thread.addAgentMessage(agent.name, content)
      subscriber.next(messageEvent)
      return messageEvent.timestamp
    }
    return undefined
  }

  protected async shouldProcessAgainAfterResponse(
    _text: string | undefined,
    toolRequests: ToolRequestEvent[] | undefined,
    agent: Agent,
    thread: AiThread
  ): Promise<boolean> {
    if (!toolRequests?.length) return false

    // Simple threshold check
    const { usage } = thread
    if (
      (usage.price > 0 && usage.price >= usage.priceThreshold) ||
      (usage.price === 0 && usage.iterations >= usage.iterationsThreshold)
    ) {
      const thresholdType = usage.price > 0 ? 'cost' : 'iteration'
      const current = usage.price > 0 ? `${usage.price.toFixed(2)}` : usage.iterations
      const limit = usage.price > 0 ? `${usage.priceThreshold.toFixed(2)}` : usage.iterationsThreshold

      const explanation =
        `${thresholdType} threshold reached (${current} >= ${limit}).
` +
        'Proceeding will:\n' +
        `- Double the ${thresholdType} limit\n` +
        '- Continue processing with the current context\n\n' +
        'Stopping will:\n' +
        '- End the current run\n' +
        '- Clear pending commands\n' +
        '- Return to prompt'

      const choice = await this.interactor.chooseOption(['proceed', 'stop'], explanation, 'What do you want to do?')

      if (choice === 'stop') {
        thread.runStatus = RunStatus.STOPPED
        return false
      }

      // Double relevant threshold
      if (usage.price > 0) {
        usage.priceThreshold *= 2
        this.interactor.displayText(`Cost threshold increased to ${usage.priceThreshold.toFixed(2)}`)
      } else {
        usage.iterationsThreshold *= 2
        this.interactor.displayText(`Iteration threshold increased to ${usage.iterationsThreshold}`)
      }
    }

    // Normal tool processing
    await Promise.all(
      toolRequests.map(async (request) => {
        let responseEvent: ToolResponseEvent
        try {
          this.interactor.sendEvent(request)
          responseEvent = await agent.tools.run(request)
        } catch (error: any) {
          const errorMessage = `Error running tool ${request.name}: ${error}`
          console.error(errorMessage)
          responseEvent = request.buildResponse(errorMessage)
        }
        this.interactor.sendEvent(responseEvent)
        thread.addToolRequests(agent.name, [request])
        thread.addToolResponseEvents([responseEvent])
      })
    )
    return this.shouldProceed(thread)
  }

  /**
   * Standardized error handling for AI clients
   * @param error The error object
   * @param subscriber The event subscriber
   * @param providerName Name of the AI provider for better error context
   * @protected
   */
  protected handleError(error: unknown, subscriber: Subject<CodayEvent>, providerName: string): void {
    // Create a user-friendly error message based on error type
    let userMessage = `${providerName} error occurred`

    if (error instanceof Error) {
      userMessage = `${providerName} error: ${error.message}`

      // Handle common API errors with more specific messages
      const err = error as any // Using any for specific error properties
      if (err.status === 401 || err.statusCode === 401) {
        userMessage = `${providerName} authentication failed. Please check your API key.`
      } else if (err.status === 429 || err.statusCode === 429) {
        userMessage = `${providerName} rate limit exceeded. Please try again later.`
      } else if (err.code === 'ECONNREFUSED' || err.code === 'ENOTFOUND' || err.code === 'ETIMEDOUT') {
        userMessage = `Network error connecting to ${providerName}. Please check your internet connection.`
      } else if (error.message?.includes('Max tokens')) {
        userMessage = `${providerName} response exceeded token limit. Try simplifying your request or splitting it into smaller parts.`
      }
    }

    // Log the full error for debugging
    console.error(`${providerName} client error:`, error)

    // Send the error event to the UI
    subscriber.next(
      new ErrorEvent({
        error: error instanceof Error ? error : new Error(userMessage),
      })
    )

    // Also display the error directly to the user
    this.interactor.displayText(`\u26a0\ufe0f ${userMessage}`)
  }

  protected shouldProceed(thread: AiThread): boolean {
    return thread.runStatus === RunStatus.RUNNING && !this.killed
  }

  protected showAgentAndUsage(agent: Agent, aiProvider: string, model: string, thread: AiThread): void {
    const agentPart = `🤖 ${agent.name} | ${aiProvider} - ${model} `
    if (!thread.usage) return
    const loop = `Loop${thread.usage.iterations > 1 ? 's' : ''}: ${thread.usage.iterations} | `
    const tokensIO = `Input: ${thread.usage.input}, Output: ${thread.usage.output} | `
    const cacheIO = `Cache write: ${thread.usage.cache_write}, Cache read: ${thread.usage.cache_read}`
    const price = `🏃$${thread.usage.price.toFixed(3)} / 🧵$${thread.price.toFixed(3)}`
    this.interactor.displayText(agentPart + price)
    this.interactor.debug(loop + tokensIO + cacheIO)
  }

  protected getModel(agent: Agent): AiModel | undefined {
    const aliasOrName = agent.definition.modelName?.toLowerCase()
    const byAlias = this.models.find((m) => m.alias?.toLowerCase() === aliasOrName)
    if (byAlias) return byAlias

    // default case, return the model that might correspond per model name, or undefined
    return this.models.find((m) => m.name.toLowerCase() === aliasOrName)
  }

  supportsModel(name: string): boolean {
    return this.models.some((model) => model.name.toLowerCase() === name || model?.alias?.toLowerCase() === name)
  }

  returnError(error: string): Observable<CodayEvent> {
    return of(new ErrorEvent({ error }))
  }

  /**
   * Log agent usage after a complete response cycle
   */
  protected logAgentUsage(agent: Agent, model: string, cost: number): void {
    this.logger?.logAgentUsage(this.username ?? 'no_username', agent.name, model, cost)
  }
}

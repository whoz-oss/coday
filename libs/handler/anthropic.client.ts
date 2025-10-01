import { Agent, AiClient, AiModel, AiProviderConfig, CompletionOptions, Interactor } from '../model'
import Anthropic from '@anthropic-ai/sdk'
import { ToolSet } from '../integration/tool-set'
import { CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { Observable, of, Subject } from 'rxjs'
import { AiThread } from '../ai-thread/ai-thread'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { CodayLogger } from '../service/coday-logger'

interface RateLimitInfo {
  inputTokensRemaining: number
  outputTokensRemaining: number
  requestsRemaining: number
  inputTokensLimit: number
  outputTokensLimit: number
  requestsLimit: number
}

const ANTHROPIC_DEFAULT_MODELS: AiModel[] = [
  {
    name: 'claude-sonnet-4-5-20250929',
    alias: 'BIG',
    contextWindow: 200000,
    price: {
      inputMTokens: 3,
      cacheWrite: 3.75,
      cacheRead: 0.3,
      outputMTokens: 15,
    },
  },
  {
    name: 'claude-3-5-haiku-latest',
    alias: 'SMALL',
    contextWindow: 200000,
    price: {
      inputMTokens: 0.8,
      cacheWrite: 1,
      cacheRead: 0.08,
      outputMTokens: 4,
    },
  },
]

const MAX_THROTTLING_DELAY = 60
const THROTTLING_THRESHOLD = 0.4

// Cache marker strategy constants
const CACHE_MARKER_PLACEMENT_RATIO = 0.9
const CACHE_MARKER_UPDATE_THRESHOLD = 0.5
const CACHE_MIN_MESSAGES = 5

export class AnthropicClient extends AiClient {
  name: string
  private rateLimitInfo: RateLimitInfo | null = null

  constructor(
    readonly interactor: Interactor,
    aiProviderConfig: AiProviderConfig,
    logger: CodayLogger
  ) {
    super(aiProviderConfig, logger)
    this.name = 'Anthropic'
    this.mergeModels(ANTHROPIC_DEFAULT_MODELS)
  }

  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    const anthropic: Anthropic | undefined = this.isAnthropicReady()
    const model = this.getModel(agent)
    if (!anthropic || !model) {
      return of()
    }

    thread.resetUsageForRun()
    const outputSubject: Subject<CodayEvent> = new Subject()
    const thinking = setInterval(() => this.interactor.thinking(), this.thinkingInterval)
    this.processThread(anthropic, agent, model, thread, outputSubject).finally(() => {
      clearInterval(thinking)
      this.showAgentAndUsage(agent, 'Anthropic', model.name, thread)
      // Log usage after the complete response cycle
      const cost = thread.usage?.price || 0
      this.logAgentUsage(agent, model.name, cost)
      outputSubject.complete()
    })
    return outputSubject
  }

  private async processThread(
    client: Anthropic,
    agent: Agent,
    model: AiModel,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    // Apply throttling delay if needed
    await this.applyThrottlingDelay()

    // Recalculate budget on each iteration to account for growing thread
    const initialContextCharLength = agent.systemInstructions.length + agent.tools.charLength + 20
    const charBudget = Math.max(model.contextWindow * this.charsPerToken - initialContextCharLength, 10000)

    // API call with localized error handling
    let response: Anthropic.Messages.Message
    let httpResponse: Response
    try {
      const result = await this.makeApiCall(client, model, agent, thread, charBudget)
      response = result.data
      httpResponse = result.response
    } catch (error: any) {
      // Handle 429 rate limit errors with retry
      if (error.status === 429 && error.headers) {
        const retryAfter = parseInt(error.headers['retry-after'] || '60')
        this.interactor.displayText(`⏳ Rate limit hit. Waiting ${retryAfter} seconds before retry...`)

        // Update rate limits from error headers
        this.updateRateLimitsFromHeaders(error.headers)

        // Show which limit was hit
        this.displayRateLimitStatus(error.headers)

        // Wait and retry once
        await new Promise((resolve) => setTimeout(resolve, retryAfter * 1000))

        this.interactor.displayText(`🔄 Retrying request...`)

        try {
          const retryResult = await this.makeApiCall(client, model, agent, thread, charBudget)
          response = retryResult.data
          httpResponse = retryResult.response
        } catch (retryError: any) {
          // If retry also fails, handle as normal error
          this.handleError(retryError, subscriber, this.name)
          return
        }
      } else {
        // Other errors propagate immediately
        this.handleError(error, subscriber, this.name)
        return
      }
    }

    // Update rate limits from successful response headers
    this.updateRateLimitsFromHeaders(httpResponse?.headers)

    this.updateUsage(response?.usage, agent, thread)

    if (response.stop_reason === 'max_tokens') throw new Error('Max tokens reached for Anthropic 😬')

    const text = response.content
      .filter((block) => block.type === 'text')
      .map((block) => block.text.trim())
      .filter((t) => !!t)
      .join('\n')
    this.handleText(thread, text, agent, subscriber)

    const toolRequests = response.content
      .filter((block) => block.type === 'tool_use')
      .map(
        (block) =>
          new ToolRequestEvent({
            toolRequestId: block.id,
            name: block.name,
            args: JSON.stringify(block.input),
          })
      )
    if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread)) {
      // Continue with tool processing - recursive call is now outside try-catch
      await this.processThread(client, agent, model, thread, subscriber)
    }
  }

  private updateUsage(usage: any, agent: Agent, thread: AiThread): void {
    const model = this.getModel(agent)
    const input = (usage?.input_tokens || 0) * (model?.price?.inputMTokens || 0)
    const output = (usage?.output_tokens || 0) * (model?.price?.outputMTokens || 0)
    const cacheWrite = (usage?.cache_creation_input_tokens || 0) * (model?.price?.cacheWrite || 0)
    const cacheRead = (usage?.cache_read_input_tokens || 0) * (model?.price?.cacheRead || 0)
    const price = (input + output + cacheWrite + cacheRead) / 1_000_000

    thread.addUsage({
      input: usage?.input_tokens ?? 0,
      output: usage?.output_tokens ?? 0,
      cache_read: usage?.cache_read_input_tokens ?? 0,
      cache_write: usage?.cache_creation_input_tokens ?? 0,
      price,
    })
  }

  private isAnthropicReady(): Anthropic | undefined {
    if (!this.apiKey) {
      this.interactor.warn('ANTHROPIC_API_KEY not set, skipping AI command. Please configure your API key.')
      return
    }

    try {
      return new Anthropic({
        apiKey: this.apiKey,
        /**
         * Special beta header to enable prompt caching
         */
        defaultHeaders: { ['anthropic-beta']: 'prompt-caching-2024-07-31' },
      })
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.warn(`Failed to initialize Anthropic client: ${errorMessage}`)
      console.error('Anthropic client initialization error:', error)
      return
    }
  }

  /**
   * Convert a ThreadMessage to Claude's MessageParam format
   * Includes mobile cache marker optimization
   */
  private toClaudeMessage(
    messages: ThreadMessage[],
    thread: AiThread,
    updateCache: boolean = false
  ): Anthropic.MessageParam[] {
    // Get or update the cache marker position
    const markerMessageId = this.getOrUpdateCacheMarker(thread, messages, updateCache)

    return messages
      .map((msg, index) => {
        let claudeMessage: Anthropic.MessageParam | undefined
        const shouldAddCache = markerMessageId && msg.timestamp === markerMessageId
        if (msg instanceof MessageEvent) {
          const isLastUserMessage = msg.role === 'user' && index === messages.length - 1
          const message = msg as MessageEvent
          const content = this.enhanceWithCurrentDateTime(message.content, isLastUserMessage)
          const claudeContent: (Anthropic.ImageBlockParam | Anthropic.TextBlockParam)[] = content
            .map((c, index) => {
              let result: Anthropic.ImageBlockParam | Anthropic.TextBlockParam | undefined = undefined
              if (c.type === 'text') {
                // Coday TextContent already matches the Claude type, how convenient...
                result = {
                  type: 'text',
                  text: c.content,
                }
              }
              if (c.type === 'image') {
                // Convert Coday ImageContent to Claude ImageBlockParam
                result = {
                  type: 'image',
                  source: {
                    type: 'base64',
                    media_type: c.mimeType as 'image/jpeg' | 'image/png' | 'image/gif' | 'image/webp',
                    data: c.content,
                  },
                }
              }
              if (!result) {
                // Fallback for unknown content types
                this.interactor.warn(`Unknown content type: ${(c as any).type}`)
                return null
              }
              return {
                ...result,
                // add cache marker on last element of content
                ...(shouldAddCache && index === content.length - 1 && { cache_control: { type: 'ephemeral' } }),
              }
            })
            // cast forced by cache_control type unduly generalized as string instead of 'ephemeral'
            .filter(Boolean) as (Anthropic.ImageBlockParam | Anthropic.TextBlockParam)[]
          // Structure message with content blocks for cache_control support

          claudeMessage = {
            role: msg.role,
            content: claudeContent,
          }
        }
        if (msg instanceof ToolRequestEvent) {
          claudeMessage = {
            role: 'assistant',
            content: [
              {
                type: 'tool_use',
                id: msg.toolRequestId,
                name: msg.name,
                input: JSON.parse(msg.args),
                ...(shouldAddCache && { cache_control: { type: 'ephemeral' } }),
              },
            ],
          }
        }
        if (msg instanceof ToolResponseEvent) {
          let toolResultContent: string | (Anthropic.ImageBlockParam | Anthropic.TextBlockParam)[]

          if (typeof msg.output === 'string') {
            // Simple string output
            toolResultContent = msg.output
          } else {
            // Rich content (MessageContent)
            const content = msg.output
            if (content.type === 'text') {
              toolResultContent = [
                {
                  type: 'text' as const,
                  text: content.content,
                },
              ]
            } else if (content.type === 'image') {
              toolResultContent = [
                {
                  type: 'image' as const,
                  source: {
                    type: 'base64' as const,
                    media_type: content.mimeType,
                    data: content.content,
                  },
                },
              ]
            } else {
              throw new Error(`Unknown content type: ${(content as any).type}`)
            }
          }

          claudeMessage = {
            role: 'user',
            content: [
              {
                type: 'tool_result',
                tool_use_id: msg.toolRequestId,
                content: toolResultContent,
                ...(shouldAddCache && { cache_control: { type: 'ephemeral' } }),
              },
            ],
          }
        }

        return claudeMessage
      })
      .filter((m) => !!m)
  }

  /**
   * Get or update the cache marker position using mobile marker strategy
   * Places marker at CACHE_MARKER_PLACEMENT_RATIO% of conversation,
   * repositions when it drops below CACHE_MARKER_UPDATE_THRESHOLD%
   */
  private getOrUpdateCacheMarker(thread: AiThread, messages: ThreadMessage[], updateCache: boolean): string | null {
    const messageCount = messages.length

    // No cache marker for short conversations
    if (messageCount < CACHE_MIN_MESSAGES) {
      this.interactor.debug(`📋 Cache: Conversation too short (${messageCount} messages), no marker`)
      return null
    }

    // Initialize anthropic data if needed
    if (!thread.data.anthropic) {
      thread.data.anthropic = {}
    }

    // Get current marker
    const currentMarkerId = thread.data.anthropic.cacheMarkerMessageId

    if (currentMarkerId && !updateCache) {
      // Find current marker position
      const markerIndex = messages.findIndex((m) => m.timestamp === currentMarkerId)
      if (markerIndex !== -1) {
        const ratio = markerIndex / messageCount
        const percentage = Math.round(ratio * 100)
        const minPercentage = Math.round(CACHE_MARKER_UPDATE_THRESHOLD * 100)
        const maxPercentage = Math.round(CACHE_MARKER_PLACEMENT_RATIO * 100)

        // Keep marker if it's still in the valid range
        if (ratio >= CACHE_MARKER_UPDATE_THRESHOLD && ratio <= CACHE_MARKER_PLACEMENT_RATIO) {
          this.interactor.debug(
            `✅ Cache: Keeping marker at message ${markerIndex + 1}/${messageCount} (${percentage}%)`
          )
          return currentMarkerId
        }

        this.interactor.debug(
          `🔄 Cache: Marker at ${percentage}% is out of range (${minPercentage}-${maxPercentage}%), repositioning...`
        )
      } else {
        this.interactor.debug(`⚠️ Cache: Marker message not found, creating new marker`)
      }
    } else {
      this.interactor.debug(`🆕 Cache: No existing marker, creating first marker`)
    }

    // Place/move marker to configured position
    const newIndex = Math.floor(messageCount * CACHE_MARKER_PLACEMENT_RATIO)
    const newMarkerId = messages[newIndex]?.timestamp
    const newPercentage = Math.round((newIndex / messageCount) * 100)

    if (newMarkerId) {
      thread.data.anthropic.cacheMarkerMessageId = newMarkerId
      this.interactor.debug(
        `📍 Cache: Placed marker at message ${newIndex + 1}/${messageCount} (${newPercentage}%) - ID: ${newMarkerId}`
      )
      return newMarkerId
    }

    this.interactor.debug(`❌ Cache: Failed to place marker at position ${newIndex}`)
    return null
  }

  /**
   * Map tool definitions to match Anthropic's API
   * Adds cache_control to tools since they're static throughout the conversation
   *
   * @param toolSet
   * @private
   */
  private getClaudeTools(toolSet: ToolSet) {
    const tools = toolSet.getTools().map((t) => ({
      name: t.function.name,
      description: t.function.description,
      input_schema: t.function.parameters,
    }))

    // Add cache_control to the last tool (covers all tools)
    if (tools.length > 0) {
      ;(tools[tools.length - 1] as any).cache_control = { type: 'ephemeral' }
    }

    return tools
  }

  /**
   * Make the actual API call to Anthropic
   * Extracted to avoid code duplication between initial call and retry
   */
  private async makeApiCall(
    client: Anthropic,
    model: AiModel,
    agent: Agent,
    thread: AiThread,
    charBudget: number
  ): Promise<{ data: Anthropic.Messages.Message; response: Response }> {
    const data = await this.getMessages(thread, charBudget, model.name)
    const messages = this.toClaudeMessage(data.messages, thread)
    return await client.messages
      .create({
        model: model.name,
        messages,
        system: [
          {
            text: agent.systemInstructions,
            type: 'text',
            cache_control: { type: 'ephemeral' },
          },
        ] as unknown as Array<Anthropic.TextBlockParam>,
        tools: this.getClaudeTools(agent.tools),
        temperature: agent.definition.temperature ?? 0.8,
        max_tokens: 8192,
      })
      .withResponse()
  }

  private getHeader = (headers: Headers | Record<string, string> | undefined, key: string): string | null => {
    if (!headers) {
      return null
    }
    if (headers instanceof Headers) {
      return headers.get(key)
    } else {
      return headers[key] || null
    }
  }

  /**
   * Update rate limit information from headers
   * Only create rateLimitInfo if we have actual numeric rate limit headers.
   */
  private updateRateLimitsFromHeaders(headers: Headers | Record<string, string> | undefined): void {
    if (!headers) {
      this.rateLimitInfo = null
      return
    }

    // Try to get rate limit headers
    const inputTokensRemaining = this.getHeader(headers, 'anthropic-ratelimit-input-tokens-remaining')
    const outputTokensRemaining = this.getHeader(headers, 'anthropic-ratelimit-output-tokens-remaining')
    const requestsRemaining = this.getHeader(headers, 'anthropic-ratelimit-requests-remaining')
    const inputTokensLimit = this.getHeader(headers, 'anthropic-ratelimit-input-tokens-limit')
    const outputTokensLimit = this.getHeader(headers, 'anthropic-ratelimit-output-tokens-limit')
    const requestsLimit = this.getHeader(headers, 'anthropic-ratelimit-requests-limit')

    // Only create rateLimitInfo if we have at least one valid numeric header
    // Check that we have actual numeric values, not just null/undefined
    const hasValidRemainingHeaders =
      (inputTokensRemaining && !isNaN(parseInt(inputTokensRemaining))) ||
      (outputTokensRemaining && !isNaN(parseInt(outputTokensRemaining))) ||
      (requestsRemaining && !isNaN(parseInt(requestsRemaining)))

    if (hasValidRemainingHeaders) {
      this.rateLimitInfo = {
        inputTokensRemaining: inputTokensRemaining ? parseInt(inputTokensRemaining) : 999999,
        outputTokensRemaining: outputTokensRemaining ? parseInt(outputTokensRemaining) : 999999,
        requestsRemaining: requestsRemaining ? parseInt(requestsRemaining) : 999999,
        inputTokensLimit: inputTokensLimit ? parseInt(inputTokensLimit) : 200000,
        outputTokensLimit: outputTokensLimit ? parseInt(outputTokensLimit) : 80000,
        requestsLimit: requestsLimit ? parseInt(requestsLimit) : 4000,
      }
    } else {
      // No valid rate limit headers = no throttling
      this.rateLimitInfo = null
    }
  }

  /**
   * Apply throttling delay based on current rate limits
   * Simple approach: if we have rate limit info and limits are low, throttle. Otherwise, no throttling.
   */
  private async applyThrottlingDelay(): Promise<void> {
    // No rate limit info = no throttling
    if (!this.rateLimitInfo) {
      return
    }

    // Calculate remaining ratios
    const inputTokensRatio = this.rateLimitInfo.inputTokensRemaining / Math.max(this.rateLimitInfo.inputTokensLimit, 1)
    const outputTokensRatio =
      this.rateLimitInfo.outputTokensRemaining / Math.max(this.rateLimitInfo.outputTokensLimit, 1)
    const requestsRatio = this.rateLimitInfo.requestsRemaining / Math.max(this.rateLimitInfo.requestsLimit, 1)

    // Debug logging
    this.interactor.debug(
      `Rate limit ratios: ${JSON.stringify({
        inputTokensRatio,
        outputTokensRatio,
        requestsRatio,
        rateLimitInfo: this.rateLimitInfo,
      })}`
    )

    // Find the most restrictive limit
    const minRatio = Math.min(inputTokensRatio, outputTokensRatio, requestsRatio)

    // Throttle if any limit is below threshold
    if (minRatio < THROTTLING_THRESHOLD) {
      const proportion = (THROTTLING_THRESHOLD - minRatio) / THROTTLING_THRESHOLD
      const delaySeconds = Math.max(1, Math.round(proportion * MAX_THROTTLING_DELAY))

      this.interactor.displayText(
        `⏳ Rate limit approaching (${Math.round(minRatio * 100)}% remaining), waiting ${delaySeconds} seconds...`
      )
      await new Promise((resolve) => setTimeout(resolve, delaySeconds * 1000))
    }
  }

  /**
   * Display which rate limit was hit
   */
  private displayRateLimitStatus(headers: any): void {
    const inputRemaining = parseInt(headers['anthropic-ratelimit-input-tokens-remaining'] || '0')
    const outputRemaining = parseInt(headers['anthropic-ratelimit-output-tokens-remaining'] || '0')
    const requestsRemaining = parseInt(headers['anthropic-ratelimit-requests-remaining'] || '0')

    let limitType = 'unknown'
    if (inputRemaining === 0) limitType = 'input tokens'
    else if (outputRemaining === 0) limitType = 'output tokens'
    else if (requestsRemaining === 0) limitType = 'requests'

    this.interactor.debug(`🚨 Rate limit exceeded: ${limitType} limit reached`)
  }

  /**
   * Enhanced error handling for Anthropic-specific errors
   * Note: 429 errors are now handled locally in processThread, so this method
   * focuses on other error types and provides Anthropic-specific context.
   */
  protected handleError(error: unknown, subscriber: Subject<CodayEvent>, providerName: string): void {
    // Use the parent implementation for all errors
    // 429 errors are handled locally in processThread before reaching here
    super.handleError(error, subscriber, providerName)
  }

  async complete(prompt: string, options?: CompletionOptions): Promise<string> {
    const anthropic = this.isAnthropicReady()
    if (!anthropic) throw new Error('Anthropic client not ready')

    // Select model: options > SMALL alias > fallback
    const modelName = options?.model || this.models.find((m) => m.alias === 'SMALL')?.name || 'claude-3-5-haiku-latest'

    try {
      const response = await anthropic.messages.create({
        model: modelName,
        messages: [{ role: 'user', content: prompt }],
        max_tokens: options?.maxTokens ?? 100,
        temperature: options?.temperature ?? 0.5,
        stop_sequences: options?.stopSequences,
      })

      const text = response.content
        .filter((block) => block.type === 'text')
        .map((block) => block.text.trim())
        .join(' ')

      return text
    } catch (error: any) {
      console.error('Anthropic completion error:', error)
      throw new Error(`Anthropic completion failed: ${error.message}`)
    }
  }
}

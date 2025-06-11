import { Agent, AiClient, AiModel, AiProviderConfig, Interactor } from '../model'
import Anthropic from '@anthropic-ai/sdk'
import { MessageParam } from '@anthropic-ai/sdk/resources'
import { ToolSet } from '../integration/tool-set'
import { CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { Observable, of, Subject } from 'rxjs'
import { AiThread } from '../ai-thread/ai-thread'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { TextBlockParam } from '@anthropic-ai/sdk/resources/messages'
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
    name: 'claude-sonnet-4-20250514',
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
    const initialContextCharLength = agent.systemInstructions.length + agent.tools.charLength + 20
    const charBudget = model.contextWindow * this.charsPerToken - initialContextCharLength

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

        // Retry the API call once
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
   */
  private toClaudeMessage(messages: ThreadMessage[]): MessageParam[] {
    return messages
      .map((msg, index) => {
        let claudeMessage: MessageParam | undefined
        if (msg instanceof MessageEvent) {
          const isLastUserMessage = msg.role === 'user' && index === messages.length - 1
          const content = this.enhanceWithCurrentDateTime(msg.content, isLastUserMessage)

          claudeMessage = { role: msg.role, content }
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
              },
            ],
          }
        }
        if (msg instanceof ToolResponseEvent) {
          claudeMessage = {
            role: 'user',
            content: [
              {
                type: 'tool_result',
                tool_use_id: msg.toolRequestId,
                content: msg.output,
              },
            ],
          }
        }
        return claudeMessage
      })
      .filter((m) => !!m)
  }

  /**
   * Map tool definitions to match Anthropic's API
   *
   * @param toolSet
   * @private
   */
  private getClaudeTools(toolSet: ToolSet) {
    return toolSet.getTools().map((t) => ({
      name: t.function.name,
      description: t.function.description,
      input_schema: t.function.parameters,
    }))
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
    return await client.messages
      .create({
        model: model.name,
        messages: this.toClaudeMessage(thread.getMessages(charBudget)),
        system: [
          {
            text: agent.systemInstructions,
            type: 'text',
            cache_control: { type: 'ephemeral' },
          },
        ] as unknown as Array<TextBlockParam>,
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
    console.log('DEBUG: Rate limit ratios:', {
      inputTokensRatio,
      outputTokensRatio,
      requestsRatio,
      rateLimitInfo: this.rateLimitInfo
    })

    // Find the most restrictive limit
    const minRatio = Math.min(inputTokensRatio, outputTokensRatio, requestsRatio)

    // Throttle if any limit is below 20%
    if (minRatio < 0.2) {
      const delaySeconds = Math.max(1, Math.round((0.2 - minRatio) * 20))
      console.log('DEBUG: Throttling calculation:', { minRatio, delaySeconds, formula: `(0.2 - ${minRatio}) * 20 = ${(0.2 - minRatio) * 20}` })
      
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
}

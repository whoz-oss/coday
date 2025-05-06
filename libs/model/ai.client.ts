import { Observable, Subject } from 'rxjs'
import { CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { Agent } from './agent'
import { AiThread } from '../ai-thread/ai-thread'
import { RunStatus } from '../ai-thread/ai-thread.types'
import { Interactor } from './interactor'
import { AgentDefinition, ModelSize } from './agent-definition'
import { AiModel } from './ai-model'
import { AiProviderConfig } from './ai-provider-config'
import { CodayLogger } from '../service/coday-logger'

/**
 * Common abstraction over different AI provider APIs.
 */
export abstract class AiClient {
  abstract name: string
  protected models: AiModel[] = []
  protected apiKey: string | undefined
  protected abstract interactor: Interactor
  protected killed: boolean = false
  protected defaultModelSize: ModelSize = ModelSize.BIG
  protected thinkingInterval: number = 3000
  protected charsPerToken: number = 3.5 // should be 4, some margin baked in to avoid overshoot on tool call
  protected logger?: CodayLogger
  protected username?: string

  protected constructor(aiProviderConfig: AiProviderConfig) {
    // merge the models in, ovewrite the models by aliases
    const modelsByAliasOrName = new Map<string, AiModel>(this.models.map((m) => [m.alias ?? m.name, m]))
    aiProviderConfig.models?.forEach((m) => modelsByAliasOrName.set(m.alias ?? m.name, m))
    this.models = Array.from(modelsByAliasOrName.values())
    this.apiKey = aiProviderConfig.apiKey
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
   * Kills the process, deprecating
   * Will be replaced by aiThread.runStatus...
   */
  kill(): void {
    this.killed = true
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
      const messageEvent = new MessageEvent({
        role: 'assistant',
        content: text,
        name: agent.name,
      })
      thread.addAgentMessage(agent.name, text)
      subscriber.next(messageEvent)
      return messageEvent.timestamp
    }
    return
  }

  protected async shouldProcessAgainAfterResponse(
    text: string | undefined,
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
          this.interactor.sendEvent(responseEvent)
        } catch (error: any) {
          console.error(`Error running tool ${request.name}:`, error)
          responseEvent = request.buildResponse(`Error: ${error.message}`)
        }
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

  /**
   * Enhances the last user message with current date/time information.
   * This provides transient temporal context that is always current,
   * avoiding persistence issues and pattern copying problems.
   *
   * @param content The original message content
   * @param isLastUserMessage Whether this is the last user message in the thread
   * @returns Enhanced content with date/time if applicable, otherwise original content
   */
  protected enhanceWithCurrentDateTime(content: string, isLastUserMessage: boolean): string {
    if (!isLastUserMessage) return content

    const now = new Date()
    const currentDate = now.toISOString().split('T')[0]
    const currentTime = now.toLocaleTimeString('en-US', {
      hour12: false,
      timeZone: 'UTC',
    })

    return `${content}\n\n[Current date: ${currentDate}, time: ${currentTime} UTC]`
  }

  protected showAgentAndUsage(agent: Agent, aiProvider: string, model: string, thread: AiThread): void {
    const agentPart = `🤖 ${agent.name} | ${aiProvider} - ${model}  \n`
    if (!thread.usage) return
    const loop = `🔁${thread.usage.iterations} | `
    const tokensIO = `Tokens ⬇️${thread.usage.input} ⬆️${thread.usage.output} | `
    const cacheIO = `Cache ${thread.usage.cache_write ? `✍️${thread.usage.cache_write} ` : ''}📖${thread.usage.cache_read} | `
    const price = `💸 🏃$${thread.usage.price.toFixed(3)} 🧵$${thread.price.toFixed(3)}`
    this.interactor.displayText(agentPart + loop + tokensIO + cacheIO + price)
  }

  protected getModelSize(agent: Agent): ModelSize {
    return agent.definition.modelSize ?? this.defaultModelSize
  }

  protected getModel(agent: AgentDefinition): AiModel | undefined {
    const aliasOrName = agent.modelName
    const byAlias = this.models.find((m) => m.alias === aliasOrName)
    if (byAlias) return byAlias

    // default case, return the model that might correspond per model name, or undefined
    return this.models.find((m) => m.name === aliasOrName)
  }

  supportsModel(name: string): boolean {
    return this.models.some((model) => model.name === name || model.alias === name)
  }

  /**
   * Set the usage logger for this AI client
   */
  setLogger(logger: CodayLogger, username: string): void {
    this.logger = logger
    this.username = username
  }

  /**
   * Log agent usage after a complete response cycle
   */
  protected async logAgentUsage(agent: Agent, model: string, cost: number): Promise<void> {
    if (!this.logger || !this.username) return

    try {
      await this.logger.logAgentUsage(this.username, agent.name, model, cost)
    } catch (error) {
      // Silent failure - logging should never disrupt the main flow
      console.warn('Failed to log agent usage:', error)
    }
  }
}

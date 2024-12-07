import {Observable, Subject} from "rxjs"
import {CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {Agent} from "./agent"
import {AiThread} from "../ai-thread/ai-thread"
import {RunStatus} from "../ai-thread/ai-thread.types"
import {Interactor} from "./interactor"
import {ModelSize} from "./agent-definition"

/**
 * Common abstraction over different AI provider APIs.
 */
export abstract class AiClient {
  protected abstract interactor: Interactor
  protected killed: boolean = false
  protected defaultModelSize: ModelSize = ModelSize.BIG

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

  protected handleText(
    thread: AiThread,
    text: string | undefined,
    agent: Agent,
    subscriber: Subject<CodayEvent>
  ): void {
    if (text) {
      thread.addAgentMessage(agent.name, text)
      subscriber.next(
        new MessageEvent({
          role: 'assistant',
          content: text,
          name: agent.name,
        })
      )
    }
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
    if ((usage.price > 0 && usage.price >= usage.priceThreshold) ||
        (usage.price === 0 && usage.iterations >= usage.iterationsThreshold)) {
        
        const thresholdType = usage.price > 0 ? 'cost' : 'iteration'
        const current = usage.price > 0 ? `${usage.price.toFixed(2)}` : usage.iterations
        const limit = usage.price > 0 ? `${usage.priceThreshold.toFixed(2)}` : usage.iterationsThreshold

        const explanation = `${thresholdType} threshold reached (${current} >= ${limit}).
` +
            'Proceeding will:\n' +
            `- Double the ${thresholdType} limit\n` +
            '- Continue processing with the current context\n\n' +
            'Stopping will:\n' +
            '- End the current run\n' +
            '- Clear pending commands\n' +
            '- Return to prompt'

        const choice = await this.interactor.chooseOption(
            ['proceed', 'stop'],
            explanation,
            'What do you want to do?'
        )

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
          responseEvent = await agent.tools.run(request)
        } catch (error: any) {
          console.error(`Error running tool ${request.name}:`, error)
          responseEvent = request.buildResponse(`Error: ${error.message}`)
        }
        thread.addToolRequests(agent.name, [request])
        thread.addToolResponseEvents([responseEvent])
      })
    )

    return thread.runStatus === RunStatus.RUNNING && !this.killed
  }

  protected showUsage(thread: AiThread): void {
    if (!thread.usage) return
    const loop = `🔁${thread.usage.iterations} | `
    const tokensIO = `Tokens ⬇️${thread.usage.input} ⬆️${thread.usage.output} | `
    const cacheIO = `Cache ${thread.usage.cache_write ? `✍️${thread.usage.cache_write} ` : ''}📖${thread.usage.cache_read} | `
    const price = `💸 🏃$${thread.usage.price.toFixed(3)} 🧵$${thread.price.toFixed(3)}`
    this.interactor.displayText(loop + tokensIO + cacheIO + price)
  }

  protected getModelSize(agent: Agent): ModelSize {
    return agent.definition.modelSize ?? this.defaultModelSize
  }
}

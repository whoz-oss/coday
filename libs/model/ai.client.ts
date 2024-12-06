import { Observable, Subject } from 'rxjs'
import { CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '../shared/coday-events'
import { Agent } from './agent'
import { AiThread } from '../ai-thread/ai-thread'
import { RunStatus } from '../ai-thread/ai-thread.types'
import { Interactor } from './interactor'
import { ModelSize } from './agent-definition'

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

  protected showPrice(price: number): void {
    if (price === 0) return
    this.interactor.displayText(`$${price.toFixed(3)}`)
  }

  protected getModelSize(agent: Agent): ModelSize {
    return agent.definition.modelSize ?? this.defaultModelSize
  }
}

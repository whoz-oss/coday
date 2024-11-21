import {CommandContext} from "../model"
import {AiProvider} from "./agent-definition"
import {Observable, Subject} from "rxjs"
import {CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {Agent} from "./agent"
import {AiThread} from "../ai-thread/ai-thread"
import {RunStatus} from "../ai-thread/ai-thread.types"

/**
 * Common abstraction over different AI provider APIs.
 */
export abstract class AiClient {
  abstract aiProvider: AiProvider
  abstract multiAssistant: boolean
  protected killed: boolean = false
  
  /**
   * Run the AI with the given configuration and thread context.
   *
   * @param agent Complete agent configuration including model, system instructions, and tools
   * @param thread Current thread containing conversation history and managing message state
   * @returns Observable stream of events from the AI interaction (messages, tool calls, tool responses)
   */
  abstract answer2(
    agent: Agent,
    thread: AiThread
  ): Promise<Observable<CodayEvent>>
  
  /**
   * Adds a user-issued message to the Openai thread
   *
   * Usable only with the Openai assistant API.
   * Should be deprecated in favor of an in-house thread management
   *
   * @param message the user message to add to the thread
   * @param context
   */
  abstract addMessage(
    message: string,
    context: CommandContext,
  ): Promise<void>
  
  /**
   * Answer the command by querying the assistant by its name
   *
   * Should be deprecated in favor of a stateless signature like:
   *   answer(agent: Agent, thread: AiThread): Promise<void>
   *
   * @param name of the assistant called (openai-specific)
   * @param command prompt sent to the assistant
   * @param context
   */
  abstract answer(
    name: string,
    command: string,
    context: CommandContext,
  ): Promise<string>
  
  /**
   * Protected utility to check if thread is stopped
   *
   * @param thread The thread being processed
   * @returns true if processing should stop
   */
  protected shouldStop(thread: AiThread): boolean {
    return thread.runStatus === RunStatus.STOPPED
  }
  
  /**
   * Start processing on a thread
   */
  protected start(thread: AiThread): void {
    thread.runStatus = RunStatus.RUNNING
  }
  
  /**
   * Forgets the thread data for a client reset
   *
   * Used only for Openai and Gemini, deprecating
   */
  abstract reset(): void
  
  /**
   * Kills the process, deprecating
   * Will be replaced by aiThread.runStatus...
   */
  kill(): void {
    this.killed = true
  }
  
  protected async shouldProcessAgainAfterResponse(text: string | undefined, toolRequests: ToolRequestEvent[] | undefined, agent: Agent, thread: AiThread, subscriber: Subject<CodayEvent>): Promise<boolean> {
    if (text) {
      thread.addAgentMessage(agent.name, text)
      subscriber.next(new MessageEvent({
        role: "assistant",
        content: text,
        name: agent.name
      }))
    }
    if (toolRequests?.length) {
      // Emit requests and process responses in parallel
      await Promise.all(toolRequests.map(async request => {
        let responseEvent: ToolResponseEvent
        try {
          responseEvent = await agent.tools.run(request)
        } catch (error: any) {
          console.error(`Error running tool ${request.name}:`, error)
          responseEvent = request.buildResponse(`Error: ${error.message}`)
        }
        thread.addToolRequests(agent.name, [request])
        thread.addToolResponseEvents([responseEvent])
      }))
      
      // Check interruption before recursion
      if (this.mustStop(thread)) return false
      
      return true
    }
    return false
  }
  
  private mustStop(thread: AiThread): boolean {
    return thread.runStatus === RunStatus.STOPPED || this.killed
  }
}
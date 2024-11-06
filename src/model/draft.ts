import {CodayEvent} from "shared/coday-events"
import {Project} from "./project"
import {Observable, of} from "rxjs"
import {AiClient} from "./ai.client"
import {AgentDefinition, AiProvider} from "./agent-definition"

interface Agentic {
  work(input: WorkInput): Observable<CodayEvent>
}

/**
 * Agent class embodiment
 *
 * Agent exposes its name and description and accepts to work on a request
 */
class Agent implements Agentic {
  
  constructor(
    private agent: AgentDefinition,
    aiClient: AiClient,
    project: Project,
    readonly internal: boolean,
  ) {
    // define tools at this stage ?
  }
  
  /**
   * Returns name of the agent, for selection and presentation
   */
  get name(): string {
    return this.agent.name
  }
  
  /**
   * Returns description of agent, for presentation
   */
  get description(): string {
    return this.agent.description
  }
  
  
  work(input: WorkInput): Observable<CodayEvent> {
    // aiClient should prepare the thread (summarize) or the agent ?
    return of()
  }
  
}

/**
 *
 */
interface WorkInput {
  prompt: string,
  thread: AiThread,
  options: {
    teammates: { name: string, description: string }[]
  }
}

/**
 * One or more agents constitutes a Team
 *
 * A default team should always exist with all defined agents.
 *
 * A team implicitly has a supervisor for routing and ensuring a request is finally answered (ala `iterate` handler).
 * Unless there is only a single agent...
 *
 * Should have an implicit supervisor LLM able to direct to the correct Agent or sub-team ?
 */
class Team implements Agentic {
  
  constructor(
    readonly name: string,
    readonly agents: Agent[],
    supervisor: Agent
  ) {
  }
  
  work(input: WorkInput): Observable<CodayEvent> {
    // parse the prompt to find a destinee agent
    // if none, route through supervisor
    
    return of()
  }
  
}

/**
 * Wrapper around the ai-provider-specific details
 *
 * Stores the ai-provider-specific messages (or reference for openai) for re-injection in next run
 * Could be multi-ai-provider ala context.data garbage object ?
 * Then how would one provider add messages to others ? Through simple event conversion ?
 * Lazy messages conversion based on events ? Need for cursor for each ai provider to track the last handled messages.
 *
 * Should manage the token length against a custom limit, and trigger a summarization when over (through events ?)
 * Summarization of each message or directly chunks ?
 *
 * Could store the events for re-load of saved thread
 * Threads would be stored in their folder
 * Hydration to take care of
 *
 * Options:
 * - only store relevant codayEvents and convert on-demand by the client <= preferred
 * - store ai-provider specific blocks, but how to sync ? Through codayEvents ?
 *
 * Threads should be named automatically (events ?)
 * Need for a utility aiclient to handle naming and summarization in full text
 *
 * Autosave ?
 * Think save locally (1 yml / thread) ?
 * Think save remotely (1 entry for thread, 1 collection for events) ?
 */
class AiThread {
  /**
   * Defines whether the Agents can call the user
   */
  interactive: boolean = false
  
  /**
   * Reference to the parentThread
   */
  parentThread?: AiThread
  
  /**
   * Full history of messages
   * To be used for thread re-load in frontend ?
   */
  messages: ThreadMessage[] = []
  
  /**
   * Shortened histories:
   *   - one per AiProvider (different constraints)
   *   - events added the same (so will grow beyond limits
   *   - lazy summarization, triggered only when called ?
   * Exposed as responsability of the aiClient to manage summarization according to his settings
   * Entry for `null` is full history, never accessed ?
   */
  messagesByAiProvider: Map<AiProvider, ThreadMessage[]> = new Map()
  
  constructor(readonly id: string, defaultAiClient: AiClient, title: string) {
    // how to re-construct the thread from saved yml ?
  }
  
  addMessage(message: ThreadMessage): void {
    this.messages.push(message)
    this.messagesByAiProvider.forEach((threadMessages) => threadMessages.push(message))
  }
  
  /**
   * Returns the thread messages matching the given options
   * @param options the aiClient to use for aiProvider (selecting the right message stack) and the options to trigger summarization
   */
  getMessages(options?: { aiClient: AiClient, maxChars: number }): ThreadMessage[] {
    if (!options) {
      // then return the full history
      return [...this.messages]
    }
    
    // TODO: check thread size against length, then summarize if needed
    // logic for summarization of thread to put here
    
    return this.messagesByAiProvider.get(options.aiClient.aiProvider) ?? []
  }
  
}

/**
 * Model of messages composing a generic AiThread
 *
 * Loosely adapted from Claude API
 */
type ThreadMessage = UserMessage | SystemMessage

type UserMessage = {
  role: "user"
  content: TextMessage | ToolResponse
}

type SystemMessage = {
  role: "system"
  content: TextMessage | ToolCall
}

type ThreadMessageContent = TextMessage | ToolCall | ToolResponse

interface TextMessage {
  type: "TEXT"
  text: string
}

interface ToolCall {
  type: "TOOL_CALL"
  id: string
  name: string
  args: string
}

interface ToolResponse {
  type: "TOOL_RESPONSE"
  toolCallId: string
  response: string
}
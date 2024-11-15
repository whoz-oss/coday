import {CodayEvent} from "shared/coday-events"
import {Project} from "./project"
import {Observable, of} from "rxjs"
import {AiClient} from "./ai.client"
import {AgentDefinition} from "./agent-definition"
import {AiThread} from "../ai-thread/ai-thread"

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

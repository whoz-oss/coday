import { AiClient } from './ai.client'
import { AgentDefinition } from './agent-definition'
import { Project } from './project'
import { ToolSet } from '../integration/tool-set'
import { AiThread } from '../ai-thread/ai-thread'
import { Observable } from 'rxjs'
import { CodayEvent } from '../shared/coday-events'

/**
 * Agent class represents an AI agent with specific capabilities and responsibilities.
 * It encapsulates the logic for processing requests through an AI client with
 * its specific configuration and tools.
 */
export class Agent {
  readonly name: string
  readonly description: string

  constructor(
    readonly definition: AgentDefinition,
    private readonly aiClient: AiClient,
    private readonly project: Project,
    readonly tools: ToolSet,
    readonly internal: boolean = false
  ) {
    this.name = definition.name
    this.description = definition.description
  }

  /**
   * Process a work request through this agent
   *
   * @returns Observable stream of events from the processing
   * @param command text written by the user
   * @param thread
   */
  async run(command: string, thread: AiThread): Promise<Observable<CodayEvent>> {
    thread.addUserMessage('user', command)

    return this.aiClient.run(this, thread)
  }

  get systemInstructions(): string {
    return `${this.definition.instructions}\n\n
                <project-context>
${this.project.description}
</project-context>`
  }
}

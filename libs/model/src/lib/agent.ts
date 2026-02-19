import { AiClient } from './ai.client'
import { AiThread } from './ai-thread'
import { Observable } from 'rxjs'
import { AnswerEvent, CodayEvent } from './coday-events'
import { AgentDefinition } from './agent-definition'
import { ToolSet } from './integration-tool-set'

/**
 * Simplified view of an agent for listing and selection purposes
 */
export interface AgentSummary {
  name: string
  description: string
}

/**
 * Agent class represents an AI agent with specific capabilities and responsibilities.
 * It encapsulates the logic for processing requests through an AI client with
 * its specific configuration and tools.
 */
export class Agent {
  readonly name: string
  readonly description: string
  definition: AgentDefinition

  constructor(
    initialDefinition: AgentDefinition,
    private readonly aiClient: AiClient,
    readonly tools: ToolSet,
    readonly internal: boolean = false
  ) {
    this.name = initialDefinition.name
    this.description = initialDefinition.description
    this.definition = { ...initialDefinition }
  }

  get systemInstructions(): string {
    return this.definition.instructions!
  }

  /**
   * Access to the agent's AI client for completion operations
   */
  getAiClient(): AiClient {
    return this.aiClient
  }

  /**
   * Process a work request through this agent
   *
   * @returns Observable stream of events from the processing
   * @param command text written by the user (may include @agentName)
   * @param thread
   */
  async run(command: string, thread: AiThread): Promise<Observable<CodayEvent>> {
    const trimmedCommand = command.trim()

    // Add AnswerEvent to thread with the original command (including @agentName if present)
    // This preserves the user's original input in the thread history
    const answerEvent = new AnswerEvent({ answer: trimmedCommand })
    thread.addAnswerEvent(answerEvent)

    // Run with AI client
    return await this.aiClient.run(this, thread)
  }
}

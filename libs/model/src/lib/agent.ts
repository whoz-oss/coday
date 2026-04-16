import { AiClient } from './ai.client'
import { AiThread } from './ai-thread'
import { Observable, ReplaySubject } from 'rxjs'
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
  async run(command: string, thread: AiThread, username?: string): Promise<Observable<CodayEvent>> {
    if (!thread) {
      throw new Error(
        `[Agent:${this.name}] Cannot run: thread context is undefined. Ensure initThread() completed before agent execution.`
      )
    }
    const trimmedCommand = command.trim()

    // Add AnswerEvent to thread with the original command (including @agentName if present)
    // This preserves the user's original input in the thread history
    const answerEvent = new AnswerEvent({ answer: trimmedCommand, name: username ?? thread.username })
    thread.addAnswerEvent(answerEvent)

    // Run with AI client
    const events = await this.aiClient.run(this, thread)

    // Bridge through a ReplaySubject to prevent event loss when the AI client
    // completes before subscribers attach (race condition with fast models - issue #508).
    const replay = new ReplaySubject<CodayEvent>()
    events.subscribe(replay)
    return replay.asObservable()
  }
}

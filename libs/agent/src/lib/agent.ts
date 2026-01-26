import { AiClient } from './ai.client'
import { AiThread } from '@coday/ai-thread'
import { Observable } from 'rxjs'
import { CodayEvent } from '@coday/model'
import { AgentDefinition, ModelSize } from '@coday/model'
import { ToolSet } from '@coday/model'

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
   * @param command text written by the user
   * @param thread
   */
  async run(command: string, thread: AiThread): Promise<Observable<CodayEvent>> {
    // Trim the command
    const trimmedCommand = command.trim()

    // Handle model size change
    const currentModelSize = this.definition.modelSize || ModelSize.SMALL
    let processedCommand = trimmedCommand
    let newModelSize = currentModelSize

    if (trimmedCommand.startsWith('+') && currentModelSize === ModelSize.SMALL) {
      newModelSize = ModelSize.BIG
      processedCommand = trimmedCommand.slice(1).trim()
    } else if (trimmedCommand.startsWith('-') && currentModelSize === ModelSize.BIG) {
      newModelSize = ModelSize.SMALL
      processedCommand = trimmedCommand.slice(1).trim()
    }

    // Update model size
    this.definition.modelSize = newModelSize

    // Add processed command to thread
    // TODO: assess whether we can make the command a MessageContent array: containing text and images
    thread.addUserMessage('user', { type: 'text', content: processedCommand })

    // Run with updated configuration
    return await this.aiClient.run(this, thread)
  }
}

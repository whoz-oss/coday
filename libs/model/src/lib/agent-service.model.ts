import { Agent, AgentSummary } from './agent'
import { CommandContext } from './command-context'

export interface AgentServiceModel {
  listAgentSummaries(): AgentSummary[]
  initialize(context: CommandContext): Promise<void>
  /**
   * Find an agent by exact name match (case insensitive)
   * Uses lazy loading - creates agent on-demand if not in cache
   */
  findByName(name: string, context: CommandContext): Promise<Agent | undefined>
  findAgentByNameStart(nameStart: string | undefined, context: CommandContext): Promise<Agent | undefined>
  /**
   * Find agents by the start of their name (case insensitive)
   * For example, "fid" will match "Fido_the_dog"
   * Returns all matching agents or empty array if none found
   * Uses lazy loading - creates agents on-demand if not in cache
   */
  findAgentsByNameStart(nameStart: string, context: CommandContext): Promise<Agent[]>
  /**
   * Get the user's preferred agent for the current project
   * @returns The name of the preferred agent or undefined if not set
   */
  getPreferredAgent(): string | undefined
  kill(): Promise<void>
  // addDefinition(def: AgentDefinition, basePath: string): void
  /**
   * Generate virtual agents from all available AI models.
   * These agents provide direct access to models without custom instructions or tools restrictions.
   * They are named exactly as the model name (e.g., 'gpt-4o', 'claude-sonnet-4.5').
   */
  // generateVirtualAgentsFromModels(): void
  /**
   * Load agent definitions from all configured agent folders:
   * - ~/.coday/[project]/agents/ folder
   * - folder next to coday.yaml
   * - folders specified in coday.yaml agentFolders
   * - folders specified via command line options
   * Each file should contain a single agent definition
   */
  // loadFromFiles(context: CommandContext): Promise<void>
  /**
   * Try to create and add an agent (lazy loading)
   * Logs error if dependencies are missing
   */
  // tryAddAgent(
  //   entry: {
  //     definition: AgentDefinition
  //     basePath: string
  //   },
  //   context: CommandContext
  // ): Promise<Agent | undefined>
}

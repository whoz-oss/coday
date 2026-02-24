import { Killable } from './killable'
import { CodayTool } from './coday-tool'
import { Interactor } from './interactor'
import { CommandContext } from './command-context'
import { IntegrationConfig } from './integration-config'

export abstract class AssistantToolFactory implements Killable {
  tools: CodayTool[] = []

  // name is the INSTANCE name (e.g., "jira-prod")
  // Will be set by concrete classes in their constructor
  name: string

  // config holds the integration configuration for this instance
  protected config?: IntegrationConfig

  /**
   * Hook for clean up on session kill.
   * Default implementation has nothing to clean-up.
   * @protected
   */
  async kill(): Promise<void> {
    return
  }

  protected constructor(
    protected interactor: Interactor,
    instanceName: string,
    config?: IntegrationConfig
  ) {
    this.name = instanceName
    this.config = config
  }

  protected abstract buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]>

  async getTools(context: CommandContext, toolNames: string[], agentName: string): Promise<CodayTool[]> {
    this.tools = await this.buildTools(context, agentName)
    return this.tools.filter((tool) => {
      if (!toolNames || !toolNames.length) return true
      // tool names are 'INTEGRATION__toolName', allowedTools list uses short names ('toolName')
      const shortName = tool.function.name.split('__')[1] ?? tool.function.name
      return toolNames.includes(shortName)
    })
  }
}

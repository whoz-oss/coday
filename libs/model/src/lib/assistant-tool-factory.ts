import { Killable } from './killable'
import { CodayTool } from './coday-tool'
import { Interactor } from './interactor'
import { CommandContext } from './command-context'

export abstract class AssistantToolFactory implements Killable {
  tools: CodayTool[] = []
  abstract name: string

  /**
   * Hook for clean up on session kill.
   * Default implementation has nothing to clean-up.
   * @protected
   */
  async kill(): Promise<void> {
    return
  }

  protected constructor(protected interactor: Interactor) {}

  protected abstract buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]>

  async getTools(context: CommandContext, toolNames: string[], agentName: string): Promise<CodayTool[]> {
    this.tools = await this.buildTools(context, agentName)
    return this.tools.filter((tool) => !toolNames || !toolNames.length || toolNames.includes(tool.function.name))
  }
}

import { Killable } from '@coday/model'
import { CodayTool } from '@coday/model'
import { Interactor } from '@coday/model'
import { CommandContext } from '@coday/handler'

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

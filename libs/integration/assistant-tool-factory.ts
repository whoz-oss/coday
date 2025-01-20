import { CommandContext, Interactor } from '../model'
import { FunctionTool } from './types'

export type CodayTool = FunctionTool<any>

export abstract class AssistantToolFactory {
  tools: CodayTool[] = []
  lastToolInitContext: CommandContext | null = null
  abstract name: string

  protected constructor(protected interactor: Interactor) {}

  protected abstract hasChanged(context: CommandContext): boolean

  protected abstract buildTools(context: CommandContext): CodayTool[]

  getTools(context: CommandContext, toolNames?: string[]): CodayTool[] {
    if (!this.lastToolInitContext || this.hasChanged(context)) {
      this.lastToolInitContext = context
      this.tools = this.buildTools(context)
    }
    return this.tools.filter((tool) => !toolNames || !toolNames.length || toolNames.includes(tool.function.name))
  }
}

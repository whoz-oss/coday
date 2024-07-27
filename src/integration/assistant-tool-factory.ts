import {CommandContext, Interactor} from "../model"
import {FunctionTool} from "./types"

export type Tool = FunctionTool<any>

export abstract class AssistantToolFactory {
  tools: Tool[] = []
  lastToolInitContext: CommandContext | null = null
  
  protected constructor(protected interactor: Interactor) {
  }
  
  protected abstract hasChanged(context: CommandContext): boolean
  
  protected abstract buildTools(context: CommandContext): Tool[]
  
  getTools(context: CommandContext): Tool[] {
    if (!this.lastToolInitContext || this.hasChanged(context)) {
      this.lastToolInitContext = context
      this.tools = this.buildTools(context)
    }
    return this.tools
  }
}

import { CommandContext, Interactor } from '../model'
import { FunctionTool } from './types'

export type CodayTool = FunctionTool<any>

export abstract class AsyncAssistantToolFactory {
    tools: CodayTool[] = []
    lastToolInitContext: CommandContext | null = null

    protected constructor(protected interactor: Interactor) {}

    protected abstract hasChanged(context: CommandContext): boolean

    protected abstract buildAsyncTools(context: CommandContext): Promise<CodayTool[]>

    async getAsyncTools(context: CommandContext): Promise<CodayTool[]> {
        if (!this.lastToolInitContext || this.hasChanged(context)) {
            this.lastToolInitContext = context
            this.tools = await this.buildAsyncTools(context)
        }
        return this.tools
    }
}

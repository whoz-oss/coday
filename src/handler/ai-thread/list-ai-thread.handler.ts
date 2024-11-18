import {CommandHandler} from "../../model/command.handler"
import {Interactor} from "../../model/interactor"
import {AiThreadService} from "../../ai-thread/ai-thread.service"
import {lastValueFrom} from "rxjs"
import {CommandContext} from "../../model/command-context"

/**
 * Handler for listing all available AI threads.
 */
export class ListAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: "list",
      description: "List all available AI threads"
    })
  }
  
  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const threads = await lastValueFrom(this.threadService.list())
      const currentThread = this.threadService.getCurrentThread()
      
      let output = ""
      if (threads.length === 0) {
        output = "No threads found."
      } else {
        output = "Available threads:\n" + threads
          .map(thread => `- ${thread.id}: ${currentThread?.id === thread.id ? "[CURRENT] " : ""}${thread.name} (${thread.modifiedDate})`)
          .join("\n")
      }
      
      this.interactor.displayText(output)
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
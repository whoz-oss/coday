import {CommandHandler} from "../../model/command.handler"
import {Interactor} from "../../model/interactor"
import {AiThreadService} from "../../ai-thread/ai-thread.service"
import {CommandContext} from "../../model/command-context"
import {lastValueFrom} from "rxjs"

/**
 * Handler for selecting an AI thread for use.
 * If no thread ID is provided, shows a selection interface.
 */
export class SelectAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: "select",
      description: "Select a thread interactively or by ID"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const threadId = this.getSubCommand(command)
      if (threadId) {
        const thread = await this.threadService.select(threadId)
        this.interactor.displayText(`Selected thread: ${thread.id} - ${thread.name}`)
        return context
      }
      
      // Interactive selection
      const threads = await lastValueFrom(this.threadService.list())
      if (threads.length === 0) {
        this.interactor.displayText("No threads available.")
        return context
      }
      
      const currentThread = this.threadService.getCurrentThread()
      const threadsByText = new Map<string, string>()
      
      threads.forEach(thread => {
        const text = `${thread.id}: ${currentThread?.id === thread.id ? "[CURRENT] " : ""}${thread.name}`
        threadsByText.set(text, thread.id)
      })
      
      const options = Array.from(threadsByText.keys())
      const selected = await this.interactor.chooseOption(options, "Select a thread")
      const selectedId = threadsByText.get(selected)
      if (!selectedId) {
        this.interactor.error("Failed to get selected thread ID")
        return context
      }
      
      const thread = await this.threadService.select(selectedId)
      this.interactor.displayText(`Selected thread: ${thread.id} - ${thread.name}`)
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
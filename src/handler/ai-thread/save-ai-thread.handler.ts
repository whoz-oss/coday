import {CommandHandler} from "../../model/command.handler"
import {Interactor} from "../../model/interactor"
import {AiThreadService} from "../../ai-thread/ai-thread.service"
import {CommandContext} from "../../model/command-context"

/**
 * Handler for saving the current AI thread state.
 * This triggers saving the thread and any post-processing like summarization.
 *
 * Usage:
 * - `save`: saves the current thread with its current name
 * - `save new thread name`: saves the current thread under a new name
 */
export class SaveAiThreadHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly threadService: AiThreadService
  ) {
    super({
      commandWord: "save",
      description: "Save current thread state, optionally under a new name."
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      const currentThread = this.threadService.getCurrentThread()
      if (!currentThread) {
        this.interactor.displayText("No active thread to save.")
        return context
      }
      
      // Get new name if provided using parent's getSubCommand
      const newName = this.getSubCommand(command)
      
      if (newName) {
        // Validate name length when trimmed
        if (newName.length < 5) {
          this.interactor.displayText("Please provide a more significant name (at least 5 characters).")
          return context
        }
        
        await this.threadService.save(newName)
        this.interactor.displayText(`Thread saved with new name: ${newName} (${currentThread.id})`)
      } else {
        await this.threadService.save()
        this.interactor.displayText(`Thread saved: ${currentThread.name} (${currentThread.id})`)
      }
    } catch (error) {
      this.interactor.error(error)
    }
    return context
  }
}
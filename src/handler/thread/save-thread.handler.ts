import {CommandContext, CommandHandler, Interactor} from "../../model"
import {threadService} from "../../service/thread.service"
import {OpenaiClient} from "../openai.client"

export class SaveThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "save",
      description: "Save the current thread, will ask for title if not provided"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const threadId = this.openaiClient.threadId
    if (!threadId) {
      this.interactor.warn("Currently no thread started, nothing to save.")
      return context
    }
    
    const name = await this.getThreadName(command)
    threadService.saveThread(threadId, name)
    return context
  }
  
  private async getThreadName(command: string): Promise<string> {
    const subCommand = this.getSubCommand(command)
    if (subCommand) {
      return subCommand
    }
    return await this.interactor.promptText("Thread title :")
  }
}

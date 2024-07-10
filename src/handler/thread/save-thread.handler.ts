import {CommandHandler} from "../command.handler"
import {CommandContext} from "../../model/command-context"
import {threadService} from "../../service/thread.service"
import {Interactor} from "../../model/interactor"
import {OpenaiClient} from "../openai-client"

export class SaveThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "save",
      description: "Save the current thread"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const threadId = this.openaiClient.threadId
    if (!threadId) {
      this.interactor.warn("Currently no thread started, nothing to save.")
      return context
    }
    const name = await this.interactor.promptText("Thread title :")
    threadService.saveThread(threadId, name)
    return context
  }
}

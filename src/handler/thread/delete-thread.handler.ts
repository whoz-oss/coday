import {CommandContext} from "../../model/command-context"
import {threadService} from "../../service/thread.service"
import {Interactor} from "../../model/interactor"
import {OpenaiClient} from "../openai-client"
import {selectThread} from "./select-thread.util"
import {CommandHandler} from "../../model/command.handler"

export class DeleteThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "delete",
      description: "Delete a specific thread"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    this.interactor.displayText("Select the thread to delete:")
    const selectedThread = await selectThread(this.interactor, this.openaiClient.threadId)
    this.openaiClient.threadId = null
    threadService.deleteThread(selectedThread?.threadId)
    this.interactor.displayText(`Deleted thread: ${selectedThread?.threadId}`)
    return context
  }
}

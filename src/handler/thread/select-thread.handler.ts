import {CommandHandler} from "../command.handler"
import {CommandContext} from "../../model/command-context"
import {Interactor} from "../../model/interactor"
import {OpenaiClient} from "../openai-client"
import {selectThread} from "./select-thread.util"

export class SelectThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "select",
      description: "Select a specific thread"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    this.interactor.displayText("List of saved threads:")
    const selectedThread = await selectThread(this.interactor, this.openaiClient.threadId)
    this.openaiClient.threadId = selectedThread?.threadId ?? null
    return context
  }
}

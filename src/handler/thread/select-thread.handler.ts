import {CommandContext} from "../../model/command-context"
import {Interactor} from "../../model/interactor"
import {OpenaiClient} from "../openai.client"
import {selectThread} from "./select-thread.util"
import {CommandHandler} from "../../model/command.handler"

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
    const selectedThread = await selectThread(this.interactor, context.data.openaiData?.threadId)
    if (!context.data.openaiData) {
      context.data.openaiData = {}
    }
    context.data.openaiData.threadId = selectedThread?.threadId ?? null
    return context
  }
}

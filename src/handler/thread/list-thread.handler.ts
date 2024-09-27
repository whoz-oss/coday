import {CommandContext, CommandHandler, Interactor} from "../../model"
import {threadService} from "../../service/thread.service"
import {OpenaiClient} from "../openai.client"
import {formatThread} from "./format-thread.util"

export class ListThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "list",
      description: "List all threads"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const threads = threadService.listThreads()
    if (!threads?.length) {
      this.interactor.displayText("No thread saved.")
    } else {
      const currentThreadId = context.data.openaiData?.threadId
      this.interactor.displayText(`Saved threads:`)
      threads
        .map(t => `  - ${formatThread(t.threadId, t.name, currentThreadId)}`)
        .forEach(text => this.interactor.displayText(text))
    }
    return context
  }
}

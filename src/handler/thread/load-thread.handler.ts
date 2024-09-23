import {CommandContext} from "../../model/command-context"
import {Interactor} from "../../model/interactor"
import {OpenaiClient} from "../openai.client"
import {CommandHandler} from "../../model/command.handler"

export class LoadThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "load",
      description: "Load a specific thread by threadId"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const threadId = this.getSubCommand(command)
    if (!threadId) {
      this.interactor.warn("No threadId provided. Usage: thread load [threadId]")
      return context
    }
    this.openaiClient.threadId = threadId
    return context
  }
}

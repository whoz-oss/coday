import {CommandContext, CommandHandler, IntegrationName, Interactor} from "../model"
import {memoryService} from "../service/memory.service"


class MemoryHandler extends CommandHandler {
  
  constructor(private interactor: Interactor) {
    super({
      commandWord: "memory",
      description: "list current memories",
      requiredIntegrations: [IntegrationName.OPENAI],
      isInternal: true
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const memories: string = memoryService.listMemories().map(m => `${m.title}\n    ${m.content}`).join("\n")
    this.interactor.displayText(`Memories:\n${memories ? memories : "no current memories"}`)
    return context
  }
}

export {MemoryHandler}
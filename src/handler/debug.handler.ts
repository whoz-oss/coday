import {CommandContext, CommandHandler, Interactor} from "../model"
import {memoryService} from "../service/memory.service"
import {MemoryLevel} from "../model/memory"

export class DebugHandler extends CommandHandler {
  
  constructor(private interactor: Interactor) {
    super({
      commandWord: "debug",
      description: "run a command for dev-testing purposes",
      isInternal: true,
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    console.log("pre-memory")
    console.log(memoryService.listMemories())
    memoryService.upsertMemory({title: "toto", content: "is a test data", level: MemoryLevel.USER})
    console.log(memoryService.listMemories())
    return context
  }
  
}
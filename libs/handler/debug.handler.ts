import { CommandContext, CommandHandler } from '../model'
import { MemoryLevel } from '../model/memory'
import { memoryService } from '../service/memory.service'

export class DebugHandler extends CommandHandler {
  constructor() {
    super({
      commandWord: 'debug',
      description: 'run a command for dev-testing purposes',
      isInternal: true,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    console.log('pre-memory')
    console.log(memoryService.listMemories())
    memoryService.upsertMemory({ title: 'toto', content: 'is a test data', level: MemoryLevel.USER })
    console.log(memoryService.listMemories())
    return context
  }
}

import { CommandContext, CommandHandler, Interactor } from '../model'
import { MemoryService } from '../service/memory.service'

class MemoryHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super({
      commandWord: 'memory',
      description: 'list current memories',
      isInternal: true,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const memories: string = this.memoryService
      .listMemories()
      .map((m) => `${m.title}\n    ${m.content}`)
      .join('\n')
    this.interactor.displayText(`Memories:\n${memories ? memories : 'no current memories'}`)
    return context
  }
}

export { MemoryHandler }

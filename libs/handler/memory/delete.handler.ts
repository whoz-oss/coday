import { CommandHandler, CommandContext, Interactor } from '../../model'
import { MemoryService } from '../../service/memory.service'
import { MemoryLevel, Memory } from '../../model/memory'

export class MemoryDeleteHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super({
      commandWord: 'delete',
      description: 'Delete a memory. Optional filters: [PROJECT|USER] and agent:AgentName (e.g., "memory delete USER agent:Sway")'
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)

    let level: MemoryLevel | undefined = MemoryLevel.PROJECT
    let agent: string | undefined

    const levelMatch = Object.values(MemoryLevel).find(l => subCommand.includes(l))
    if (levelMatch) level = levelMatch as MemoryLevel

    const agentMatch = subCommand.match(/agent:(\w+)/)
    if (agentMatch) agent = agentMatch[1]

    let memories: Memory[]
    try {
      memories = this.memoryService.listMemories(level, agent)
    } catch (err: any) {
      this.interactor.error(`Error retrieving memories: ${err.message}`)
      return context
    }

    if (!memories.length) {
      this.interactor.displayText('No memories found to delete with the given filters.')
      return context
    }

    let selectedMemory: Memory | undefined
    if (memories.length === 1) {
      selectedMemory = memories[0]
    } else {
      const memoryChoices = memories.map((memory, index) =>
        `${index + 1}. [${memory.level}${memory.agentName ? ` - ${memory.agentName}` : ''}] ${memory.title}`
      )
      const options = [...memoryChoices, 'Cancel']
      let chosen: string
      try {
        chosen = await this.interactor.chooseOption(options, 'Select a memory to delete:')
      } catch (err: any) {
        this.interactor.error(`Memory selection interrupted: ${err.message}`)
        return context
      }
      if (chosen === 'Cancel') {
        this.interactor.displayText('Memory deletion cancelled.')
        return context
      }
      const chosenIdx = options.indexOf(chosen)
      selectedMemory = memories[chosenIdx]
    }

    if (!selectedMemory) {
      this.interactor.displayText('No memory selected.')
      return context
    }

    // Confirm deletion
    let confirm: string
    try {
      confirm = await this.interactor.chooseOption([
        'Yes, delete this memory',
        'No, cancel deletion'
      ], `Are you sure you want to delete the memory titled "${selectedMemory.title}"?`)
    } catch (err: any) {
      this.interactor.error(`Confirmation interrupted: ${err.message}`)
      return context
    }
    if (confirm !== 'Yes, delete this memory') {
      this.interactor.displayText('Memory deletion cancelled.')
      return context
    }

    try {
      this.memoryService.deleteMemory(selectedMemory.title)
      this.interactor.displayText(`Memory "${selectedMemory.title}" deleted successfully.`)
    } catch (error: any) {
      this.interactor.displayText(`Error deleting memory: ${error.message}`)
    }

    return context
  }
}

import { CommandHandler, CommandContext, Interactor } from '../../model'
import { MemoryService } from '../../service/memory.service'
import { MemoryLevel, Memory } from '../../model/memory'

export class MemoryEditHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super({
      commandWord: 'edit',
      description: 'Edit an existing memory. Optional filters: [PROJECT|USER] and agent:AgentName (e.g., "memory edit USER agent:Bob")'
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
      this.interactor.displayText('No memories found for editing with the given filters.')
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
        chosen = await this.interactor.chooseOption(options, 'Select a memory to edit:')
      } catch (err: any) {
        this.interactor.error(`Memory selection interrupted: ${err.message}`)
        return context
      }
      if (chosen === 'Cancel') {
        this.interactor.displayText('Memory edit cancelled.')
        return context
      }
      const chosenIdx = options.indexOf(chosen)
      selectedMemory = memories[chosenIdx]
    }

    if (!selectedMemory) {
      this.interactor.displayText('No memory selected.')
      return context
    }

    let newContent: string
    try {
      newContent = await this.interactor.promptText(
        `Current memory content:\n${selectedMemory.content}\n\nEnter new content:`,
        selectedMemory.content
      )
    } catch (err: any) {
      this.interactor.error(`Cancelled or failed to get new content: ${err.message}`)
      return context
    }

    if (!newContent || newContent === selectedMemory.content) {
      this.interactor.displayText('No changes made to memory.')
      return context
    }

    try {
      this.memoryService.upsertMemory({
        ...selectedMemory,
        content: newContent
      })
      this.interactor.displayText('Memory updated successfully.')
    } catch (error: any) {
      this.interactor.displayText(`Error updating memory: ${error.message}`)
    }

    return context
  }
}

import { CommandContext, CommandHandler, Interactor } from '../../model'
import { MemoryService } from '../../service/memory.service'
import { MemoryLevel } from '../../model/memory'

export class MemoryListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super({
      commandWord: 'list',
      description:
        'List memories. Optional filters: [PROJECT|USER] and agent:AgentName (e.g., "memory list USER" or "memory list agent:Sway")',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)

    let level: MemoryLevel | undefined = MemoryLevel.PROJECT
    let agent: string | undefined

    // Parse subcommand for level or agent
    const levelMatch = Object.values(MemoryLevel).find((l) => subCommand.includes(l))
    if (levelMatch) level = levelMatch as MemoryLevel

    const agentMatch = subCommand.match(/agent:(\w+)/)
    if (agentMatch) agent = agentMatch[1]

    const memories = this.memoryService.listMemories(level, agent)

    if (memories.length === 0) {
      this.interactor.displayText('No memories found for the given filter.')
      return context
    }

    const showFullDetails = memories.length <= 20

    // Build output string
    let outputText =
      `Found ${memories.length} memories` +
      `${agent ? ` for agent '${agent}'` : ''}` +
      `${level ? ` at level '${level}'` : ''}:\n\n`

    memories.forEach((memory, index) => {
      const headerPrefix = `${index + 1}. [${memory.level}${memory.agentName ? ` - ${memory.agentName}` : ''}] ${memory.title}`

      if (showFullDetails) {
        // Full details with indented content
        const indentedContent = memory.content
          .split('\n')
          .map((line) => '    ' + line)
          .join('\n')
        outputText += `${headerPrefix}\n${indentedContent}\n\n`
      } else {
        // Only metadata
        outputText += `${headerPrefix}\n`
      }
    })

    if (!showFullDetails) {
      outputText += '\nList truncated to metadata only for readability.\n'
      outputText +=
        "Tip: Use filters like 'memory list PROJECT', 'memory list USER', or 'memory list agent:agentName' for fewer results and more details.\n"
      outputText += 'For full content, refine your search or (future) use pagination options.\n'
    }

    this.interactor.displayText(outputText, 'MEMORY')
    return context
  }
}

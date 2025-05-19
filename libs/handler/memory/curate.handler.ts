import { CommandHandler, CommandContext, Interactor } from '../../model'
import { MemoryLevel } from '../../model/memory'

export class MemoryCurateHandler extends CommandHandler {
  constructor(
    private interactor: Interactor
  ) {
    super({
      commandWord: 'curate',
      description: 'Curate memories with a free-form prompt. Optional filters: [PROJECT|USER] and agent:AgentName (e.g., "memory curate USER agent:Sway about X")'
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    let level: MemoryLevel | undefined
    let agent: string | undefined
    let promptText = subCommand.trim()

    // Parse for explicit level
    const levelMatch = Object.values(MemoryLevel).find(l => promptText.includes(l))
    if (levelMatch) {
      level = levelMatch as MemoryLevel
      // Remove the level specifier from the prompt
      promptText = promptText.replace(levelMatch, '').trim()
    }

    // Parse for explicit agent
    const agentMatch = promptText.match(/agent:(\w+)/)
    if (agentMatch) {
      agent = agentMatch[1]
      promptText = promptText.replace(agentMatch[0], '').trim()
    }

    // Prompt for missing values if needed
    if (!level) {
      const chosen = await this.interactor.chooseOption([
        MemoryLevel.PROJECT,
        MemoryLevel.USER
      ], 'Select memory level for curation:')
      level = chosen as MemoryLevel
    }

    if (!agent) {
      agent = await this.interactor.promptText('Enter agent name for curation (leave blank for all):')
      if (agent === '') agent = undefined
    }

    // Prompt for curation subject if still empty
    if (!promptText) {
      promptText = await this.interactor.promptText('What would you like to curate about these memories? (describe focus):')
    }

    // Compose the memory curation command
    let memoryCurationCmd = `memory-curate ${promptText}`
    if (level) memoryCurationCmd = `${level} ` + memoryCurationCmd
    if (agent) memoryCurationCmd = `agent:${agent} ` + memoryCurationCmd

    // Queue the command for downstream handling (AI agent, etc.)
    context.addCommands(memoryCurationCmd)
    this.interactor.displayText('Memory curation command queued for processing.')
    return context
  }
}

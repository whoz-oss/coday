import { CommandContext, CommandHandler, Interactor } from '@coday/model'
import { MemoryLevel } from '@coday/model/memory'
import { parseArgs } from '../parse-args'

export class MemoryCurateHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'curate',
      description: `Curate memories with a free-form prompt on a topic.
    Use \`--project\` or \`--user\` to specify level (defaults to interactive selection), \`--agent=NAME\` to specify agent (defaults to Coday).
    Example: \`memory curate --project --agent=Sway coding patterns\`.
    Shorthand syntax: \`memory curate -p -a=Sway coding patterns\`.`,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    const args = parseArgs(subCommand, [
      { key: 'project', alias: 'p' },
      { key: 'user', alias: 'u' },
      { key: 'agent', alias: 'a' },
    ])
    let promptText = args.rest
    const userLevel = args.user ? MemoryLevel.USER : undefined
    let level = args.project ? MemoryLevel.PROJECT : userLevel
    let agent = typeof args.agent === 'string' ? args.agent : undefined

    // Prompt for missing values if needed
    if (!level) {
      const chosen = await this.interactor.chooseOption(
        [MemoryLevel.PROJECT, MemoryLevel.USER],
        'Select memory level for curation:'
      )
      level = chosen as MemoryLevel
    }

    if (!agent) {
      agent = await this.interactor.promptText('Enter agent name for curation (leave blank for agent-less memories):')
      if (agent === '') agent = undefined
    }

    // Prompt for curation subject if still empty
    if (!promptText) {
      promptText = await this.interactor.promptText(
        'What would you like to curate about these memories? (describe focus):'
      )
    }

    // Build the curation prompt chain
    const curationPromptChain = this.buildCurationPrompt(promptText, level, agent)

    // Queue the command chain for processing
    context.addCommands(...curationPromptChain)
    this.interactor.displayText('Memory curation command queued for processing.')
    return context
  }

  private buildCurationPrompt(topic: string, level: MemoryLevel, agent: string = ''): string[] {
    const aboutTopic = topic ? ` about "${topic}"` : ''
    return [
      `${agent} I want you to curate the ${level} memories${aboutTopic}:

1. Check memories that:
   - Cover similar topics
   - Have overlapping information
   - Could be consolidated for clarity

2. For each group of redundant memories:
   - Delete individual memories using deleteMemory
   - Create new consolidated memory that:
     - Maintains all critical details
     - Provides clear structure
     - Uses appropriate level (${level})

Be proactive but careful in this consolidation, explaining your reasoning.`,

      `${agent} Now${aboutTopic}, analyze remaining memories for outdated or incorrect information:

1. Compare each memory against:
   - Other memories for inconsistencies
   - Project context for outdated info
   - Core documentation for misalignments

2. For each problematic memory:
   - List the title with short explanation of the issue
   - Take appropriate action:
     - Delete if outdated/incorrect
     - Update if partial/incomplete
     - Consolidate if better fits elsewhere

Be thorough in your analysis and clear in your explanations.`,

      `${agent} Finally${aboutTopic}, verify the memory system's current state:

1. Review the overall structure
2. Check each memory for:
   - Completeness and validity
   - Proper categorization (PROJECT/USER)
   - Clear structure and organization
3. Document any remaining issues or recommendations

This ensures the memory system stays efficient and valuable.`,
    ]
  }
}

import { MemoryService } from '@coday/service/memory.service'
import { MemoryLevel, Memory } from '@coday/model/memory'
import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'

export class MemoryListHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super({
      commandWord: 'list',
      description: `List memories.
    Use \`--project\` to list only PROJECT memories, \`--user\` to list only USER memories.
    If neither is specified, both levels will be listed.
    Use \`--agent=NAME\` to specify agent.
    Example: \`memory list --user --agent=Sway\`.
    Shorthand syntax: \`memory list -u -a=Sway\`.`,
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    const args = parseArgs(subCommand, [
      { key: 'project', alias: 'p' },
      { key: 'user', alias: 'u' },
      { key: 'agent', alias: 'a' },
    ])

    const agent: string | undefined = typeof args.agent === 'string' ? args.agent : undefined

    // Collect memories based on specified levels
    let allMemories: Memory[] = []

    if (args.project || (!args.project && !args.user)) {
      // Get PROJECT memories if --project flag is set OR if neither flag is set
      const projectMemories = this.memoryService.listMemories(MemoryLevel.PROJECT, agent)
      allMemories = allMemories.concat(projectMemories)
    }

    if (args.user || (!args.project && !args.user)) {
      // Get USER memories if --user flag is set OR if neither flag is set
      const userMemories = this.memoryService.listMemories(MemoryLevel.USER, agent)
      allMemories = allMemories.concat(userMemories)
    }

    if (allMemories.length === 0) {
      this.interactor.displayText('No memories found for the given filter.')
      return context
    }

    const showFullDetails = allMemories.length <= 20

    // Build output string
    const levelFilter =
      args.project && !args.user
        ? ` at level '${MemoryLevel.PROJECT}'`
        : !args.project && args.user
          ? ` at level '${MemoryLevel.USER}'`
          : ''

    let outputText =
      `Found ${allMemories.length} memories` + `${agent ? ` for agent '${agent}'` : ''}` + `${levelFilter}:\n\n`

    allMemories.forEach((memory, index) => {
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
        "Tip: Use filters like 'memory list --project', 'memory list --user', or 'memory list --agent=agentName' for fewer results and more details.\n"
      outputText += 'For full content, refine your search or (future) use pagination options.\n'
    }

    this.interactor.displayText(outputText, 'MEMORY')
    return context
  }
}

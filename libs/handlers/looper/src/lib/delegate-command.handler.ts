import { CommandHandler } from '@coday/handler'
import { CommandContext, Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { delegateFunction } from '@coday/integrations-ai'
import { parseAgentCommand } from '@coday/handler'

const USAGE_HINT = 'Usage: /delegate @AgentName <task description>'

/**
 * DelegateCommandHandler - User-initiated delegation to a named agent
 *
 * Handles the `/delegate @AgentName <task>` command, which is the human-initiated
 * equivalent of the agent `delegate` tool. The agent runs the task in a fresh
 * sub-thread and the result is displayed inline.
 *
 * This handler is registered as a custom handler inside SlashCommandHandler so it:
 * - Appears in the `/` autocomplete (via PromptService.list() stub)
 * - Takes priority over any prompt with the same name
 * - Uses the native delegation infrastructure (delegateFunction)
 *
 * The commandWord is `delegate` (without leading `/`) because SlashCommandHandler
 * strips the `/` prefix before dispatching to sub-handlers.
 */
export class DelegateCommandHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly services: CodayServices
  ) {
    super({
      commandWord: 'delegate',
      description: 'Delegate a task to a specific agent in an isolated sub-thread: /delegate @AgentName <task>',
    })
  }

  override accept(command: string, _context: CommandContext): boolean {
    const lower = command.trim().toLowerCase()
    return lower === 'delegate' || lower.startsWith('delegate ')
  }

  override async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const remainder = command.trim().slice('delegate'.length).trim()

    if (!remainder.startsWith('@')) {
      this.interactor.displayText(`Missing @AgentName. ${USAGE_HINT}`)
      return context
    }

    const [agentName, task] = parseAgentCommand(remainder)

    if (!agentName) {
      this.interactor.displayText(`Missing agent name. ${USAGE_HINT}`)
      return context
    }

    if (!task.trim()) {
      this.interactor.displayText(`Missing task. ${USAGE_HINT}`)
      return context
    }

    if (!this.services.agent) {
      this.interactor.error('Agent service is not available. Cannot execute delegation.')
      return context
    }

    const agent = await this.services.agent.findAgentByNameStart(agentName, context)
    if (!agent) {
      this.interactor.error(`Agent '${agentName}' not found. Use 'help' to see available agents.`)
      return context
    }

    const delegate = delegateFunction({
      context,
      interactor: this.interactor,
      agentFind: this.services.agent.findAgentByNameStart,
      threadService: this.services.thread,
      emitResultAsUserMessage: true,
    })

    await delegate({ delegations: [{ agentName: agent.name, task: task.trim() }] }, context.aiThread)
    return context
  }
}

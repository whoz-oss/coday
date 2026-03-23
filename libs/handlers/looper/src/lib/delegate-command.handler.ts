import { CommandHandler } from '@coday/handler'
import { CommandContext, Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { delegateFunction } from '@coday/integrations-ai'
import { parseAgentCommand } from '@coday/handlers-openai'

const USAGE_HINT = 'Usage: /delegate @AgentName <task description>'

export class DelegateCommandHandler extends CommandHandler {
  constructor(
    private readonly interactor: Interactor,
    private readonly services: CodayServices
  ) {
    super({
      commandWord: '/delegate',
      description: 'Delegate a task to a specific agent in an isolated sub-thread: /delegate @AgentName <task>',
    })
  }

  override accept(command: string, _context: CommandContext): boolean {
    return command.trim().toLowerCase().startsWith('/delegate ')
  }

  override async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const remainder = command.trim().slice('/delegate '.length).trim()

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
    })

    const result = await delegate({ delegations: [{ agentName: agent.name, task: task.trim() }] }, context.aiThread)

    this.interactor.displayText(result)
    return context
  }
}

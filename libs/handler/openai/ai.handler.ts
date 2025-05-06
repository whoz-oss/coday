import { keywords } from '../../keywords'
import { Agent, CommandContext, CommandHandler, Interactor, Killable } from '../../model'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent, MessageEvent } from '@coday/coday-events'
import { AgentService } from '../../agent'
import { parseAgentCommand } from './parseAgentCommand'

export class AiHandler extends CommandHandler implements Killable {
  constructor(
    private interactor: Interactor,
    private agentService: AgentService
  ) {
    super({
      commandWord: keywords.assistantPrefix,
      description:
        "calls the AI with the given command and current context. 'reset' for using a new thread. You can call whatever assistant in your openai account by its name, ex: joke_generator called by @jok (choice prompt if multiple matches).",
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const [agentName, restOfCommand] = parseAgentCommand(command)
    this.interactor.debug(`Agent name extracted from command: ${agentName}`)

    // Try to select the specified agent
    // If agentName is empty, selectAgent will handle the fallback logic
    const selectedAgent = await this.selectAgent(agentName, context)

    if (!selectedAgent) {
      this.interactor.error('Failed to find any agent')
      return context
    }

    // If no further command, just confirm selection
    if (!restOfCommand.trim()) {
      this.interactor.debug(`Agent ${selectedAgent!.name} selected.`)
      return context
    }

    return this.runAgent(selectedAgent!, restOfCommand, context)
  }

  /**
   * Try to select an agent by name, handling various cases:
   * - Empty/no match: returns default agent
   * - Single match: returns that agent
   * - Multiple matches: handles interactive/non-interactive cases
   * Returns undefined if selection fails or is cancelled
   */
  private async selectAgent(nameStart: string, context: CommandContext): Promise<Agent | undefined> {
    // If a specific agent name start is provided, use that
    if (nameStart) {
      const agent = await this.agentService.findAgentByNameStart(nameStart, context)
      if (agent) {
        this.interactor.debug(`Selected agent: ${agent.name}`)
      }
      return agent
    }

    // No specific agent requested, follow preference chain:
    // 1. Last used agent in thread history
    // 2. User's default agent for this project (from user config)
    // 3. Fall back to 'coday'

    // Check for last used agent in this thread
    const lastAgent = context?.aiThread?.getLastAgentName()
    if (lastAgent) {
      const agent = await this.agentService.findByName(lastAgent, context)
      if (agent) {
        this.interactor.debug(`Select last used agent: ${agent.name}`)
        return agent
      } else {
        this.interactor.warn('Previously selected agent not available anymore')
      }
    }

    // No last agent or not found, check for user's preferred agent
    const preferredAgent = this.agentService.getPreferredAgent()
    if (preferredAgent) {
      const agent = await this.agentService.findByName(preferredAgent, context)
      if (agent) {
        this.interactor.displayText(`Selecting user default agent ${preferredAgent}`)
        this.interactor.debug(`Selected default agent: ${preferredAgent}`)
        return agent
      }
      // Preferred agent not found
      this.interactor.warn(`Preferred agent '${preferredAgent}' not found, using default.`)
    }

    // Fall back to default 'coday' agent
    const defaultAgent = await this.agentService.findByName('coday', context)
    if (!defaultAgent) {
      this.interactor.error('Critical failure: Cannot initialize default Coday agent!')
    }
    return defaultAgent
  }

  /**
   * Execute agent with given command in context
   */
  private async runAgent(agent: Agent, cmd: string, context: CommandContext): Promise<CommandContext> {
    const events: Observable<CodayEvent> = await agent.run(cmd, context.aiThread!)
    events.subscribe({
      next: (event) => {
        this.interactor.sendEvent(event)
        if (event instanceof MessageEvent) {
          this.interactor.displayText(event.content, event.name)
        }
      },
      error: (error) => {
        if (error.message === 'Processing interrupted by user request') {
          this.interactor.debug('Processing stopped gracefully')
        } else {
          this.interactor.error(`Error in AI processing: ${error.message}`)
        }
      },
    })
    await lastValueFrom(events)
    return context
  }

  async kill(): Promise<void> {
    await this.agentService.kill()
  }
}

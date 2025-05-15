import { keywords } from '../../keywords'
import { Agent, CommandContext, CommandHandler, Interactor, Killable } from '../../model'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared/coday-events'
import { AgentService } from '../../agent'

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
    /* The command should start with @ followed by an agent name, optionally followed by the actual command
     * Examples:
     *   - "@" => invalid, no agent name, use default agent
     *   - "@ " => invalid, no agent name, use default agent
     *   - "@AgentName" => valid, use AgentName agent with no further command
     *   - "@AgentName rest of command" => valid, use AgentName with "rest of command"
     *   - "@AgentName\nrest of command" => valid, use AgentName with "rest of command"
     */

    // Extract the agent name using regex
    // Format: @ followed by zero or more non-whitespace characters, optionally followed by whitespace and the rest
    const match = command.match(/^@(\S*)(?:\s+(.*))?$/)

    if (!match) {
      // This case should rarely happen as the regex now matches almost any string starting with @
      // But keeping it as a safety check
      const agent = await this.agentService.findByName('coday', context)
      if (!agent) {
        this.interactor.error('Failed to initialize default agent')
        return context
      }
      return this.runAgent(agent, command.slice(1).trim(), context) // Remove @ and pass the rest as command
    }

    const agentName = match[1] // First group: the agent name (could be empty)
    const restOfCommand = match[2] || '' // Second group: the rest of the command (or empty string if not present)

    // Try to select the specified agent
    // If agentName is empty, selectAgent will handle the fallback logic
    const selectedAgent = await this.selectAgent(agentName, context)
    if (!selectedAgent) {
      // Agent name was provided but not found, show warning and use default
      if (agentName) {
        this.interactor.warn(`Agent '${agentName}' not found. Using default agent.`)
      }
      const defaultAgent = await this.agentService.findByName('coday', context)
      if (!defaultAgent) {
        this.interactor.error('Failed to initialize default agent')
        return context
      }
      return this.runAgent(defaultAgent, restOfCommand, context)
    }

    // If no further command, just confirm selection
    if (!restOfCommand.trim()) {
      this.interactor.displayText(`Agent ${selectedAgent.name} selected.`)
      return context
    }

    return this.runAgent(selectedAgent, restOfCommand, context)
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
    if (nameStart?.trim()) {
      const agent = await this.agentService.findAgentByNameStart(nameStart, context)
      if (agent) {
        this.interactor.displayText(`Selected agent: ${agent.name}`)
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
        return agent
      }
    }

    // No last agent or not found, check for user's preferred agent
    const preferredAgent = this.agentService.getPreferredAgent()
    if (preferredAgent) {
      const agent = await this.agentService.findByName(preferredAgent, context)
      if (agent) {
        this.interactor.displayText(`Selected default agent: ${preferredAgent}`)
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
          this.interactor.displayText('Processing stopped gracefully', agent.name)
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

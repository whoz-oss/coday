import { keywords } from '../../keywords'
import { Agent, CommandContext, CommandHandler, Interactor } from '../../model'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared/coday-events'
import { AgentService } from '../../agent'

export class AiHandler extends CommandHandler {
  private lastAgentName: string | undefined

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
    /* The command can contain a space after the commandWord that is important to detect if it is an agent name or not.
     * The getSubCommand of CommandHandler does trim it, misplacing first word as agent name.
     * ex:
     *   - "@ foo bar" => no agent name, user prompt is "foo bar"
     *   - "@foo bar" => agent name is "foo", user prompt is "bar"
     */
    const cmd = command.slice(this.commandWord.length)

    // Check for agent name at start of command
    const firstSpaceIndex = cmd.indexOf(' ')
    const potentialAgentName = firstSpaceIndex < 0 ? cmd : cmd.slice(0, firstSpaceIndex)
    const restOfCommand = firstSpaceIndex < 0 ? '' : cmd.slice(firstSpaceIndex + 1)

    // Empty command, use default agent, corner case
    if (!cmd.trim()) {
      const agent = await this.agentService.findByName('coday', context)
      if (!agent) {
        this.interactor.error('Failed to initialize default agent')
        return context
      }
      return this.runAgent(agent, '', context)
    }

    // Try to select an agent
    const selectedAgent = await this.selectAgent(potentialAgentName, context)
    if (!selectedAgent) {
      console.log('No selected agent for command, skipping.')
      return context
    }

    // If no further command, just confirm selection
    if (!restOfCommand) {
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
    let name = nameStart
    if (!nameStart.trim()) {
      if (this.lastAgentName) {
        console.log('Selecting last agent')
        name = this.lastAgentName
      } else {
        this.interactor.displayText('Selecting Coday')
        name = 'coday'
      }
    }

    // Find matching agents
    const matchingAgents = await this.agentService.findAgentByNameStart(name, context)

    if (matchingAgents.length === 0) {
      // No match, use default agent
      const agent = await this.agentService.findByName('coday', context)
      if (!agent) {
        this.interactor.error(
          `Failed to initialize default agent 'Coday', at least add an agent named "Coday" in coday.yaml, section agents.`
        )
        return undefined
      }
      return agent
    }

    if (matchingAgents.length === 1) {
      this.lastAgentName = matchingAgents[0].name
      return matchingAgents[0]
    }

    // Multiple matches
    if (context.oneshot) {
      // Non-interactive mode: show error
      this.interactor.error(
        `Multiple agents match '${name}'. Please be more specific: ` + matchingAgents.map((a) => a.name).join(', ')
      )
      return undefined
    }

    // Interactive mode: let user choose
    const options = matchingAgents.map((agent) => agent.name)
    try {
      const selection = await this.interactor.chooseOption(
        options,
        `Multiple agents match '${name}', please select one:`
      )
      const selectedAgent = matchingAgents.find((agent) => agent.name === selection)
      this.lastAgentName = selectedAgent?.name
      return selectedAgent
    } catch (error) {
      this.interactor.error('Selection cancelled')
      return undefined
    }
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

  kill(): void {
    this.agentService.kill()
  }
}

import { keywords } from '../../keywords'
import { Agent, CommandContext, CommandHandler, Interactor, Killable } from '@coday/model'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent } from '@coday/coday-events'
import { AgentService } from '@coday/agent'
import { parseAgentCommand } from './parseAgentCommand'
import { ThreadStateService } from '@coday/ai-thread/thread-state.service'
import { AiThread } from '@coday/ai-thread/ai-thread'
import { generateThreadName } from '../generate-thread-name'

export class AiHandler extends CommandHandler implements Killable {
  constructor(
    private interactor: Interactor,
    private agentService: AgentService,
    private threadService: ThreadStateService
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
    } else {
      this.interactor.debug(`Selected default 'Coday' agent`)
    }
    return defaultAgent
  }

  /**
   * Execute agent with given command in context
   */
  private async runAgent(agent: Agent, cmd: string, context: CommandContext): Promise<CommandContext> {
    const events: Observable<CodayEvent> = await agent.run(cmd, context.aiThread!)

    // Check for auto-save after user message is added to thread
    await this.checkAndAutoSave(context.aiThread!, agent)

    events.subscribe({
      next: (event) => {
        this.interactor.sendEvent(event)
        // MessageEvent is now handled directly by the frontend
        // No need to convert to TextEvent - this would cause duplication
      },
      error: (error) => {
        if (error.message === 'Processing interrupted by user request') {
          this.interactor.debug('Processing stopped gracefully')
        } else {
          this.interactor.error(`Error in AI processing: ${error.message}`)
        }
      },
    })
    try {
      await lastValueFrom(events)
    } catch (error: any) {
      this.interactor.error(`Could not run agent ${agent.name} : ${error.message}`)
    } finally {
      // Always perform final autosave, even if there was an error
      // This ensures the last agent message is saved
      try {
        await this.threadService.autoSave()
      } catch (saveError) {
        this.interactor.debug(`Final auto-save failed: ${saveError}`)
      }
    }
    return context
  }

  /**
   * Auto-save thread after each message and rename if needed
   */
  private async checkAndAutoSave(thread: AiThread, agent: Agent): Promise<void> {
    if (thread.getUserMessageCount() === 0) {
      // no autosave
      return
    }

    // Check if we should rename the thread (at 3 messages and still has default name)
    if (!thread.name) {
      try {
        // Generate thread name using the agent's AI client
        const threadName = await generateThreadName(thread, agent)

        // Save the thread with the generated name
        await this.threadService.autoSave(threadName)

        // Notify user
        this.interactor.displayText(`Thread auto-renamed to "${threadName}"`)
      } catch (error) {
        this.interactor.warn(`Auto-rename failed: ${error}`)
      }
    } else {
      // Always auto-save the thread (since all threads are now persistent)
      try {
        await this.threadService.autoSave()
      } catch (error) {
        this.interactor.debug(`Auto-save failed: ${error}`)
      }
    }
  }

  async kill(): Promise<void> {
    await this.agentService.kill()
  }
}

import { keywords } from '../../keywords'
import { Agent, AiClient, CodayAgentDefinition, CommandContext, CommandHandler, Interactor } from '../../model'
import { Toolbox } from '../../integration/toolbox'
import { ToolSet } from '../../integration/tool-set'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared/coday-events'

export class AiHandler extends CommandHandler {
  private toolbox: Toolbox
  private tempAgent: Agent | undefined

  constructor(
    private interactor: Interactor,
    private aiClient: AiClient | undefined
  ) {
    super({
      commandWord: keywords.assistantPrefix,
      description:
        "calls the AI with the given command and current context. 'reset' for using a new thread. You can call whatever assistant in your openai account by its name, ex: joke_generator called by @jok (choice prompt if multiple matches).",
    })
    this.toolbox = new Toolbox(this.interactor)
  }

  private getAgent(context: CommandContext): Agent {
    if (!this.tempAgent) {
      const toolset = new ToolSet(this.toolbox.getTools(context))
      this.tempAgent = new Agent(CodayAgentDefinition, this.aiClient!, context.project, toolset)
    }
    return this.tempAgent
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.aiClient) {
      this.interactor.error("No AI provider configured, see 'config edit-ai' command.")
      return context
    }

    const cmd = command.slice(this.commandWord.length)

    try {
      // This agent definition should be done beforehand, as "soon" as the project is selected
      const agent = this.getAgent(context)
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
    } catch (error: any) {
      this.interactor.error(`Error processing command: ${error}`)
    }

    return context
  }

  kill(): void {
    this.aiClient?.kill()
  }
}

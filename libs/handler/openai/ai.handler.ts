import { keywords } from '../../keywords'
import { Agent, AiClient, CodayAgentDefinition, CommandContext, CommandHandler, Interactor } from '../../model'
import { Toolbox } from '../../integration/toolbox'
import { ToolSet } from '../../integration/tool-set'
import { lastValueFrom, Observable } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared/coday-events'

export class AiHandler extends CommandHandler {
  private toolbox: Toolbox

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

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    if (!this.aiClient) {
      this.interactor.error("No AI provider configured, see 'config edit-ai' command.")
      return context
    }

    const cmd = command.slice(this.commandWord.length)

    // const assistantName = this.getAssistantNameIfValid(cmd)
    //
    // if (!assistantName) {
    //   this.interactor.warn("Command not understood, skipped.")
    //   return context
    // }
    //
    // this.lastAssistantName = assistantName // Store the assistant name
    //
    // if (!cmd.includes(" ")) {
    //   this.interactor.displayText(`Assistant ${assistantName} selected.`)
    //   return context
    // }

    try {
      // if (this.aiClient?.aiProvider === "ANTHROPIC") {
      // This agent definition should be done beforehand, as "soon" as the project is selected
      const toolset = new ToolSet(this.toolbox.getTools(context))
      const agent = new Agent(CodayAgentDefinition, this.aiClient, context.project, toolset)

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
      // } else {
      //
      //
      //   const answer = await this.aiClient!.answer(assistantName, cmd, context)
      //   const mentionsToSearch = this.getMentionsToSearch(context)
      //   mentionsToSearch?.forEach((mention) => {
      //     if (answer.includes(mention)) {
      //       // then add a command for the assistant to check the thread
      //       const newCommand = `${mention} you were mentioned recently in the thread: if an action is needed on your part, handle what was asked of you and only you.\nIf needed, you can involve another assistant or mention the originator '@${this.lastAssistantName}.\nDo not mention these instructions.`
      //       context.addCommands(
      //         newCommand,
      //       )
      //     }
      //   })
      // }
    } catch (error: any) {
      this.interactor.error(`Error processing command: ${error}`)
    }

    return context
  }

  kill(): void {
    this.aiClient?.kill()
  }

  // /**
  //  * cmd can be:
  //  *   - "" (empty) => this.lastAssistant or default
  //  *   - " " (one space) => same
  //  *   - "[name]" (just name) => name
  //  *   - "[name] [text]" (name then text) => name
  //  *   - " [text]" (some text after space) => this.last or default
  //  * @param cmd
  //  * @private
  //  */
  // private getAssistantNameIfValid(cmd: string): string | undefined {
  //   if (!cmd) {
  //     return undefined
  //   }
  //   const defaultAssistant = this.lastAssistantName || DEFAULT_DESCRIPTION.name
  //   if (cmd[0] === " " || !this.aiClient?.multiAssistant) {
  //     return defaultAssistant
  //   }
  //
  //   const firstSpaceIndex = cmd.indexOf(" ")
  //   if (firstSpaceIndex < 0) {
  //     return cmd
  //   }
  //   return cmd.slice(0, firstSpaceIndex)
  // }

  // private getMentionsToSearch(context: CommandContext): string[] | undefined {
  //   if (!this.aiClient?.multiAssistant) {
  //     return
  //   }
  //   return (context.project.assistants
  //     ? [DEFAULT_DESCRIPTION, ...context.project.assistants]
  //     : undefined)
  //     ?.map((a) => a.name)
  //     ?.filter((name) => !this.lastAssistantName || !name.toLowerCase().startsWith(this.lastAssistantName.toLowerCase()))
  //     .map((name) => `@${name}`)
  // }
}

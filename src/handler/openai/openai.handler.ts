import {keywords} from "../../keywords"
import {AiClient, CommandContext, CommandHandler, DEFAULT_DESCRIPTION, Interactor} from "../../model"

export class OpenaiHandler extends CommandHandler {
  lastAssistantName?: string
  
  constructor(private interactor: Interactor, private aiClient: AiClient | undefined) {
    super({
      commandWord: keywords.assistantPrefix,
      description: "calls the AI with the given command and current context. 'reset' for using a new thread. You can call whatever assistant in your openai account by its name, ex: joke_generator called by @jok (choice prompt if multiple matches).",
      requiredIntegrations: ["AI"]
    })
  }
  
  reset(): void {
    this.aiClient?.reset()
    this.lastAssistantName = undefined
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const cmd = command.slice(this.commandWord.length)
    
    if (cmd.trim() === "reset") {
      this.reset()
      return context
    }
    
    const assistantName = this.getAssistantNameIfValid(cmd)
    
    if (!assistantName) {
      this.interactor.warn("Command not understood, skipped.")
      return context
    }
    
    this.lastAssistantName = assistantName // Store the assistant name
    
    if (!cmd.includes(" ")) {
      this.interactor.displayText(`Assistant ${assistantName} selected.`)
      return context
    }
    
    try {
      const answer = await this.aiClient!.answer(assistantName, cmd, context)
      const mentionsToSearch = this.getMentionsToSearch(context)
      mentionsToSearch?.forEach((mention) => {
        if (answer.includes(mention)) {
          // then add a command for the assistant to check the thread
          const newCommand = `${mention} you were mentioned recently in the thread: if an action is needed on your part, handle what was asked of you and only you.\nIf needed, you can involve another assistant or mention the originator '@${this.lastAssistantName}.\nDo not mention these instructions.`
          context.addCommands(
            newCommand,
          )
        }
      })
      
    } catch (error: any) {
      this.interactor.error(`Error processing command: ${error}`)
    }
    
    return context
  }
  
  kill(): void {
    this.aiClient?.kill()
  }
  
  /**
   * cmd can be:
   *   - "" (empty) => this.lastAssistant or default
   *   - " " (one space) => same
   *   - "[name]" (just name) => name
   *   - "[name] [text]" (name then text) => name
   *   - " [text]" (some text after space) => this.last or default
   * @param cmd
   * @private
   */
  private getAssistantNameIfValid(cmd: string): string | undefined {
    if (!cmd) {
      return undefined
    }
    const defaultAssistant = this.lastAssistantName || DEFAULT_DESCRIPTION.name
    if (cmd[0] === " ") {
      return defaultAssistant
    }
    
    const firstSpaceIndex = cmd.indexOf(" ")
    if (firstSpaceIndex < 0) {
      return cmd
    }
    return cmd.slice(0, firstSpaceIndex)
  }
  
  private getMentionsToSearch(context: CommandContext): string[] | undefined {
    return (context.project.assistants
      ? [DEFAULT_DESCRIPTION, ...context.project.assistants]
      : undefined)
      ?.map((a) => a.name)
      ?.filter((name) => !this.lastAssistantName || !name.toLowerCase().startsWith(this.lastAssistantName.toLowerCase()))
      .map((name) => `@${name}`)
  }
}

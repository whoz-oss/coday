import {CommandHandler} from "./command.handler"
import {Interactor} from "../model/interactor"
import {OpenaiClient} from "./openai-client"
import {CommandContext} from "../model/command-context"
import {IntegrationName} from "../model/integration-name"
import {integrationService} from "../service/integration.service"
import {CODAY_DESCRIPTION} from "./coday-description"
import {keywords} from "../keywords"

export class OpenaiHandler extends CommandHandler {
  openaiClient: OpenaiClient
  lastAssistantName?: string
  
  constructor(private interactor: Interactor) {
    super({
      commandWord: keywords.assistantPrefix,
      description: "calls the AI with the given command and current context. 'reset' for using a new thread. You can call whatever assistant in your openai account by its name, ex: joke_generator called by @jok (choice prompt if multiple matches).",
      requiredIntegrations: [IntegrationName.OPENAI]
    })
    const apiKeyProvider = () => integrationService.getApiKey("OPENAI")
    this.openaiClient = new OpenaiClient(interactor, apiKeyProvider)
  }
  
  reset(): void {
    this.openaiClient.reset()
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
      await this.openaiClient.answer(assistantName, cmd, context)
    } catch (error: any) {
      this.interactor.error(`Error processing command: ${error}`)
    }
    
    return context
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
    const defaultAssistant = this.lastAssistantName || CODAY_DESCRIPTION.name
    if (cmd[0] === " ") {
      return defaultAssistant
    }
    
    const firstSpaceIndex = cmd.indexOf(" ")
    if (firstSpaceIndex < 0) {
      return cmd
    }
    return cmd.slice(0, firstSpaceIndex)
  }
}

import {AiConfig, CommandContext, CommandHandler, Interactor} from "../../model"
import {configService} from "../../service/config.service"
import {keywords} from "../../keywords"

const AI_PROVIDERS: readonly ["anthropic", "openai", "gemini"] = ["anthropic", "openai", "gemini"] as const
type AiProvider = typeof AI_PROVIDERS[number]

export class EditAiHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
  ) {
    super({
      commandWord: "edit-ai",
      description: "Configure AI providers API keys"
    })
  }
  
  async handle(
    command: string,
    context: CommandContext,
  ): Promise<CommandContext> {
    if (!configService.project) {
      this.interactor.displayText("No current project, select one first.")
      return context
    }
    
    const currentConfig: AiConfig = configService.project.ai || {}
    
    // Parse command for provider name start and api key
    const subCommand: string = this.getSubCommand(command)
    const [providerStart, ...apiKeyParts]: string[] = subCommand.split(" ")
    const apiKeyFromCommand: string | undefined = apiKeyParts.join(" ")
    
    let provider: AiProvider
    if (providerStart) {
      // Try to find matching provider
      const matchingProvider: AiProvider | undefined = AI_PROVIDERS.find(p =>
        p.toLowerCase().startsWith(providerStart.toLowerCase())
      )
      if (!matchingProvider) {
        this.interactor.displayText(`No AI provider matches '${providerStart}'`)
        return context
      }
      provider = matchingProvider
    } else {
      // Show current configuration only when asking for selection
      const status: string = [
        "Current AI providers configuration:",
        ...AI_PROVIDERS.map(provider =>
          `${currentConfig[provider] ? "✅" : "❌"} ${provider}`
        )
      ].join("\n")
      this.interactor.displayText(status)
      
      const selectedProvider: string = await this.interactor.chooseOption(
        [...AI_PROVIDERS, keywords.exit],
        "Select an AI provider to configure",
        "Enter provider name or 'exit' to cancel",
      )
      
      if (!selectedProvider || selectedProvider === keywords.exit) {
        return context
      }
      provider = selectedProvider as AiProvider
    }
    
    let apiKey: string
    if (apiKeyFromCommand) {
      apiKey = apiKeyFromCommand
    } else {
      apiKey = await this.interactor.promptText(
        `Enter API key for ${provider}`,
      )
      if (!apiKey) {
        this.interactor.displayText("No API key provided")
        return context
      }
    }
    
    const newConfig: AiConfig = {...currentConfig}
    newConfig[provider] = {apiKey}
    this.interactor.displayText(`✅ ${provider} configured successfully`)
    configService.saveProjectConfig({ai: newConfig})
    
    return context
  }
}
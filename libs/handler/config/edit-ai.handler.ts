import { CommandContext, CommandHandler, Interactor } from '../../model'
import { keywords } from '../../keywords'
import { userConfigService } from '../../service/user-config.service'
import { DEFAULT_USER_CONFIG } from '../../model/user-config'

const AI_PROVIDERS: readonly ['anthropic', 'openai', 'google'] = ['anthropic', 'openai', 'google'] as const
type AiProvider = (typeof AI_PROVIDERS)[number]

/**
 * Handler for configuring AI provider API keys in user configuration.
 *
 * This handler allows users to:
 * - View current AI provider configurations
 * - Set or update API keys for any supported provider
 * - Use direct commands like "edit-ai anthropic sk-xxx"
 *
 * API keys are stored in ~/.coday/user.yml and can be temporarily
 * overridden by environment variables (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY).
 */
export class EditAiHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'edit-ai',
      description:
        'Configure AI providers API keys. Usage:\n' +
        '  edit-ai                    : Interactive mode, shows current config\n' +
        '  edit-ai anthropic          : Configure Anthropic (Claude)\n' +
        '  edit-ai openai sk-xxx      : Direct key set for OpenAI\n' +
        '  edit-ai gem xxx            : Direct key set for Gemini (partial name ok)',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // Parse command for provider name start and api key
    const subCommand: string = this.getSubCommand(command)
    const [providerStart, ...apiKeyParts]: string[] = subCommand.split(' ')
    const apiKeyFromCommand: string | undefined = apiKeyParts.join(' ')

    let provider: AiProvider
    if (providerStart) {
      // Try to find matching provider by start of name (case insensitive)
      const matchingProvider: AiProvider | undefined = AI_PROVIDERS.find((p) =>
        p.toLowerCase().startsWith(providerStart.toLowerCase())
      )
      if (!matchingProvider) {
        this.interactor.displayText(`No AI provider matches '${providerStart}'`)
        return context
      }
      provider = matchingProvider
    } else {
      // Interactive mode: show current configuration and prompt for provider
      const currentConfig = userConfigService.currentConfig || DEFAULT_USER_CONFIG
      const status: string = [
        'Current AI providers configuration:',
        ...AI_PROVIDERS.map((provider) => `${currentConfig.aiProviders[provider] ? '✅' : '❌'} ${provider}`),
      ].join('\n')
      this.interactor.displayText(status)

      const selectedProvider: string = await this.interactor.chooseOption(
        [...AI_PROVIDERS, keywords.exit],
        'Select an AI provider to configure',
        "Enter provider name or 'exit' to cancel"
      )

      if (!selectedProvider || selectedProvider === keywords.exit) {
        return context
      }
      provider = selectedProvider as AiProvider
    }

    // Get API key: either from command or prompt user
    let apiKey: string
    if (apiKeyFromCommand) {
      apiKey = apiKeyFromCommand
    } else {
      apiKey = await this.interactor.promptText(`Enter API key for ${provider}`)
      if (!apiKey) {
        this.interactor.displayText('No API key provided')
        return context
      }
    }

    // Update configuration
    const currentConfig = userConfigService.currentConfig || DEFAULT_USER_CONFIG
    const newProviders = { ...currentConfig.aiProviders }
    newProviders[provider] = { apiKey }

    this.interactor.displayText(`✅ ${provider} configured successfully`)
    userConfigService.updateConfig({ aiProviders: newProviders })

    return context
  }
}

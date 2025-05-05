import { CommandContext, CommandHandler } from '../../model'
import { keywords } from '../../keywords'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'
import { LocalLlmConfig } from '../../model/ai-providers'

const MASKED_VALUE = '********'
const AI_PROVIDERS = ['anthropic', 'openai', 'google', 'localLlm'] as const
type AiProvider = (typeof AI_PROVIDERS)[number]

/**
 * Handler for configuring AI provider API keys in user configuration.
 *
 * This handler allows users to:
 * - View current user-level AI provider configurations
 * - Set or update personal API keys for any supported provider
 * - Use direct commands like "config ai user anthropic sk-xxx"
 * - Configure local LLM settings with interactive prompts
 *
 * API keys are stored in ~/.coday/user.yml and can be temporarily
 * overridden by environment variables (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, LOCAL_LLM_API_KEY).
 */
export class UserAiConfigHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'user',
      description:
        'Configure personal AI provider settings. Usage:\n' +
        '      config ai user                    : Interactive mode, shows current config\n' +
        '      config ai user anthropic          : Configure Anthropic (Claude)\n' +
        '      config ai user openai sk-xxx      : Direct key set for OpenAI\n' +
        '      config ai user gem xxx            : Direct key set for Gemini (partial name ok)\n' +
        '      config ai user local              : Configure local LLM settings',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const project = this.services.project.selectedProject
    if (!project) {
      this.interactor.displayText('No current project, select one first.')
      return context
    }

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
      const currentConfig = this.services.user.config
      const projectConfig = project.config
      const currentAiProviders = currentConfig.ai
      const projectAiProviders = projectConfig.ai
      const status: string = [
        'Current user-level AI providers configuration:',
        ...AI_PROVIDERS.map((provider) => {
          const userSet = currentAiProviders[provider]
          const projectSet = projectAiProviders[provider]
          let details = ''
          if (provider === 'localLlm' && userSet) {
            const localConfig = userSet as LocalLlmConfig
            details = ` (URL: ${localConfig.url}${localConfig.model ? `, Model: ${localConfig.model}` : ''})`
          }
          return `${userSet ? '✅' : '❌'} ${provider}${details}${projectSet ? ' (project default available)' : ''}`
        }),
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

    // Special handling for local LLM configuration
    if (provider === 'localLlm') {
      await this.configureLocalLlm()
      return context
    }

    // Get current value if any
    const currentConfig = this.services.user.config
    const currentApiKey = currentConfig.aiProviders[provider]?.apiKey
    const projectHasKey = project.config.aiProviders[provider]

    // Get API key: either from command or prompt user
    let apiKey: string | undefined
    if (apiKeyFromCommand) {
      apiKey = apiKeyFromCommand
    } else {
      // Special handling for API key with masking
      const apiKeyPrompt = currentApiKey
        ? `Enter API key for ${provider} (Current value is masked, validate to leave unchanged${
            projectHasKey ? ', project default available' : ''
          })`
        : `Enter API key for ${provider}${projectHasKey ? ' (project default available)' : ''}`

      const apiKeyInput = await this.interactor.promptText(apiKeyPrompt, currentApiKey ? MASKED_VALUE : undefined)

      // Handle the different input cases
      if (apiKeyInput === MASKED_VALUE) {
        // Keep existing API key
        if (currentApiKey) apiKey = currentApiKey
      } else if (apiKeyInput === '' || apiKeyInput === null) {
        // Explicitly remove API key
        apiKey = undefined
      } else {
        // New API key provided
        apiKey = apiKeyInput
      }
    }

    // Update configuration
    const newProviders = currentConfig.aiProviders
    if (apiKey === undefined) {
      // Remove the provider config if it exists
      delete newProviders[provider]
      this.interactor.displayText(`✅ ${provider} configuration removed`)
    } else {
      // Set the new API key
      newProviders[provider] = { apiKey }
      this.interactor.displayText(`✅ ${provider} configured successfully`)
    }

    this.services.user.save()

    return context
  }

  /**
   * Interactive configuration for local LLM settings
   */
  private async configureLocalLlm(): Promise<void> {
    const currentConfig = this.services.user.config
    const currentSettings = currentConfig.aiProviders.localLlm as LocalLlmConfig | undefined

    // Get URL (required)
    const url = await this.interactor.promptText(
      'Enter the URL for your local LLM server (e.g., http://localhost:1234/v1)',
      currentSettings?.url
    )
    if (!url) {
      if (currentSettings) {
        delete currentConfig.aiProviders.localLlm
        this.interactor.displayText('✅ Local LLM configuration removed')
        this.services.user.save()
      }
      return
    }

    // Get model name (optional)
    const model = await this.interactor.promptText(
      'Enter the model name (optional, press Enter to skip)',
      currentSettings?.model || ''
    )

    // Get context window (optional)
    const contextWindowStr = await this.interactor.promptText(
      'Enter the context window size in tokens (optional, press Enter for default 8192)',
      currentSettings?.contextWindow?.toString() || '8192'
    )
    const contextWindow = contextWindowStr ? parseInt(contextWindowStr, 10) : undefined

    // Get API key (optional, depends on implementation)
    const apiKey = await this.interactor.promptText(
      'Enter API key if required by your local LLM server (optional, press Enter to skip)',
      currentSettings?.apiKey || ''
    )

    // Update configuration
    currentConfig.aiProviders.localLlm = {
      url,
      ...(model && { model }),
      ...(contextWindow && { contextWindow }),
      ...(apiKey && { apiKey }),
    }

    this.interactor.displayText('✅ Local LLM configured successfully')
    this.services.user.save()
  }
}

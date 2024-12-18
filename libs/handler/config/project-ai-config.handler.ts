import { CommandContext, CommandHandler } from '../../model'
import { keywords } from '../../keywords'
import { Interactor } from '../../model/interactor'
import { CodayServices } from '../../coday-services'

const MASKED_VALUE = '********'
const AI_PROVIDERS: readonly ['anthropic', 'openai', 'google'] = ['anthropic', 'openai', 'google'] as const
type AiProvider = (typeof AI_PROVIDERS)[number]

/**
 * Handler for configuring AI provider API keys in project configuration.
 *
 * This handler allows project-wide configuration of AI providers:
 * - View current project-level AI provider configurations
 * - Set or update API keys that will be available to all project users
 * - Use direct commands like "config ai project anthropic sk-xxx"
 *
 * API keys are stored in the project's .coday.yml and will be the default
 * for all users unless they configure their own keys.
 */
export class ProjectAiConfigHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'project',
      description:
        'Configure project-wide AI provider settings (affects all users). Usage:\n' +
        '      config ai project                    : Interactive mode, shows current config\n' +
        '      config ai project anthropic          : Configure Anthropic (Claude)\n' +
        '      config ai project openai sk-xxx      : Direct key set for OpenAI\n' +
        '      config ai project gem xxx            : Direct key set for Gemini (partial name ok)',
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
      const projectConfig = project.config
      const status: string = [
        '⚠️ Project-wide AI provider settings will affect all users of this project.',
        '',
        'Current project-level AI providers configuration:',
        ...AI_PROVIDERS.map((provider) => `${projectConfig.aiProviders[provider] ? '✅' : '❌'} ${provider}`),
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

    // Get current value if any
    const currentApiKey = project.config.aiProviders[provider]?.apiKey

    // Get API key: either from command or prompt user
    let apiKey: string | undefined
    if (apiKeyFromCommand) {
      apiKey = apiKeyFromCommand
    } else {
      // Special handling for API key with masking
      const apiKeyPrompt = currentApiKey
        ? `Enter API key for ${provider} (Current value is masked, validate to leave unchanged)`
        : `Enter API key for ${provider}`

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

    // Update project configuration
    if (apiKey === undefined) {
      // Remove the provider from project config if it exists
      const newAiProviders = { ...project.config.aiProviders }
      delete newAiProviders[provider]
      this.services.project.save({ aiProviders: newAiProviders })
      this.interactor.displayText(`✅ ${provider} configuration removed from project`)
    } else {
      // Set the new API key
      this.services.project.save({
        aiProviders: {
          ...project.config.aiProviders,
          [provider]: { apiKey },
        },
      })
      this.interactor.displayText(`✅ ${provider} configured successfully for all project users`)
    }

    return context
  }
}

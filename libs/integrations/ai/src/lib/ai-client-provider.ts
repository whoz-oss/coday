import { CommandContext, OpenaiClient } from '@coday/handler'
import { AnthropicClient } from '@coday/handler'
import { UserService } from '@coday/service/user.service'
import { ProjectStateService } from '@coday/service/project-state.service'
import { GoogleClient } from '@coday/handler'
import { CodayLogger } from '@coday/service/coday-logger'
import { AiClient } from '@coday/agent'
import { AiProviderConfig } from '@coday/model/ai-provider-config'
import { Interactor } from '@coday/model/interactor'

/**
 * Environment variable names for each provider.
 * Used for both overriding configured providers and auto-detection.
 */
const ENV_VARS: Record<string, string> = {
  anthropic: 'ANTHROPIC_API_KEY',
  openai: 'OPENAI_API_KEY',
  google: 'GEMINI_API_KEY',
}

/**
 * Manages AI client instances and their configuration.
 *
 * Key features:
 * - Caches client instances per provider
 * - Handles configuration from user config and env vars
 * - Provides default client selection based on priority
 *
 * Important behaviors:
 * - A provider must be configured in user config to be available
 * - Environment variables can only override existing configurations
 * - Client instances are cached per provider within this instance
 */
export class AiClientProvider {
  private aiClients: AiClient[] = []
  private aiProviderConfigs: AiProviderConfig[] | undefined

  constructor(
    private readonly interactor: Interactor,
    private readonly userService: UserService,
    private readonly projectService: ProjectStateService,
    private readonly logger: CodayLogger
  ) {}

  init(context: CommandContext): void {
    if (this.aiProviderConfigs) return
    // get the ai def and models from coday.yaml
    const aiCodayYaml = context.project.ai || []
    const projectYaml = this.projectService.selectedProject?.config?.ai || []
    const userYaml = this.userService.config.ai || []

    // Auto-detect providers from environment variables
    const autoDetectedConfigs = this.detectProvidersFromEnvironment()

    // Track which providers were explicitly configured (not auto-detected)
    const explicitProviderNames = new Set([
      ...aiCodayYaml.map((c) => c.name.toLowerCase()),
      ...projectYaml.map((c) => c.name.toLowerCase()),
      ...userYaml.map((c) => c.name.toLowerCase()),
    ])

    this.aiProviderConfigs = []

    // merge all ai definitions one over the others, order is important !
    // Auto-detected configs go first (lowest priority), then explicit configs
    for (const aiDef of [...autoDetectedConfigs, ...aiCodayYaml, ...projectYaml, ...userYaml]) {
      const i = this.aiProviderConfigs.findIndex((a) => a.name === aiDef.name)
      if (i === -1) {
        // definition of a new provider, add it
        this.aiProviderConfigs.push(aiDef)
      } else {
        // existing provider, merge new def in it
        const currentConfig = this.aiProviderConfigs[i]!
        const currentModels = currentConfig.models ? [...currentConfig.models] : []
        const aiDefModels = aiDef.models ?? []
        aiDefModels.forEach((model) => {
          const j = currentModels.findIndex((m) => m.alias === model.alias || m.name === model.name)
          if (j === -1) {
            // adding a new model def
            currentModels.push(model)
          } else {
            // merging the new model def over the current one
            const current = currentModels[j]!
            currentModels[j] = { ...current, ...model, price: { ...current.price, ...model.price } }
          }
        })
        this.aiProviderConfigs[i] = { ...currentConfig, ...aiDef, models: currentModels }
      }
    }

    // then try to instantiate all clients
    this.aiClients = this.aiProviderConfigs.map((config) => this.createClient(config)).filter((client) => !!client)

    // ... and log the result
    // Only show "auto-detected" label for providers that have NO explicit configuration
    const autoDetectedOnlyProviders = autoDetectedConfigs
      .filter((c) => !explicitProviderNames.has(c.name.toLowerCase()))
      .map((c) => c.name.toLowerCase())
    const autoDetectedProvidersSet = new Set(autoDetectedOnlyProviders)

    const clientLog =
      `AI providers (models listed as: name (alias)):` +
      '\n' +
      this.aiProviderConfigs
        .map((config) => {
          const client = this.aiClients.find((c) => c.name.toLowerCase() === config.name.toLowerCase())
          const isSuccess = !!client
          const prefix = isSuccess ? '✅' : '❌'
          const isAutoDetectedOnly = autoDetectedProvidersSet.has(config.name.toLowerCase())
          const autoDetectLabel = isAutoDetectedOnly ? ' (auto-detected)' : ''
          let line = ` - ${prefix} ${config.name}${autoDetectLabel}`
          if (isSuccess && Array.isArray(client.models) && client.models.length > 0) {
            // Build one-line model list: name (alias) if alias, otherwise just name
            const modelsStr = client.models
              .map((model) => {
                if (model.alias && model.alias !== model.name) {
                  return `${model.name} (${model.alias})`
                }
                return model.name
              })
              .join(', ')
            line += `, models: ${modelsStr}`
          }
          return line
        })
        .join('\n')
    this.interactor.displayText(clientLog)
  }

  /**
   * Get an AI client instance, either for a specific provider
   * or the first available one according to priority order.
   *
   * @param provider Optional provider name, if not provided returns first available
   * @param name Name or alias of the model
   * @returns AiClient instance or undefined if no client available
   */
  getClient(provider: string | undefined, name?: string | undefined): AiClient | undefined {
    // filter providers by name and provider
    const clients = this.aiClients.filter((client) => {
      const matchesProviderName = !provider || client.name.toLowerCase() === provider.toLowerCase()
      const supportsModel = !name || client.supportsModel(name.toLowerCase())
      return matchesProviderName && supportsModel
    })

    return clients.length ? clients[0] : undefined
  }

  /**
   * Get all available models from all configured providers.
   * Returns an array of model information including name and provider.
   *
   * @returns Array of models with their provider information
   */
  getAllModels(): Array<{ name: string; providerName: string }> {
    const models: Array<{ name: string; providerName: string }> = []

    for (const client of this.aiClients) {
      if (client.models) {
        for (const model of client.models) {
          models.push({
            name: model.name,
            providerName: client.name,
          })
        }
      }
    }

    return models
  }

  /**
   * Cleanup AI clients for fresh connections but keep configurations.
   * Used when ending a conversation but keeping Coday instance alive.
   */
  public cleanup(): void {
    this.aiClients.forEach((client) => client.kill())
    this.aiClients = []
    // Keep aiProviderConfigs so we can recreate clients later
  }

  /**
   * Completely destroy all AI clients and configurations.
   * Used when terminating the Coday instance entirely.
   */
  public kill(): void {
    this.cleanup()
    this.aiProviderConfigs = undefined
  }

  private getApiKey(aiProviderConfig: AiProviderConfig): string | undefined {
    const envVar = ENV_VARS[aiProviderConfig.name]
    const envKey = envVar
      ? (process.env[envVar] ?? process.env[`${aiProviderConfig.name.toUpperCase()}_API_KEY`])
      : undefined
    return envKey ?? aiProviderConfig.apiKey
  }

  /**
   * Detects AI providers from environment variables.
   * Creates minimal configurations for providers that have API keys set
   * but are not explicitly configured.
   *
   * Note: Default models are defined in each client implementation
   * (AnthropicClient, GoogleClient, OpenaiClient) and will be merged
   * during client creation.
   *
   * @returns Array of auto-detected provider configurations
   */
  private detectProvidersFromEnvironment(): AiProviderConfig[] {
    const detected: AiProviderConfig[] = []

    for (const [providerName, envVarName] of Object.entries(ENV_VARS)) {
      const apiKey = process.env[envVarName]
      if (apiKey) {
        // Create a minimal configuration - client will provide default models
        const defaultConfig: AiProviderConfig = {
          name: providerName,
          apiKey,
        }
        detected.push(defaultConfig)
        this.interactor.debug(`🔍 Auto-detected ${providerName} provider from ${envVarName} environment variable`)
      }
    }

    return detected
  }

  /**
   * Creates a new client instance for the specified provider.
   *
   * @param aiProviderConfig The AI provider to create client for
   * @returns New client instance or undefined if provider not supported
   */
  private createClient(aiProviderConfig: AiProviderConfig): AiClient | undefined {
    const apiKey = this.getApiKey(aiProviderConfig)
    // Could be controversial, but always expect an apiKey, even for local providers
    if (!apiKey) {
      this.interactor.displayText(`ℹ️ no api key for AI provider '${aiProviderConfig.name}'`)
      return undefined
    }
    // override the existing apiKey
    const config = { ...aiProviderConfig, apiKey }
    switch (aiProviderConfig.name.toLowerCase()) {
      case 'anthropic':
        return new AnthropicClient(this.interactor, config, this.logger)
      case 'google':
        return new GoogleClient(this.interactor, config, this.logger)
      default:
        return new OpenaiClient(this.interactor, config, this.logger)
    }
  }
}

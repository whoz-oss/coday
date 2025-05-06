import { AiClient, AiProviderConfig, CommandContext, Interactor } from '../../model'
import { OpenaiClient } from '../../handler/openai.client'
import { AnthropicClient } from '../../handler/anthropic.client'
import { UserService } from '../../service/user.service'
import { ProjectService } from '../../service/project.service'
import { GoogleClient } from '../../handler/google.client'
import { CodayLogger } from '../../service/coday-logger'

/**
 * Environment variable names for each provider.
 * These can override (but not enable) configured providers.
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
    private userService: UserService,
    private projectService: ProjectService,
    private logger: CodayLogger
  ) {}

  init(context: CommandContext): void {
    if (this.aiProviderConfigs) return
    // get the ai def and models from coday.yaml
    const aiCodayYaml = context.project.ai || []
    const projectYaml = this.projectService.selectedProject?.config?.ai || []
    const userYaml = this.userService.config.ai || []
    this.aiProviderConfigs = []

    // merge all ai definitions one over the others, order is important !
    for (const aiDef of [...aiCodayYaml, ...projectYaml, ...userYaml]) {
      const i = this.aiProviderConfigs.findIndex((a) => a.name === aiDef.name)
      if (i === -1) {
        // definition of a new provider, add it
        this.aiProviderConfigs.push(aiDef)
      } else {
        // existing provider, merge new def in it
        const currentConfig = this.aiProviderConfigs[i]
        const currentModels = currentConfig.models ? [...currentConfig.models] : []
        const aiDefModels = aiDef.models ?? []
        aiDefModels.forEach((model) => {
          const j = currentModels.findIndex((m) => m.alias === model.alias || m.name === model.name)
          if (j === -1) {
            // adding a new model def
            currentModels.push(model)
          } else {
            // merging the new model def over the current one
            const current = currentModels[j]
            currentModels[j] = { ...current, ...model, price: { ...current.price, ...model.price } }
          }
        })
        this.aiProviderConfigs[i] = { ...currentConfig, ...aiDef, models: currentModels }
      }
    }

    // then try to instantiate all clients
    this.aiClients = this.aiProviderConfigs.map((config) => this.createClient(config))

    // ... and log the result
    const clientSucess = this.aiClients.map((client) => client.name)
    const clientStatus = this.aiProviderConfigs.map(
      (config) => `${clientSucess.includes(config.name) ? '✅' : '❌'} ${config.name}`
    )
    const clientLog = `AI providers:
${clientStatus.map((status) => ` - ${status}`).join('\n')}
`
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
    console.log(`ai client provider: get ${provider}, ${name}`)
    const clients = this.aiClients.filter(
      (client) =>
        (!provider || client.name.toLowerCase() === provider.toLowerCase()) && (!name || client.supportsModel(name))
    )

    return clients.length ? clients[0] : undefined
  }

  public kill(): void {
    this.aiClients.forEach((client, key, map) => client.kill())
    this.aiProviderConfigs = undefined
  }

  private getApiKey(aiProviderConfig: AiProviderConfig): string | undefined {
    const envKey =
      process.env[ENV_VARS[aiProviderConfig.name]] || process.env[`${aiProviderConfig.name.toUpperCase()}_API_KEY`]
    return envKey || aiProviderConfig.apiKey
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
    if (!apiKey) return undefined
    const config = { ...aiProviderConfig, apiKey }
    switch (aiProviderConfig.name) {
      case 'anthropic':
        return new AnthropicClient(this.interactor, aiProviderConfig)
      case 'google':
        return new GoogleClient(this.interactor, aiProviderConfig)
      default:
        return new OpenaiClient(this.interactor, config)
    }
  }
}

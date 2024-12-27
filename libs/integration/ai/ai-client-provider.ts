import {AiClient, AiProvider, Interactor} from '../../model'
import {OpenaiClient} from '../../handler/openai.client'
import {AnthropicClient} from '../../handler/anthropic.client'
import {UserService} from '../../service/user.service'
import {ProjectService} from '../../service/project.service'

/**
 * Environment variable names for each provider.
 * These can override (but not enable) configured providers.
 */
const ENV_VARS: Record<AiProvider, string> = {
  anthropic: 'ANTHROPIC_API_KEY',
  openai: 'OPENAI_API_KEY',
  google: 'GEMINI_API_KEY',
  localLlm: 'LOCAL_LLM_API_KEY', // Usually not needed but kept for consistency
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
  /** Cache of instantiated clients to avoid recreation */
  private readonly clientCache: Map<AiProvider, AiClient> = new Map()

  /**
   * Order of preference for selecting default provider.
   * Used when no specific provider is requested.
   */
  private readonly providerOrder: AiProvider[] = ['anthropic', 'openai', 'google', 'localLlm']

  constructor(
    private readonly interactor: Interactor,
    private userService: UserService,
    private projectService: ProjectService
  ) {}

  /**
   * Get an AI client instance, either for a specific provider
   * or the first available one according to priority order.
   *
   * @param provider Optional provider name, if not provided returns first available
   * @returns AiClient instance or undefined if no client available
   */
  getClient(provider?: AiProvider): AiClient | undefined {
    // If no provider specified, find first available in order
    if (!provider) {
      for (const p of this.providerOrder) {
        const client = this.getClient(p)
        if (client) {
          return client
        }
      }
      return undefined
    }

    // Check cache first
    let client = this.clientCache.get(provider)
    if (client) {
      return client
    }

    // Create new client with the api key provider
    client = this.createClient(provider)
    if (client) {
      this.clientCache.set(provider, client)
    }

    return client
  }

  public kill(): void {
    const clients: AiClient[] = Array.from(this.clientCache.values())
    for (const client of clients) {
      client.kill()
    }
  }

  private getApiKey(provider: AiProvider): string | undefined {
    const userKey: string | undefined = this.userService.config.aiProviders[provider]?.apiKey
    const projectKey = this.projectService.selectedProject?.config?.aiProviders[provider]?.apiKey
    const envKey = process.env[ENV_VARS[provider]]
    return envKey || userKey || projectKey
  }

  /**
   * Creates a new client instance for the specified provider.
   *
   * @param provider The AI provider to create client for
   * @returns New client instance or undefined if provider not supported
   */
  private createClient(provider: AiProvider): AiClient | undefined {
    console.log(`create client with ${provider}`)
    const apiKey = this.getApiKey(provider)
    switch (provider) {
      case 'anthropic':
        if (!apiKey) return undefined
        return new AnthropicClient(this.interactor, apiKey)
      case 'openai':
        if (!apiKey) return undefined
        return new OpenaiClient('OpenAI', this.interactor, apiKey)
      case 'google':
        if (!apiKey) return undefined
        // Leveraging Google Gemini enabling use of Openai SDK for beta
        return new OpenaiClient(
          'Google Gemini',
          this.interactor,
          apiKey,
          'https://generativelanguage.googleapis.com/v1beta/openai/',
          {}, //TODO: Gemini models !
          'Gemini'
        )
      case 'localLlm':
        const config = this.userService.config.aiProviders.localLlm
        console.log('localLlm config', config)
        if (!config) return undefined

        // Create custom model configuration
        const localModels = {
          BIG: {
            name: config.model || 'local-model',
            contextWindow: config.contextWindow || 8192,
            price: {
              inputMTokens: 0,
              cacheRead: 0,
              outputMTokens: 0,
            },
          },
          SMALL: {
            name: config.model || 'local-model',
            contextWindow: config.contextWindow || 8192,
            price: {
              inputMTokens: 0,
              cacheRead: 0,
              outputMTokens: 0,
            },
          },
        }

        return new OpenaiClient(
          'Local LLM',
          this.interactor,
          apiKey ?? 'no_api_key_for_local_llm',
          config.url,
          localModels,
          'Local'
        )
    }
  }
}

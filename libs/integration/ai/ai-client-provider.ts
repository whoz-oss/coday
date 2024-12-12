import {AiClient, AiProvider, Interactor} from "../../model"
import {OpenaiClient} from "../../handler/openai.client"
import {AnthropicClient} from "../../handler/anthropic.client"
import {userConfigService} from "../../service/user-config.service"

/**
 * Environment variable names for each provider.
 * These can override (but not enable) configured providers.
 */
const ENV_VARS: Record<AiProvider, string> = {
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
class AiClientProvider {
  /** Cache of instantiated clients to avoid recreation */
  private readonly clientCache: Map<AiProvider, AiClient> = new Map()

  /**
   * Order of preference for selecting default provider.
   * Used when no specific provider is requested.
   */
  private readonly providerOrder: AiProvider[] = ['anthropic', 'openai', 'google']

  constructor(private readonly interactor: Interactor) {}

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

    // Check if provider has a config or env var
    const apiKeyProvider = this.createApiKeyProvider(provider)
    const apiKey = apiKeyProvider()
    if (!apiKey) {
      return undefined
    }

    // Create new client with the api key provider
    client = this.createClient(provider, apiKeyProvider)
    if (client) {
      this.clientCache.set(provider, client)
    }

    return client
  }

  /**
   * Creates a function that provides the API key for a given provider.
   * The key is sourced from:
   * 1. Environment variable (if provider is configured)
   * 2. User configuration
   *
   * Note: Provider must be configured in user config to be available,
   * environment variables can only override existing configurations.
   *
   * @param provider The AI provider to get key for
   * @returns Function that returns current API key or undefined
   */
  private createApiKeyProvider(provider: AiProvider): () => string | undefined {
    return () => {
      // First check if provider is configured (required)
      const configuredKey = userConfigService.currentConfig?.aiProviders[provider]?.apiKey
      if (!configuredKey) {
        return undefined
      }

      // If configured, environment variable can override
      const envKey = process.env[ENV_VARS[provider]]
      return envKey || configuredKey
    }
  }

  /**
   * Creates a new client instance for the specified provider.
   *
   * @param provider The AI provider to create client for
   * @param apiKeyProvider Function that provides the API key
   * @returns New client instance or undefined if provider not supported
   */
  private createClient(provider: AiProvider, apiKeyProvider: () => string | undefined): AiClient | undefined {
    switch (provider) {
      case 'anthropic':
        return new AnthropicClient(this.interactor, apiKeyProvider)
      case 'openai':
        return new OpenaiClient('OpenAI', this.interactor, apiKeyProvider)
      case 'google':
        // Leveraging Google Gemini enabling use of Openai SDK for beta
        return new OpenaiClient(
          'Google Gemini',
          this.interactor,
          apiKeyProvider,
          'https://generativelanguage.googleapis.com/v1beta/openai/',
          {}, //TODO: Gemini models !
          'Gemini'
        )
    }
  }

  kill() {
    const clients: AiClient[] = Array.from(this.clientCache.values())
    for (const client of clients) {
      client.kill()
    }
  }
}

export { AiClientProvider }

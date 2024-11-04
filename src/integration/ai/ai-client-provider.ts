import {AiClient, Interactor} from "../../model"
import {OpenaiClient} from "../../handler/openai.client"
import {GeminiClient} from "../../handler/gemini.client"
import {ClaudeClient} from "../../handler/claude.client"
import {userConfigService} from "../../service/user-config.service"

type AiProvider = "anthropic" | "openai" | "gemini"

// Environment variable names for each provider
const ENV_VARS: Record<AiProvider, string> = {
  "anthropic": "ANTHROPIC_API_KEY",
  "openai": "OPENAI_API_KEY",
  "gemini": "GEMINI_API_KEY"
}

class AiClientProvider {
  private readonly clientCache: Map<AiProvider, AiClient> = new Map()
  private readonly providerOrder: AiProvider[] = ["anthropic", "openai", "gemini"]
  
  constructor(
    private readonly interactor: Interactor
  ) {
  }
  
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
  
  private createClient(provider: AiProvider, apiKeyProvider: () => string | undefined): AiClient | undefined {
    switch (provider) {
      case "anthropic":
        return new ClaudeClient(this.interactor, apiKeyProvider)
      case "openai":
        return new OpenaiClient(this.interactor, apiKeyProvider)
      case "gemini":
        return new GeminiClient(this.interactor, apiKeyProvider)
    }
  }
}

export {AiClientProvider}
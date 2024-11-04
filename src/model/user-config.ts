export type AiProviderConfig = {
  apiKey: string
}

export interface UserConfig {
  aiProviders: {
    anthropic?: AiProviderConfig
    openai?: AiProviderConfig
    gemini?: AiProviderConfig
  }
}

// Default empty configuration
export const DEFAULT_USER_CONFIG: UserConfig = {
  aiProviders: {}
}
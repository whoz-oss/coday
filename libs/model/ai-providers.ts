/**
 * Configuration for an AI provider's authentication.
 * Stored in user configuration, not in project config
 * to avoid multiple configurations of the same provider.
 */
export interface AiProviderConfig {
  apiKey?: string
}

/**
 * AI provider configurations.
 * Each provider is optional but must be configured here
 * to be available (environment variables can only override
 * existing configurations, not enable new providers).
 */
export interface AiProviderLocalConfig {
  /** Anthropic's Claude (recommended) */
  anthropic?: AiProviderConfig
  /** OpenAI's GPT models */
  openai?: AiProviderConfig
  /** Google's Gemini */
  google?: AiProviderConfig
}

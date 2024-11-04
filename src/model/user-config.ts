/**
 * Configuration for an AI provider's authentication.
 * Stored in user configuration, not in project config
 * to avoid multiple configurations of the same provider.
 */
export type AiProviderConfig = {
  apiKey: string
}

/**
 * User-level configuration, stored in ~/.coday/user.yml
 * Contains settings that are specific to the user and
 * should not be shared across users or stored in project
 * configuration files.
 *
 * Currently handles:
 * - AI provider configurations with API keys
 * - (future) User preferences
 * - (future) Default settings
 */
export interface UserConfig {
  /**
   * AI provider configurations.
   * Each provider is optional but must be configured here
   * to be available (environment variables can only override
   * existing configurations, not enable new providers).
   */
  aiProviders: {
    /** Anthropic's Claude (recommended) */
    anthropic?: AiProviderConfig
    /** OpenAI's GPT models */
    openai?: AiProviderConfig
    /** Google's Gemini */
    gemini?: AiProviderConfig
  }
}

/**
 * Initial configuration structure.
 * Used when creating a new user config file
 * or when handling null configurations.
 */
export const DEFAULT_USER_CONFIG: UserConfig = {
  aiProviders: {}
}
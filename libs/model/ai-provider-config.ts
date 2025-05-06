import { AiModel } from './ai-model'

export interface AiProviderConfig {
  /**
   * Name to use in agent declaration.
   * If 'openai' or 'anthropic' or 'google', defaults are applied to missing parameters
   */
  name: string

  /**
   * Mandatory when not one of the default names
   */
  type?: 'openai' | 'anthropic'
  url?: string
  apiKey?: string

  /**
   * Indicates if the provider is on secured infra, non-cloud, no data exposed
   */
  secure?: boolean

  /**
   * Collection of model definitions to add to the defaults (if any depending on the name)
   */
  models?: AiModel[]
}

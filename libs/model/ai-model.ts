export interface AiModel {
  /**
   * Name of the model as per API
   */
  name: string

  /**
   * Project-friendly name to use to identify a precise model
   * Can be used to select different models from different AiProviders
   */
  alias?: string

  /**
   * Number of tokens the model can accept
   * Used to shorten the context window when reaching the limits
   */
  contextWindow: number

  /**
   * If thinking model, have thinking properties tied to the AiProvider here
   */
  thinking?: any

  /**
   * Pricing data, not including it will prevent cost monitoring (iterations still monitored though).
   * All values are per Million Tokens = 1.000.000
   */
  price?: {
    inputMTokens?: number
    cacheWrite?: number
    cacheRead?: number
    outputMTokens?: number
  }
}

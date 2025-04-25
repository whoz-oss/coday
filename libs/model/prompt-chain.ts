/**
 * A chain of prompts to be used sequentially.
 */
export type PromptChain = {
  /**
   * Description of the prompt chain
   */
  description: string

  /**
   * List of commands in the prompt chain
   */
  commands: string[]

  /**
   * Optional list of required integrations for this prompt chain
   */
  requiredIntegrations?: string[]
}

/**
 * Agent description as intended in yml, in the `agents/` folder at same location as `coday.yml`
 *
 * Agents are defined by the user, as part of teams (or the default agent pool), or as internal agents for:
 *   - routing inside a team
 *   - summarizing part of an AiThread
 *   - wrapping a full complex integration (JIRA ?)
 */
export interface AgentDefinition {
  /**
   * name of the agent
   * case does not matter as should be checked against lowercase
   */
  name: string
  
  /**
   * description of the agent for the other agents and user
   */
  description: string
  
  /**
   * Team names the agent belongs to, implicit creation
   */
  teams?: string[]
  
  /**
   * System instructions of the agent.
   * Could be optional for Openai assistants having already tools and files ?
   */
  instructions?: string
  
  /**
   * Define who is providing the service (see later for custom/local llm ?)
   */
  // TODO: optional (ie uses a default or preferential provider) or mandatory ?
  aiProvider: AiProvider
  
  /**
   * Selects a model size, should default to BIG, tied to the selected AiProvider
   */
  modelSize?: ModelSize
  
  /**
   * overrides modelSize by selecting directly an explicit model name of an AiProvider
   */
  modelName: string
  
  /**
   * List of integrations the agent can have access to.
   * Integrations need also to be configured in the project to be available.
   * Values are either:
   *   - empty arrays = all tools of this integration allowed
   *   - select list of tools available (useful to restrict to read-only)
   */
  // TODO: better type for integration keys, stay loose for tools ?
  integrations: {
    [key: string]: string[] // key is name of an integration, value is empty (=all) or select list of tool keys to enable
  }
}

export type AiProvider = "OPENAI" | "ANTHROPIC" | "GOOGLE"

export enum ModelSize {
  BIG = "BIG",
  SMALL = "SMALL"
}
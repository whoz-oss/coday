import { WithDocs } from './with-docs'

export enum ModelSize {
  BIG = 'BIG',
  SMALL = 'SMALL',
}

/**
 * Agent description as intended in yml, in the `agents/` folder at same location as `coday.yml`
 *
 * Agents are defined by the user, as part of teams (or the default agent pool), or as internal agents for:
 *   - routing inside a team
 *   - summarizing part of an AiThread
 *   - wrapping a full complex integration (JIRA ?)
 */
export interface AgentDefinition extends WithDocs {
  /**
   * name of the agent
   * case does not matter as should be checked against lowercase
   */
  name: string

  /**
   * id of the assistant on openai's platform
   */
  openaiAssistantId?: string

  /**
   * description of the agent for the other agents and user
   */
  description: string

  /**
   * System instructions of the agent.
   * Could be optional for Openai assistants having already tools and files ?
   */
  instructions?: string

  /**
   * Define who is providing the service (see later for custom/local llm ?)
   */
  aiProvider?: string

  /**
   * Selects a model size, should default to BIG, tied to the selected AiProvider
   * TO DEPRECATE
   */
  modelSize?: ModelSize

  /**
   * overrides modelSize by selecting directly an explicit model name or alias of an AiProvider
   */
  modelName?: string

  /**
   * Temperature of the model, 0 to 2: 0.2 is quite deterministic, 0.8 is "creative", 1.5+ is on LSD.
   */
  temperature?: number

  /**
   * Maximum output tokens for this agent
   * Overrides the model's default maxOutputTokens if specified
   */
  maxOutputTokens?: number

  /**
   * List of integrations the agent can have access to.
   * Integrations need also to be configured in the project to be available.
   * Values are either:
   *   - empty arrays = all tools of this integration allowed
   *   - select list of tools available (useful to restrict to read-only)
   */
  integrations?: {
    [key: string]: string[] // key is name of an integration, value is empty (=all) or select list of tool keys to enable
  }
}

export const CodayAgentDefinition: AgentDefinition = {
  name: 'Coday',
  description: 'Default fallback agent with neutral character and access to all tools',
  instructions: `
You are Coday, an AI assistant designed for interactive usage by users through various chat-like interfaces.

When other agents are available, you should redirect the user request to the appropriate agent, given the topic.
`,
  modelSize: ModelSize.BIG,
  modelName: 'BIG',
  // prepare future restriction of Coday agent
  // integrations: {
  //   FILES: [],
  //   AI: [],
  // },
}

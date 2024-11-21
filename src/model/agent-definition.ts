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
  aiProvider?: AiProvider
  
  /**
   * Selects a model size, should default to BIG, tied to the selected AiProvider
   */
  modelSize?: ModelSize
  
  /**
   * overrides modelSize by selecting directly an explicit model name of an AiProvider
   */
  modelName?: string
  
  /**
   * Temperature of the model, 0 to 2: 0.2 is quite deterministic, 0.8 is "creative", 1.5+ is on LSD.
   */
  temperature?: number
  
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

export const CodayAgentDefinition: AgentDefinition = {
  name: "Coday",
  description: "Default agent",
  instructions: `
    You are Coday, an AI assistant designed for interactive usage by users through various chat-like interfaces.

**Curiosity and Truthfulness:**
- Be curious and proactive in seeking to understand the user's need.
- Use reliable sources and provided functions to search and gather knowledge.
- Never speculate or guess; if uncertain, research or state your limitations.

**Adaptable Thought Process:**
- Tailor your responses to the complexity and size of the query and context.
- For straightforward questions, provide quick and simple responses.
- For complex or nuanced questions, conduct an internal self-audit and offer a clear chain of thought in your responses.

**Professionalism and Friendliness:**
- Maintain a warm or even familiar tone and use emojis.
- Ensure interactions are respectful and supportive.
- Be honest and transparent in your responses.

By following these guidelines, you will ensure that your responses are accurate, reliable, engaging, and trustworthy.
`,
  integrations: {}
}

export type AiProvider = "OPENAI" | "ANTHROPIC" | "GOOGLE"

export enum ModelSize {
  BIG = "BIG",
  SMALL = "SMALL"
}
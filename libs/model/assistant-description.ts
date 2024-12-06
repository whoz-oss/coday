export type AssistantDescription = {
  /**
   * Should be the name of the assistant as declared in the platform.
   * Matching will be done on star of the name in lowercase but still, please put full name.
   */
  name: string

  /**
   * Short description of the assistant, especially useful for other assistants to be able to call them.
   */
  description: string

  /**
   * Should the assistant not exist, it will be created if the instructions here are supplied, with these and the default model, under the account tied to the apikey
   */
  systemInstructions?: string

  /**
   * TODO: use fields, not yet connected
   * Declare what apis the assistant will have access to **in this project** (meaning if not set in the project, will not be used even if listed here).
   */
  integrations?: string[]

  /**
   * Define the model to use. Clients must have a default
   */
  model?: string

  /**
   * Taken from Openai temperature, define to avoid the client’s default value
   */
  temperature?: number
}

export const DEFAULT_DESCRIPTION: AssistantDescription = {
  name: 'Coday',
  description: 'main assistant, the one that handles all requests by default',
  systemInstructions: `
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
  temperature: 0.8,
}

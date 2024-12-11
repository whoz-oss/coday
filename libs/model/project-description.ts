import { Scripts } from './scripts'
import { AssistantDescription } from './assistant-description'
import { AgentDefinition } from './agent-definition'

export type ProjectDescription = {
  /**
   * Description of the current project, it should contain the same high-level information and rules about the project anyone of the team should know.
   *
   * This is used as a system instruction, hence it is sent at the very beginning of any new thread.
   * Whatever the assistant involved is, other assistants will also have access to this message as part of the openai thread (or hopefully context if truncated).
   * If using the default Coday assistant (that is kept quite generic and close to default LLM), the more detailed and broad the description, the more Coday's responses are relevant (by a very wide margin).
   *
   * Recommendations: write it in markdown as you would write human-intended documentation
   */
  description: string

  /**
   * List of project paths to documents that will be loaded as part of the initial system instruction when creating a thread.
   *
   * Be cautious to not expose very large documents that would waste tokens on every new thread, focus on the common core of the project.
   */
  mandatoryDocs?: string[]

  /**
   * List of document file that will not be loaded initially but are mentioned in the initial thread system instruction.
   *
   * Should a file be relevant on a topic, the LLM is expected to read it when needed.
   */
  optionalDocs?: DocumentationFile[]

  /**
   * Custom scripts exposed as tools to the LLM
   */
  scripts?: Scripts

  /**
   * Experimental section to expose several assistants, thus made available for "spontaneous" cross-assistant calls
   */
  assistants?: AssistantDescription[]

  /**
   * Agent definitions for the project.
   * Can be a single agent or an array of agents.
   * These are loaded first, before any agents defined in ~/.coday/[project]/agents/
   */
  agents?: AgentDefinition[]

  /**
   * Custom prompts exposed as tools to the LLM
   */
  prompts?: {
    [key: string]: PromptChain
  }
}

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

type DocumentationFile = {
  /**
   * Project path to the document
   */
  path: string

  /**
   * Description of the content and added value of the file.
   * Can be a synthetic summary (to generate with AI first)...
   */
  description: string
}

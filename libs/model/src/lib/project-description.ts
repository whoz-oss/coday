import { Scripts } from './scripts'
import { AgentDefinition } from './agent-definition'
import { WithDocs } from './with-docs'
import { AiProviderConfig } from './ai-provider-config'
import { PromptChain } from './prompt-chain'
import { McpConfig } from './mcp-server-config'

export interface FactoryProjectConfig {
  /** Transitional Express adapter; disabled unless explicitly true. */
  enabled?: boolean
  /** Trusted Factory dashboard origin, for example http://127.0.0.1:3141. */
  baseUrl?: string
  /** Trusted AgentOS namespace UUID used by Factory storage and SSE. */
  namespaceId?: string
  /** Trusted runtime registry key. Defaults to coday-express-transitional. Never model-authored. */
  runtimeId?: string
}

export interface ProjectDescription extends WithDocs {
  ai?: AiProviderConfig[]

  /**
   * Transitional Express-to-Factory adapter configuration.
   * The durable implementation remains the AgentOS FactoryPublishProjectionTool.
   */
  factory?: FactoryProjectConfig

  /**
   * MCP (Model Context Protocol) server configurations
   * These serve as global defaults and can be overridden by project and user level configurations
   */
  mcp?: McpConfig

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
   * Custom scripts exposed as tools to the LLM
   */
  scripts?: Scripts

  /**
   * Agent definitions for the project.
   * Can be a single agent or an array of agents.
   * These are loaded first, before any agents defined in ~/.coday/[project]/agents/
   */
  agents?: AgentDefinition[]

  /**
   * Additional folders where agent definitions can be found.
   * Paths must be relative to the project root.
   * For agent definitions outside of the project, use the --agentFolders="/absolute/path" command line option
   */
  agentFolders?: string[]

  /**
   * Custom prompts exposed as tools to the LLM
   */
  prompts?: {
    [key: string]: PromptChain
  }
}

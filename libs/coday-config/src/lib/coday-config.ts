import { AiProviderConfig } from '@coday/model/ai-provider-config'
import { McpConfig } from '@coday/model/mcp-server-config'
import { IntegrationConfig } from '@coday/model/integration-config'
import { AgentDefinition } from '@coday/model/agent-definition'
import { Scripts } from '@coday/model/scripts'
import { PromptChain } from '@coday/model/prompt-chain'

/**
 * Integration configurations mapped by integration name
 */
export type IntegrationLocalConfig = {
  [key: string]: IntegrationConfig
}

/**
 * Unified configuration structure for Coday.
 * Used at all levels: user global, coday.yaml, project local, user project.
 *
 * The merging order (lowest to highest priority):
 * 1. User global (~/.coday/users/{username}/user.yaml)
 * 2. Coday.yaml (versioned in project root)
 * 3. Project local (~/.coday/projects/{name}/project.yaml)
 * 4. User project (~/.coday/users/{username}/user.yaml > projects[name])
 *
 * This allows:
 * - Users to define their default preferences (API keys, favorite tools)
 * - Projects to define required configurations (integrations, MCP servers)
 * - Local overrides for non-versioned settings (secrets, local paths)
 * - User-specific customizations per project (preferred agent, custom context)
 */
export interface CodayConfig {
  /**
   * Configuration version for migration purposes
   */
  version: number

  /**
   * Context/explanation that applies at this configuration level.
   *
   * Meaning depends on where this config is defined:
   * - User global: Bio/description of the user (applies to all projects)
   * - Coday.yaml: Description of the project (what the project is about)
   * - Project local: Additional local context (not versioned)
   * - User project: User-specific guidelines for this project
   *
   * All contexts are merged and sent as system instructions to agents.
   *
   * Recommendations:
   * - Write in markdown format
   * - Be concise but comprehensive
   * - Include rules and expectations relevant to the level
   */
  context?: string

  /**
   * AI provider configurations.
   *
   * Common pattern: Define providers in coday.yaml without API keys,
   * then users add their keys at user level.
   *
   * Example:
   * ```yaml
   * ai:
   *   - name: openai
   *     apiKey: sk-xxx  # Only at user level
   *     models:
   *       - name: gpt-4o
   *         alias: BIG
   * ```
   */
  ai?: AiProviderConfig[]

  /**
   * MCP (Model Context Protocol) server configurations.
   * Tools available to agents via MCP protocol.
   *
   * MCP servers can be defined at any level:
   * - User global: Personal tools available across all projects
   * - Coday.yaml: Project-required MCP tools
   * - Project local: Local-only tools (custom scripts, local servers)
   * - User project: User-specific tools for this project
   */
  mcp?: McpConfig

  /**
   * Integration configurations (Slack, GitHub, etc.)
   *
   * Typically defined at project level with configuration,
   * and secrets (tokens, API keys) added at user level.
   */
  integrations?: IntegrationLocalConfig

  /**
   * Default agent to use when none specified.
   * Only meaningful at project and user-project levels.
   *
   * The agent must be defined in the project's agents/ folder
   * or in Coday's built-in agents.
   */
  defaultAgent?: string

  /**
   * User-specific project configurations (only in user global config).
   * Maps project name to project-specific user config.
   *
   * This allows users to customize settings per project:
   * - Preferred agent for the project
   * - Project-specific context/guidelines
   * - Custom AI provider settings for the project
   * - Additional MCP servers for the project
   *
   * Example:
   * ```yaml
   * projects:
   *   my-project:
   *     context: "For this project, focus on TypeScript best practices"
   *     defaultAgent: sway
   *     ai:
   *       - name: openai
   *         model: gpt-4o
   * ```
   */
  projects?: {
    [projectName: string]: CodayConfig
  }

  // ============================================================================
  // DEPRECATED FIELDS
  // These fields are kept for backward compatibility but should not be used
  // in new configurations. They will be removed in a future version.
  // ============================================================================

  /**
   * @deprecated Agents should be defined in agents/ folder, not in config files.
   * This field will be removed in a future version.
   *
   * Migration: Move agent definitions to:
   * - {project-root}/agents/ for project-specific agents
   * - ~/.coday/agents/ for user-global agents
   */
  agents?: AgentDefinition[]

  /**
   * @deprecated Agent folders should be configured via CLI --agentFolders flag,
   * not in config files. This field will be removed in a future version.
   */
  agentFolders?: string[]

  /**
   * @deprecated Scripts should be defined separately, not in config.
   * This field will be removed in a future version.
   *
   * Consider using MCP servers for custom tools instead.
   */
  scripts?: Scripts

  /**
   * @deprecated Prompts should be defined separately, not in config.
   * This field will be removed in a future version.
   *
   * Consider using prompt chains or agent-specific prompts instead.
   */
  prompts?: { [key: string]: PromptChain }

  /**
   * @deprecated Use 'context' field instead.
   * This field is mapped to 'context' during loading for backward compatibility.
   */
  description?: string

  /**
   * @deprecated Use 'context' field instead.
   * This field is mapped to 'context' during loading for backward compatibility.
   */
  bio?: string
}

/**
 * Default empty configuration.
 * Used when creating a new configuration file or when handling null configurations.
 */
export const DEFAULT_CODAY_CONFIG: CodayConfig = {
  version: 1,
}

/**
 * Normalize a config by mapping deprecated fields to their modern equivalents.
 * This ensures backward compatibility when loading old configuration files.
 *
 * @param config Configuration to normalize
 * @returns Normalized configuration with deprecated fields mapped
 */
export function normalizeCodayConfig(config: CodayConfig): CodayConfig {
  const normalized = { ...config }

  // Map deprecated 'description' to 'context'
  if (config.description && !config.context) {
    normalized.context = config.description
  }

  // Map deprecated 'bio' to 'context'
  if (config.bio && !config.context) {
    normalized.context = config.bio
  }

  // If both 'description' and 'bio' exist, concatenate them
  if (config.description && config.bio && !config.context) {
    normalized.context = config.description + '\n\n---\n\n' + config.bio
  }

  return normalized
}

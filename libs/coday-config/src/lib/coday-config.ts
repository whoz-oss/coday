import { AiProviderConfig } from '@coday/model/ai-provider-config'
import { McpConfig } from '@coday/model/mcp-server-config'
import { IntegrationConfig } from '@coday/model/integration-config'

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
}

/**
 * User-level configuration extending CodayConfig.
 * Stored in ~/.coday/users/{username}/user.yaml
 *
 * Adds the ability to define per-project overrides.
 */
export interface UserConfig extends CodayConfig {
  /**
   * User-specific project configurations.
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
}

/**
 * Default empty configuration.
 * Used when creating a new configuration file or when handling null configurations.
 */
export const DEFAULT_CODAY_CONFIG: CodayConfig = {
  version: 1,
}

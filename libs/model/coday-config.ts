import { AiProviderConfig } from './ai-provider-config'
import { McpConfig } from './mcp-server-config'
import { IntegrationConfig } from './integration-config'
import { AgentDefinition } from './agent-definition'
import { Scripts } from './scripts'
import { PromptChain } from './prompt-chain'

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
 * Merge multiple CodayConfig objects according to the stacking order.
 * Later configs override earlier configs for simple properties.
 * Arrays (ai, mcp.servers, etc.) are merged intelligently.
 *
 * @param configs Array of configs to merge, in order of increasing priority
 * @returns Merged configuration
 */
export function mergeCodayConfigs(...configs: (CodayConfig | undefined | null)[]): CodayConfig {
  const result: CodayConfig = { version: 1 }

  for (const config of configs) {
    if (!config) continue

    // Merge context (concatenate with separator)
    if (config.context) {
      if (result.context) {
        result.context += '\n\n---\n\n' + config.context
      } else {
        result.context = config.context
      }
    }

    // Merge AI providers (by name)
    if (config.ai) {
      if (!result.ai) result.ai = []
      for (const provider of config.ai) {
        const existing = result.ai.find((p) => p.name === provider.name)
        if (existing) {
          // Merge provider properties
          Object.assign(existing, provider)
          // Merge models if present
          if (provider.models) {
            if (!existing.models) existing.models = []
            for (const model of provider.models) {
              const existingModel = existing.models.find((m) => m.name === model.name)
              if (existingModel) {
                Object.assign(existingModel, model)
              } else {
                existing.models.push(model)
              }
            }
          }
        } else {
          result.ai.push({ ...provider })
        }
      }
    }

    // Merge MCP config
    if (config.mcp) {
      if (!result.mcp) result.mcp = { servers: [] }
      if (config.mcp.servers) {
        if (!result.mcp.servers) result.mcp.servers = []
        for (const server of config.mcp.servers) {
          const existing = result.mcp.servers.find((s) => s.id === server.id)
          if (existing) {
            Object.assign(existing, server)
          } else {
            result.mcp.servers.push({ ...server })
          }
        }
      }
    }

    // Merge integrations (by key)
    if (config.integrations) {
      if (!result.integrations) result.integrations = {}
      for (const [key, integration] of Object.entries(config.integrations)) {
        if (result.integrations[key]) {
          Object.assign(result.integrations[key], integration)
        } else {
          result.integrations[key] = { ...integration }
        }
      }
    }

    // Simple overrides
    if (config.defaultAgent !== undefined) result.defaultAgent = config.defaultAgent

    // Deprecated fields (simple override, no merge)
    if (config.agents !== undefined) result.agents = config.agents
    if (config.agentFolders !== undefined) result.agentFolders = config.agentFolders
    if (config.scripts !== undefined) result.scripts = config.scripts
    if (config.prompts !== undefined) result.prompts = config.prompts
  }

  return result
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

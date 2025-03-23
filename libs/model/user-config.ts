import { IntegrationLocalConfig } from './project-local-config'
import { AiProviderLocalConfig } from './ai-providers'

export type AiProviderConfig = {
  apiKey?: string
}

/**
 * Configuration scoped to the user, for a given project name
 *
 * Integrations defined here override the project ones
 */
export type UserProjectConfig = {
  integration: IntegrationLocalConfig
  /**
   * The preferred agent to use by default for this project
   */
  defaultAgent?: string
}

/**
 * User-level configuration, stored in ~/.coday/user.yml
 * Contains settings that are specific to the user and
 * should not be shared across users or stored in project
 * configuration files.
 *
 * Currently handles:
 * - AI provider configurations with API keys
 * - MCP server configurations
 * - (future) User preferences
 * - (future) Default settings
 */
export interface UserConfig {
  aiProviders: AiProviderLocalConfig
  /**
   * Table of user-scoped project configuration
   */
  projects?: {
    [key: string]: UserProjectConfig
  }
  /**
   * MCP (Model Context Protocol) server configurations
   */
  mcp?: {
    servers?: McpServerConfig[]
  }
}

/**
 * Configuration for an MCP server connection
 */
export interface McpServerConfig {
  /** Unique identifier for this MCP server */
  id: string
  /** Human readable name for this MCP server */
  name: string
  /** MCP server URL for HTTP transport (use either url or command, not both) */
  url?: string
  /** Command to execute for stdio transport (use either url or command, not both) */
  command?: string
  /** Optional arguments for the command when using stdio transport */
  args?: string[]
  /** Optional authentication token */
  authToken?: string
  /** Whether the server connection is enabled */
  enabled: boolean
  // Note: OAuth authentication support might be added in the future
}

/**
 * Initial configuration structure.
 * Used when creating a new user config file
 * or when handling null configurations.
 */
export const DEFAULT_USER_CONFIG: UserConfig = {
  aiProviders: {},
}

import {IntegrationLocalConfig} from './project-local-config'
import {McpConfig} from './mcp-server-config'
import {AiProviderConfig} from './ai-provider-config'

export type AiProviderConfigOld = {
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
  /**
   * MCP (Model Context Protocol) server configurations overrides, for this user on this project
   */
  mcp?: McpConfig
  /**
   * Project-specific bio (optional)
   */
  bio?: string
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
  version: number

  ai?: AiProviderConfig[]
  /**
   * User bio (optional)
   */
  bio?: string // Add this field
  /**
   * Table of user-scoped project configuration
   */
  projects?: {
    [key: string]: UserProjectConfig
  }
}

/**
 * Initial configuration structure.
 * Used when creating a new user config file
 * or when handling null configurations.
 */
export const DEFAULT_USER_CONFIG: UserConfig = {
  version: 1,
  // bio is undefined by default
}

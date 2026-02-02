import { IntegrationConfig } from './integration-config'
import { AgentDefinition } from './agent-definition'
import { McpConfig } from './mcp-server-config'
import { AiProviderConfig } from './ai-provider-config'

export type IntegrationLocalConfig = {
  [key: string]: IntegrationConfig
}

export type ProjectLocalConfig = {
  version: number
  path: string
  ai?: AiProviderConfig[]
  integration: IntegrationLocalConfig
  storage?: StorageConfig
  agents?: AgentDefinition[]
  /**
   * MCP (Model Context Protocol) server configurations
   * These can be overridden by user-level configurations
   */
  mcp?: McpConfig
  /**
   * Indicates if this project was auto-generated (volatile)
   * Volatile projects are created automatically when Coday is started in a directory
   * without an existing project configuration
   */
  volatile?: boolean
  /**
   * Timestamp of project creation (for volatile projects)
   */
  createdAt?: number
}

export type StorageConfig =
  | {
      type: 'file'
    }
  | {
      type: 'mongo'
      uri: string
      database: string
    }
  | {
      type: 'postgres'
      host: string
      port: number
      database: string
      user: string
      password: string
    }

export type SelectedProject = {
  name: string
  configPath: string
  config: ProjectLocalConfig
} | null

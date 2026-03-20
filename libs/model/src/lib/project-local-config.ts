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
   * Preview server configuration — optional.
   * When set, enables the Start/Stop preview panel in the UI.
   */
  preview?: PreviewConfig
  /**
   * Indicates if this project was auto-generated (volatile)
   */
  volatile?: boolean
  /**
   * Timestamp of project creation (for volatile projects)
   */
  createdAt?: number
}

export type PreviewConfig = {
  /**
   * Shell command to start the preview server, e.g. "pnpm web:dev:tmux".
   * The command is responsible for port selection and binding.
   * The preview manager wraps it in a tmux session and reads the URL
   * from the server's own log output.
   */
  command: string
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

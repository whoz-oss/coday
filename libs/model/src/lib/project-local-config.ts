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

/**
 * A single preview entry: a named command that can be started in a tmux session.
 */
export type PreviewEntry = {
  /** Display name for this preview */
  name: string
  /**
   * Shell command to run for this preview.
   * Must be a foreground command (no trailing &, no nohup).
   * The preview manager wraps it in a tmux session automatically.
   */
  command: string
  /** Optional external URL for this preview. */
  url?: string
}

/**
 * Preview configuration: an array of named preview entries.
 * Only one preview runs at a time per project.
 */
export type PreviewConfig = PreviewEntry[]

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

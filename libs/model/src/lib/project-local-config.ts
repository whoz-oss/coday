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
   * Volatile projects are created automatically when Coday is started in a directory
   * without an existing project configuration
   */
  volatile?: boolean
  /**
   * Timestamp of project creation (for volatile projects)
   */
  createdAt?: number
}

export type PreviewConfig = {
  /** Shell command to start the preview server, e.g. "pnpm web:dev:tmux" */
  command: string
  /**
   * Host shown in the clickable preview URL, e.g. "172.16.4.4".
   * When omitted (or set to "0.0.0.0") the server auto-detects the first
   * non-loopback IPv4 address of the machine, which is useful when accessing
   * the dev server from a remote browser.
   * Note: this does NOT control the bind address of the preview command itself;
   * that is the responsibility of the command (e.g. web-dev-tmux.sh binds to all interfaces).
   */
  host?: string
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

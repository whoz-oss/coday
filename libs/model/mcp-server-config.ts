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

  /** Optional environment variables for the command when using stdio transport */
  env?: Record<string, string>

  /** Optional names of the variables to lookup in the process' env */
  envVarNames?: string[]

  /** Optional working directory for the command when using stdio transport */
  cwd?: string

  /** Optional authentication token */
  authToken?: string

  /** Whether the server connection is enabled */
  enabled?: boolean

  /** Optional list of allowed tools (if not specified, all tools are allowed) */
  allowedTools?: string[]

  /** Enable MCP Inspector debug mode for this server */
  debug?: boolean

  // Note: OAuth authentication support might be added in the future
}

export const McpServerConfigArgs = [
  'id',
  'name',
  'url',
  'command',
  'args',
  'env',
  'cwd',
  'authToken',
  'enabled',
  'allowedTools',
  'debug',
]

/**
 * MCP configuration structure that can be part of user or project config
 */
export interface McpConfig {
  servers?: McpServerConfig[]
}

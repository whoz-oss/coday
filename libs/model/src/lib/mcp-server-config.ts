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
  whiteListedHostEnvVarNames?: string[]

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

  /**
   * Prevent sharing this MCP instance across threads/users.
   * Set to true for MCP servers that:
   * - Maintain internal state specific to a conversation
   * - Use user-specific credentials or authentication
   * - Cannot safely handle concurrent requests from different contexts
   * Default: false (sharing enabled for performance)
   */
  noShare?: boolean

  /**
   * Enable OAuth 2.1 authentication for remote MCP servers.
   * When true, the MCP client will use the full OAuth 2.1 flow with
   * dynamic authorization server discovery (RFC9728 Protected Resource Metadata).
   * Tokens are stored per-user in user config.
   * Only relevant when `url` is set.
   */
  oauth2?: boolean
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
  'noShare',
]

/**
 * MCP configuration structure that can be part of user or project config
 */
export interface McpConfig {
  servers?: McpServerConfig[]
}

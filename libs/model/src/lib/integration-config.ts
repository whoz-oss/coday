/**
 * OAuth2 tokens stored securely for an integration
 */
export type OAuth2Tokens = {
  access_token: string
  refresh_token?: string
  expires_at: number // timestamp ms
  token_type?: string
  scope?: string
}

/**
 * OAuth2 configuration for an integration
 */
export type OAuth2Config = {
  client_id: string
  client_secret: string
  redirect_uri: string
  /**
   * Direct authorization endpoint URL.
   * Required unless issuer or discovery_url is provided.
   */
  authorization_endpoint?: string
  /**
   * Direct token endpoint URL.
   * Required unless issuer or discovery_url is provided.
   */
  token_endpoint?: string
  /**
   * OAuth2 / OIDC issuer URL (e.g. "https://accounts.google.com").
   * When provided, endpoints are resolved automatically via RFC 8414 / OIDC discovery.
   * Takes precedence over discovery_url; ignored when authorization_endpoint + token_endpoint are set.
   */
  issuer?: string
  /**
   * Explicit discovery document URL.
   * Used when the discovery URL does not follow the standard .well-known convention.
   * Ignored when authorization_endpoint + token_endpoint are set.
   */
  discovery_url?: string
  // Single string ("openid email") or array joined with spaces at runtime
  scope?: string | string[]
  tokens?: OAuth2Tokens // Stored tokens (user-level only)
  // Provider-specific data
  account_id?: string
  account_name?: string
  account_href?: string // Base URL for API calls
}

/**
 * A single parameter for an HTTP endpoint
 */
export type HttpParamConfig = {
  name: string
  type: 'string' | 'number' | 'boolean'
  description: string
  required?: boolean
  // Where the param is injected (default: query)
  location?: 'path' | 'query' | 'body'
}

/**
 * A single endpoint exposed as an AI tool
 */
export type HttpEndpointConfig = {
  name: string // tool name suffix: ${integrationName}__${name}
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  path: string // e.g. '/calendars/{calendarId}/events'
  description: string
  params?: HttpParamConfig[]
  // Response filtering: dot-notation paths, supports wildcard *
  // keepPaths: only these paths are kept (e.g. ["items.*.summary", "items.*.start"])
  // ignorePaths: these paths are removed (e.g. ["items.*.htmlLink", "etag"])
  // keepPaths takes precedence over ignorePaths if both are set
  keepPaths?: string[]
  ignorePaths?: string[]
  // Response format sent to the LLM: 'json' (default) or 'yaml' (more compact)
  responseFormat?: 'json' | 'yaml'
}

/**
 * HTTP-specific configuration block
 */
export type HttpConfig = {
  // Base URL for all HTTP requests of this integration
  // e.g. 'https://www.googleapis.com/calendar/v3'
  baseUrl: string
  endpoints?: HttpEndpointConfig[]
}

/**
 * Base integration configuration
 */
export type IntegrationConfig = {
  // Type of integration (e.g., 'jira', 'gitlab', 'confluence', 'http')
  // Allows multiple instances of the same integration type with different configs
  type?: string
  // Legacy fields (still used by simple integrations)
  apiUrl?: string
  username?: string
  apiKey?: string

  // OAuth2 configuration
  oauth2?: OAuth2Config

  // HTTP integration configuration
  http?: HttpConfig

  // Extensible for integration-specific data
  [key: string]: any
}

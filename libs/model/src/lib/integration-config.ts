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
  authorization_endpoint?: string
  token_endpoint?: string
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

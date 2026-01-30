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
  scope?: string
  tokens?: OAuth2Tokens // Stored tokens (user-level only)
  // Provider-specific data
  account_id?: string
  account_name?: string
  account_href?: string // Base URL for API calls
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

  // OAuth2 configuration (new)
  oauth2?: OAuth2Config

  // Extensible for integration-specific data
  [key: string]: any
}

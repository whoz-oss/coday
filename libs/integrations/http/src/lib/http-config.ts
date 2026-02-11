/**
 * HTTP integration configuration types
 * Defines the schema for declarative HTTP integrations
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export type ParamLocation = 'query' | 'path' | 'body' | 'header'

export type ParamType = 'string' | 'number' | 'boolean' | 'array' | 'object'

/**
 * Parameter definition for an HTTP endpoint
 */
export interface HttpEndpointParam {
  name: string
  type: ParamType
  required?: boolean
  location?: ParamLocation // Default: 'body' for POST/PUT/PATCH, 'query' for GET/DELETE
  description?: string
  default?: any
  // For arrays
  items?: {
    type: ParamType
  }
}

/**
 * HTTP endpoint definition
 */
export interface HttpEndpoint {
  name: string
  method: HttpMethod
  path: string
  params?: HttpEndpointParam[]
  description?: string
  // Optional: custom headers for this specific endpoint
  headers?: Record<string, string>
  // Optional: response transformation (JSONPath or similar)
  responseTransform?: string
}

/**
 * Authentication types supported
 */
export type HttpAuthType = 'none' | 'credentials' | 'oauth2' | 'bearer'

/**
 * Credentials-based authentication
 */
export interface HttpCredentialsAuth {
  type: 'credentials'
  // Header name for username (e.g., 'X-Username')
  usernameHeader?: string
  // Header name for API key (e.g., 'X-API-Key', 'Authorization')
  apiKeyHeader?: string
  // If using Authorization header with scheme
  scheme?: 'Basic' | 'Bearer' | 'ApiKey'
}

/**
 * Bearer token authentication (simple)
 */
export interface HttpBearerAuth {
  type: 'bearer'
  // Header name (default: 'Authorization')
  header?: string
  // Scheme prefix (default: 'Bearer')
  scheme?: string
}

/**
 * OAuth2 authentication configuration
 */
export interface HttpOAuth2Auth {
  type: 'oauth2'
  provider?: 'standard' | 'google-sso' // Default: 'standard'
  // OAuth2 endpoints (required for standard OAuth2)
  authorizationEndpoint?: string
  tokenEndpoint?: string
  // Scopes
  scope?: string
  // Where to put the token (default: header)
  tokenLocation?: 'header' | 'query'
  // Header/query parameter name (default: 'Authorization' for header, 'access_token' for query)
  tokenParam?: string
  // Scheme for header (default: 'Bearer')
  scheme?: string
}

/**
 * No authentication
 */
export interface HttpNoAuth {
  type: 'none'
}

export type HttpAuth = HttpNoAuth | HttpCredentialsAuth | HttpBearerAuth | HttpOAuth2Auth

/**
 * Complete HTTP integration configuration
 */
export interface HttpIntegrationConfig {
  type: 'http'
  baseUrl: string
  auth: HttpAuth
  // Common headers for all requests
  headers?: Record<string, string>
  // Endpoint definitions
  endpoints: HttpEndpoint[]
  // Optional: timeout in milliseconds
  timeout?: number
}

/**
 * Type guard functions
 */
export function isCredentialsAuth(auth: HttpAuth): auth is HttpCredentialsAuth {
  return auth.type === 'credentials'
}

export function isBearerAuth(auth: HttpAuth): auth is HttpBearerAuth {
  return auth.type === 'bearer'
}

export function isOAuth2Auth(auth: HttpAuth): auth is HttpOAuth2Auth {
  return auth.type === 'oauth2'
}

export function isNoAuth(auth: HttpAuth): auth is HttpNoAuth {
  return auth.type === 'none'
}

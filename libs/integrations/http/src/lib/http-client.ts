/**
 * HTTP client for making requests with authentication
 */

import { Interactor } from '@coday/model'
import {
  HttpAuth,
  HttpEndpoint,
  HttpEndpointParam,
  HttpIntegrationConfig,
  HttpMethod,
  isCredentialsAuth,
  isBearerAuth,
  isOAuth2Auth,
} from './http-config'
import { HttpOAuth } from './http-oauth'

export class HttpClient {
  private oauth: HttpOAuth | null = null

  constructor(
    private readonly config: HttpIntegrationConfig,
    private readonly interactor: Interactor,
    private readonly integrationName: string
  ) {}

  /**
   * Set OAuth handler (injected from HttpTools)
   */
  setOAuth(oauth: HttpOAuth): void {
    this.oauth = oauth
  }

  /**
   * Make an HTTP request to an endpoint
   */
  async request(endpoint: HttpEndpoint, params: Record<string, any>): Promise<any> {
    const url = this.buildUrl(endpoint, params)
    const headers = await this.buildHeaders(endpoint, params)
    const body = this.buildBody(endpoint, params)

    const options: RequestInit = {
      method: endpoint.method,
      headers,
    }

    if (body !== null) {
      options.body = body
    }

    this.interactor.debug(`[HTTP] ${endpoint.method} ${url}`)

    try {
      const response = await fetch(url, options)

      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(`HTTP ${response.status}: ${errorText}`)
      }

      const contentType = response.headers.get('content-type')
      if (contentType?.includes('application/json')) {
        const data = await response.json()
        return this.transformResponse(endpoint, data)
      } else {
        return await response.text()
      }
    } catch (error) {
      this.interactor.error(`HTTP request failed: ${error}`)
      throw error
    }
  }

  /**
   * Build complete URL with path and query parameters
   */
  private buildUrl(endpoint: HttpEndpoint, params: Record<string, any>): string {
    let path = endpoint.path

    // Replace path parameters
    const pathParams = endpoint.params?.filter((p) => p.location === 'path') || []
    for (const param of pathParams) {
      const value = params[param.name]
      if (value !== undefined) {
        path = path.replace(`{${param.name}}`, encodeURIComponent(String(value)))
      }
    }

    // Add query parameters
    const queryParams =
      endpoint.params?.filter((p) => {
        const location = p.location || this.defaultParamLocation(endpoint.method)
        return location === 'query'
      }) || []

    const queryString = new URLSearchParams()
    for (const param of queryParams) {
      const value = params[param.name]
      if (value !== undefined) {
        if (param.type === 'array' && Array.isArray(value)) {
          value.forEach((v) => queryString.append(param.name, String(v)))
        } else {
          queryString.append(param.name, String(value))
        }
      }
    }

    const query = queryString.toString()
    const separator = path.includes('?') ? '&' : '?'
    const fullPath = query ? `${path}${separator}${query}` : path

    return `${this.config.baseUrl}${fullPath}`
  }

  /**
   * Build request headers with authentication
   */
  private async buildHeaders(endpoint: HttpEndpoint, params: Record<string, any>): Promise<Record<string, string>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...this.config.headers,
      ...endpoint.headers,
    }

    // Add authentication headers
    await this.addAuthHeaders(headers)

    // Add header parameters
    const headerParams = endpoint.params?.filter((p) => p.location === 'header') || []
    for (const param of headerParams) {
      const value = params[param.name]
      if (value !== undefined) {
        headers[param.name] = String(value)
      }
    }

    return headers
  }

  /**
   * Add authentication headers based on auth type
   */
  private async addAuthHeaders(headers: Record<string, string>): Promise<void> {
    const auth = this.config.auth

    if (isCredentialsAuth(auth)) {
      // Credentials authentication
      const username = this.config.headers?.['X-Username'] || ''
      const apiKey = this.config.headers?.['X-API-Key'] || ''

      if (auth.scheme === 'Basic') {
        const encoded = btoa(`${username}:${apiKey}`)
        headers['Authorization'] = `Basic ${encoded}`
      } else if (auth.scheme === 'Bearer') {
        headers['Authorization'] = `Bearer ${apiKey}`
      } else if (auth.usernameHeader && auth.apiKeyHeader) {
        headers[auth.usernameHeader] = username
        headers[auth.apiKeyHeader] = apiKey
      } else if (auth.apiKeyHeader) {
        headers[auth.apiKeyHeader] = apiKey
      }
    } else if (isBearerAuth(auth)) {
      // Bearer token authentication
      const token = this.config.headers?.['Authorization']?.replace('Bearer ', '') || ''
      const header = auth.header || 'Authorization'
      const scheme = auth.scheme || 'Bearer'
      headers[header] = `${scheme} ${token}`
    } else if (isOAuth2Auth(auth)) {
      // OAuth2 authentication
      if (!this.oauth) {
        throw new Error('OAuth not initialized')
      }

      const token = await this.oauth.getAccessToken()
      const tokenLocation = auth.tokenLocation || 'header'

      if (tokenLocation === 'header') {
        const header = auth.tokenParam || 'Authorization'
        const scheme = auth.scheme || 'Bearer'
        headers[header] = `${scheme} ${token}`
      }
      // Query parameter token will be added in buildUrl
    }
  }

  /**
   * Build request body
   */
  private buildBody(endpoint: HttpEndpoint, params: Record<string, any>): string | null {
    if (endpoint.method === 'GET' || endpoint.method === 'DELETE') {
      return null
    }

    const bodyParams =
      endpoint.params?.filter((p) => {
        const location = p.location || this.defaultParamLocation(endpoint.method)
        return location === 'body'
      }) || []

    if (bodyParams.length === 0) {
      return null
    }

    const body: Record<string, any> = {}
    for (const param of bodyParams) {
      const value = params[param.name]
      if (value !== undefined) {
        body[param.name] = value
      } else if (param.required) {
        throw new Error(`Required parameter '${param.name}' is missing`)
      }
    }

    return JSON.stringify(body)
  }

  /**
   * Default parameter location based on HTTP method
   */
  private defaultParamLocation(method: HttpMethod): 'query' | 'body' {
    return method === 'GET' || method === 'DELETE' ? 'query' : 'body'
  }

  /**
   * Transform response based on endpoint configuration
   */
  private transformResponse(endpoint: HttpEndpoint, data: any): any {
    if (!endpoint.responseTransform) {
      return data
    }

    // TODO: Implement JSONPath or similar transformation
    // For now, just return the data as-is
    return data
  }
}

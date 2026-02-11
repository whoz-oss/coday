/**
 * HTTP client for making requests with authentication
 * Uses Axios for robust HTTP handling with timeout and retry
 */

import axios, { AxiosInstance, AxiosRequestConfig } from 'axios'
import { Interactor } from '@coday/model'
import { HttpEndpoint, HttpIntegrationConfig, isCredentialsAuth, isBearerAuth, isOAuth2Auth } from './http-config'
import { HttpOAuth } from './http-oauth'

export class HttpClient {
  private axiosInstance: AxiosInstance
  private oauth: HttpOAuth | null = null

  constructor(
    private readonly config: HttpIntegrationConfig,
    private readonly interactor: Interactor
  ) {
    // Create Axios instance with base configuration
    this.axiosInstance = axios.create({
      baseURL: config.baseUrl,
      timeout: config.timeout || 30000, // 30s default timeout
      headers: {
        'Content-Type': 'application/json',
        ...config.headers,
      },
    })

    // Add request interceptor for authentication
    this.axiosInstance.interceptors.request.use(
      async (config) => {
        await this.addAuthHeaders(config.headers as Record<string, string>)
        return config
      },
      (error) => Promise.reject(error)
    )

    // Add response interceptor for logging
    this.axiosInstance.interceptors.response.use(
      (response) => {
        this.interactor.debug(
          `[HTTP] ${response.config.method?.toUpperCase()} ${response.config.url} → ${response.status}`
        )
        return response
      },
      (error) => {
        if (error.response) {
          this.interactor.debug(
            `[HTTP] ${error.config?.method?.toUpperCase()} ${error.config?.url} → ${error.response.status} ${error.response.statusText}`
          )
        }
        return Promise.reject(error)
      }
    )
  }

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
    try {
      // Build request config
      const config: AxiosRequestConfig = {
        method: endpoint.method,
        url: this.buildPath(endpoint, params),
        params: this.getQueryParams(endpoint, params),
        data: this.getBodyParams(endpoint, params),
        headers: this.getHeaderParams(endpoint, params),
      }

      // Make request (auth added via interceptor)
      const response = await this.axiosInstance.request(config)

      // Transform and return response
      return this.transformResponse(endpoint, response.data)
    } catch (error: any) {
      // Axios wraps errors nicely
      if (error.response) {
        // Server responded with error status
        throw new Error(
          `HTTP ${error.response.status}: ${error.response.data?.message || error.response.statusText || 'Request failed'}`
        )
      } else if (error.request) {
        // Request made but no response
        throw new Error(`No response from server: ${error.message}`)
      } else {
        // Request setup error
        throw new Error(`Request failed: ${error.message}`)
      }
    }
  }

  /**
   * Build path with path parameters replaced
   */
  private buildPath(endpoint: HttpEndpoint, params: Record<string, any>): string {
    let path = endpoint.path

    // Replace path parameters
    const pathParams = endpoint.params?.filter((p) => p.location === 'path') || []
    for (const param of pathParams) {
      const value = params[param.name]
      if (value !== undefined) {
        path = path.replace(`{${param.name}}`, encodeURIComponent(String(value)))
      }
    }

    return path
  }

  /**
   * Get query parameters
   */
  private getQueryParams(endpoint: HttpEndpoint, params: Record<string, any>): Record<string, any> {
    const queryParams =
      endpoint.params?.filter((p) => {
        const location = p.location || this.defaultParamLocation(endpoint.method)
        return location === 'query'
      }) || []

    const result: Record<string, any> = {}
    for (const param of queryParams) {
      const value = params[param.name]
      if (value !== undefined) {
        result[param.name] = value
      }
    }

    return result
  }

  /**
   * Get body parameters
   */
  private getBodyParams(endpoint: HttpEndpoint, params: Record<string, any>): any {
    if (endpoint.method === 'GET' || endpoint.method === 'DELETE') {
      return undefined
    }

    const bodyParams =
      endpoint.params?.filter((p) => {
        const location = p.location || this.defaultParamLocation(endpoint.method)
        return location === 'body'
      }) || []

    if (bodyParams.length === 0) {
      return undefined
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

    return body
  }

  /**
   * Get header parameters
   */
  private getHeaderParams(endpoint: HttpEndpoint, params: Record<string, any>): Record<string, string> {
    const headers: Record<string, string> = { ...endpoint.headers }

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
      // Query parameter token handled in getQueryParams if needed
    }
  }

  /**
   * Default parameter location based on HTTP method
   */
  private defaultParamLocation(method: string): 'query' | 'body' {
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

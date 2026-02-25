/**
 * HTTP integration with OAuth2 support.
 *
 * Generates AI tools dynamically from declarative endpoint configuration.
 * Each endpoint defined in http.endpoints becomes one tool.
 *
 * Configuration expected in coday.yaml:
 *
 *   integration:
 *     MY_CALENDAR:
 *       type: HTTP
 *       http:
 *         baseUrl: "https://www.googleapis.com/calendar/v3"
 *         endpoints:
 *           - name: getEvents
 *             method: GET
 *             path: "/calendars/{calendarId}/events"
 *             description: "List events from a Google Calendar"
 *             responseFormat: yaml        # optional, saves tokens
 *             keepPaths:                  # optional, only these fields returned
 *               - "items.*.summary"
 *               - "items.*.start"
 *               - "items.*.end"
 *             params:
 *               - name: calendarId
 *                 type: string
 *                 description: "Calendar ID, use 'primary' for main calendar"
 *                 required: true
 *                 location: path
 *               - name: timeMin
 *                 type: string
 *                 description: "Lower bound ISO 8601 (e.g. 2024-01-15T00:00:00Z)"
 *                 location: query
 *               - name: maxResults
 *                 type: number
 *                 description: "Max events to return (default 10, max 250)"
 *                 location: query
 *       oauth2:
 *         client_id: "your-google-client-id"
 *         client_secret: "your-google-client-secret"
 *         redirect_uri: "http://localhost:3000/oauth/callback"
 *         authorization_endpoint: "https://accounts.google.com/o/oauth2/v2/auth"
 *         token_endpoint: "https://oauth2.googleapis.com/token"
 *         scope:
 *           - "https://www.googleapis.com/auth/calendar.readonly"
 */
import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  HttpEndpointConfig,
  HttpParamConfig,
  IntegrationConfig,
  Interactor,
  OAuthCallbackEvent,
} from '@coday/model'
import { UserService } from '@coday/service'
import * as yaml from 'yaml'
import { GenericOAuth } from './generic-oauth'

export class HttpTools extends AssistantToolFactory {
  static readonly TYPE = 'HTTP' as const

  private oauth: GenericOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly userService: UserService,
    instanceName: string,
    config?: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (this.oauth) {
      await this.oauth.handleCallback(event)
    }
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const oauth2Config = this.config?.oauth2
    if (!oauth2Config?.client_id || !oauth2Config?.client_secret || !oauth2Config?.redirect_uri) {
      this.interactor.debug(
        `[HTTP:${this.name}] missing oauth2 fields: client_id=${!!oauth2Config?.client_id}, client_secret=${!!oauth2Config?.client_secret}, redirect_uri=${!!oauth2Config?.redirect_uri}`
      )
      return []
    }

    const baseUrl = this.config?.http?.baseUrl
    if (!baseUrl) {
      this.interactor.debug(`[HTTP:${this.name}] missing http.baseUrl`)
      return []
    }

    if (!oauth2Config.authorization_endpoint || !oauth2Config.token_endpoint) {
      this.interactor.debug(
        `[HTTP:${this.name}] missing oauth2 endpoints: authorization_endpoint=${oauth2Config.authorization_endpoint}, token_endpoint=${oauth2Config.token_endpoint}`
      )
      return []
    }

    const endpoints = this.config?.http?.endpoints
    if (!endpoints?.length) {
      this.interactor.debug(`[HTTP:${this.name}] no endpoints configured`)
      return []
    }

    const endpointsSummary = endpoints
      .map(
        (e) =>
          `${e.name}:${e.method} ${e.path}` +
          ` (${e.params?.length ?? 0} params` +
          `${e.keepPaths ? ', keepPaths[' + e.keepPaths.length + ']' : ''}` +
          `${e.ignorePaths ? ', ignorePaths[' + e.ignorePaths.length + ']' : ''}` +
          `${e.responseFormat ? ', ' + e.responseFormat : ''})`
      )
      .join(' | ')
    this.interactor.debug(`[HTTP:${this.name}] baseUrl=${baseUrl} | endpoints: ${endpointsSummary}`)

    this.oauth = new GenericOAuth(
      {
        clientId: oauth2Config.client_id,
        clientSecret: oauth2Config.client_secret,
        redirectUri: oauth2Config.redirect_uri,
        authorizationEndpoint: oauth2Config.authorization_endpoint,
        tokenEndpoint: oauth2Config.token_endpoint,
        scope: oauth2Config.scope,
      },
      this.interactor,
      this.userService,
      context.project.name,
      this.name
    )

    return endpoints.map((endpoint) => this.buildTool(endpoint, baseUrl))
  }

  private buildTool(endpoint: HttpEndpointConfig, baseUrl: string): CodayTool {
    const params = endpoint.params ?? []
    const properties: Record<string, { type: string; description: string }> = {}
    const required: string[] = []

    for (const param of params) {
      properties[param.name] = { type: param.type, description: param.description }
      if (param.required) required.push(param.name)
    }

    const tool: FunctionTool<Record<string, unknown>> = {
      type: 'function',
      function: {
        name: `${this.name}__${endpoint.name}`,
        description: endpoint.description,
        parameters: {
          type: 'object',
          properties,
          ...(required.length ? { required } : {}),
        },
        parse: JSON.parse,
        function: async (args) => this.executeEndpoint(endpoint, baseUrl, args, params),
      },
    }
    return tool
  }

  private async executeEndpoint(
    endpoint: HttpEndpointConfig,
    baseUrl: string,
    args: Record<string, unknown>,
    params: HttpParamConfig[]
  ): Promise<unknown> {
    this.interactor.debug(`[HTTP:${this.name}__${endpoint.name}] args=${JSON.stringify(args)}`)
    this.interactor.debug(
      `[HTTP:${this.name}__${endpoint.name}] oauth=${!!this.oauth}, isAuthenticated=${this.oauth?.isAuthenticated()}`
    )

    const accessToken = await (
      this.oauth!.isAuthenticated()
        ? this.oauth!.getAccessToken()
        : this.oauth!.authenticate().then(() => this.oauth!.getAccessToken())
    ).catch((err: Error) => {
      throw new Error(`Authentication failed: ${err.message}`)
    })
    this.interactor.debug(`[HTTP:${this.name}__${endpoint.name}] got access token (length=${accessToken.length})`)

    // Substitute path params
    let resolvedPath = endpoint.path
    for (const param of params.filter((p) => p.location === 'path')) {
      const value = args[param.name]
      if (value !== undefined) {
        resolvedPath = resolvedPath.replace(`{${param.name}}`, encodeURIComponent(String(value)))
      }
    }

    const url = new URL(`${baseUrl}${resolvedPath}`)

    // Append query params
    for (const param of params.filter((p) => !p.location || p.location === 'query')) {
      const value = args[param.name]
      if (value !== undefined) {
        url.searchParams.set(param.name, String(value))
      }
    }

    // Build body
    const bodyParams = params.filter((p) => p.location === 'body')
    const body =
      bodyParams.length > 0
        ? JSON.stringify(
            Object.fromEntries(bodyParams.filter((p) => args[p.name] !== undefined).map((p) => [p.name, args[p.name]]))
          )
        : undefined

    this.interactor.debug(`[HTTP:${this.name}__${endpoint.name}] ${endpoint.method} ${url.toString()}`)

    const response = await fetch(url.toString(), {
      method: endpoint.method,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      ...(body ? { body } : {}),
    })

    if (!response.ok) {
      const errorBody = await response.text()
      throw new Error(`HTTP ${response.status} from ${endpoint.method} ${resolvedPath}: ${errorBody}`)
    }

    const data = await response.json()
    const rawSize = JSON.stringify(data).length
    const filtered = filterResponse(data, endpoint.keepPaths, endpoint.ignorePaths)
    const filteredSize = JSON.stringify(filtered).length

    if (endpoint.keepPaths?.length || endpoint.ignorePaths?.length) {
      this.interactor.debug(
        `[HTTP:${this.name}__${endpoint.name}] response filtered ${rawSize} → ${filteredSize} chars`
      )
    } else {
      this.interactor.debug(`[HTTP:${this.name}__${endpoint.name}] response ${rawSize} chars (no filter)`)
    }

    if (endpoint.responseFormat === 'yaml') {
      return yaml.stringify(filtered)
    }
    return filtered
  }
}

/**
 * Filters a response object using dot-notation paths with wildcard * support.
 * keepPaths takes precedence: if set, only matching paths are kept.
 * Otherwise ignorePaths removes matching paths.
 */
export function filterResponse(data: unknown, keepPaths?: string[], ignorePaths?: string[]): unknown {
  if (!keepPaths?.length && !ignorePaths?.length) return data
  if (keepPaths?.length) return applyKeep(data, keepPaths)
  return applyIgnore(data, ignorePaths!)
}

function applyKeep(data: unknown, paths: string[]): unknown {
  if (Array.isArray(data)) {
    return data.map((item) => applyKeep(item, paths))
  }
  if (data !== null && typeof data === 'object') {
    const result: Record<string, unknown> = {}
    for (const path of paths) {
      const [head, ...tail] = path.split('.')
      if (!head) continue
      const keys = head === '*' ? Object.keys(data as object) : [head]
      for (const key of keys) {
        const value = (data as Record<string, unknown>)[key]
        if (value === undefined) continue
        if (tail.length === 0) {
          result[key] = value
        } else {
          const sub = applyKeep(value, [tail.join('.')])
          // Merge into existing key if already present
          if (key in result && result[key] !== null && typeof result[key] === 'object') {
            result[key] = { ...(result[key] as object), ...(sub as object) }
          } else {
            result[key] = sub
          }
        }
      }
    }
    return result
  }
  return data
}

function applyIgnore(data: unknown, paths: string[]): unknown {
  if (Array.isArray(data)) {
    return data.map((item) => applyIgnore(item, paths))
  }
  if (data !== null && typeof data === 'object') {
    const result: Record<string, unknown> = { ...(data as Record<string, unknown>) }
    for (const path of paths) {
      const [head, ...tail] = path.split('.')
      if (!head) continue
      const keys = head === '*' ? Object.keys(result) : [head]
      for (const key of keys) {
        if (!(key in result)) continue
        if (tail.length === 0) {
          delete result[key]
        } else {
          result[key] = applyIgnore(result[key], [tail.join('.')])
        }
      }
    }
    return result
  }
  return data
}

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
 *       type: http
 *       http:
 *         baseUrl: "https://www.googleapis.com/calendar/v3"
 *         endpoints:
 *           - name: getEvents
 *             method: GET
 *             path: "/calendars/{calendarId}/events"
 *             description: "List events from a Google Calendar"
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
import { AssistantToolFactory } from '@coday/model'
import { CodayTool, FunctionTool } from '@coday/model'
import { CommandContext } from '@coday/model'
import { HttpEndpointConfig, HttpParamConfig, IntegrationConfig } from '@coday/model'
import { Interactor } from '@coday/model'
import { OAuthCallbackEvent } from '@coday/model'
import { UserService } from '@coday/service'
import { GenericOAuth } from './generic-oauth'

export class HttpTools extends AssistantToolFactory {
  static readonly TYPE = 'http' as const

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
        `HTTP integration '${this.name}' requires oauth2.client_id, oauth2.client_secret and oauth2.redirect_uri`
      )
      return []
    }

    const baseUrl = this.config?.http?.baseUrl
    if (!baseUrl) {
      this.interactor.debug(`HTTP integration '${this.name}' requires http.baseUrl`)
      return []
    }

    if (!oauth2Config.authorization_endpoint || !oauth2Config.token_endpoint) {
      this.interactor.debug(
        `HTTP integration '${this.name}' requires oauth2.authorization_endpoint and oauth2.token_endpoint`
      )
      return []
    }

    const endpoints = this.config?.http?.endpoints
    if (!endpoints?.length) {
      this.interactor.debug(`HTTP integration '${this.name}' has no endpoints configured`)
      return []
    }

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
    const accessToken = await this.oauth!.authenticate()
      .then(() => this.oauth!.getAccessToken())
      .catch((err: Error) => {
        throw new Error(`Authentication failed: ${err.message}`)
      })

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

    // Build body params
    const bodyParams = params.filter((p) => p.location === 'body')
    const body =
      bodyParams.length > 0
        ? JSON.stringify(
            Object.fromEntries(bodyParams.filter((p) => args[p.name] !== undefined).map((p) => [p.name, args[p.name]]))
          )
        : undefined

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

    return response.json()
  }
}

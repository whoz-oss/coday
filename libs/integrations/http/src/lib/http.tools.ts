/**
 * Generic HTTP integration tools
 * Dynamically generates tools from endpoint definitions
 */

import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  Interactor,
  OAuthCallbackEvent,
} from '@coday/model'
import { IntegrationService, UserService } from '@coday/service'
import { HttpIntegrationConfig, HttpEndpoint, HttpEndpointParam, isOAuth2Auth } from './http-config'
import { HttpClient } from './http-client'
import { HttpOAuth } from './http-oauth'

export class HttpTools extends AssistantToolFactory {
  static readonly TYPE = 'http' as const

  private client: HttpClient | null = null
  private oauth: HttpOAuth | null = null
  private httpConfig: HttpIntegrationConfig | null = null

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    private readonly userService: UserService,
    instanceName: string,
    config?: any
  ) {
    super(interactor, instanceName, config)
  }

  /**
   * Handle OAuth callback
   */
  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (this.oauth) {
      await this.oauth.handleCallback(event)
    }
  }

  /**
   * Initialize HTTP client and OAuth if needed
   */
  private async initializeClient(context: CommandContext): Promise<void> {
    if (this.client) return

    // Get integration config
    const integrationConfig = this.integrationService.getIntegration(this.name)
    if (!integrationConfig) {
      throw new Error(`Integration '${this.name}' not found`)
    }

    // Validate it's an HTTP integration
    this.httpConfig = integrationConfig as unknown as HttpIntegrationConfig
    if (!this.httpConfig.baseUrl || !this.httpConfig.endpoints) {
      throw new Error(`Invalid HTTP integration config for '${this.name}'`)
    }

    // Create HTTP client
    this.client = new HttpClient(this.httpConfig, this.interactor, this.name)

    // Initialize OAuth if needed
    const auth = this.httpConfig.auth
    if (isOAuth2Auth(auth)) {
      const clientId = integrationConfig.username || integrationConfig.oauth2?.client_id
      const clientSecret = integrationConfig.apiKey || integrationConfig.oauth2?.client_secret
      const redirectUri = integrationConfig.oauth2?.redirect_uri

      if (!clientId || !clientSecret || !redirectUri) {
        throw new Error(`OAuth2 requires clientId, clientSecret, and redirectUri for '${this.name}'`)
      }

      this.oauth = new HttpOAuth(
        clientId,
        clientSecret,
        redirectUri,
        auth,
        this.interactor,
        this.userService,
        context.project.name,
        this.name
      )

      // Initialize OAuth (load tokens or prompt)
      await this.oauth.initialize()

      // Set OAuth on client
      this.client.setOAuth(this.oauth)
    }
  }

  /**
   * Build tools dynamically from endpoint definitions
   */
  protected async buildTools(context: CommandContext): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    try {
      // Initialize client
      await this.initializeClient(context)

      if (!this.httpConfig) {
        return result
      }

      // Generate a tool for each endpoint
      for (const endpoint of this.httpConfig.endpoints) {
        const tool = this.createToolFromEndpoint(endpoint)
        result.push(tool)
      }

      this.interactor.debug(`Generated ${result.length} tools for HTTP integration '${this.name}'`)
    } catch (error) {
      this.interactor.error(`Failed to initialize HTTP integration '${this.name}': ${error}`)
    }

    return result
  }

  /**
   * Create a tool from an endpoint definition
   */
  private createToolFromEndpoint(endpoint: HttpEndpoint): CodayTool {
    // Build tool name: {integrationName}_{endpointName}
    const toolName = `${this.name}_${endpoint.name}`

    // Build parameters schema
    const properties: Record<string, any> = {}
    const required: string[] = []

    if (endpoint.params) {
      for (const param of endpoint.params) {
        properties[param.name] = this.buildParameterSchema(param)
        if (param.required) {
          required.push(param.name)
        }
      }
    }

    // Build description
    const description = endpoint.description || `${endpoint.method} ${endpoint.path}`

    // Create the function tool
    const tool: FunctionTool<Record<string, any>> = {
      type: 'function',
      function: {
        name: toolName,
        description,
        parameters: {
          type: 'object',
          properties,
          ...(required.length > 0 && { required }),
        },
        parse: JSON.parse,
        function: async (params: Record<string, any>) => {
          if (!this.client) {
            throw new Error('HTTP client not initialized')
          }
          return await this.client.request(endpoint, params)
        },
      },
    }

    return tool
  }

  /**
   * Build JSON schema for a parameter
   */
  private buildParameterSchema(param: HttpEndpointParam): any {
    const schema: any = {
      type: param.type,
    }

    if (param.description) {
      schema.description = param.description
    }

    if (param.default !== undefined) {
      schema.default = param.default
    }

    if (param.type === 'array' && param.items) {
      schema.items = {
        type: param.items.type,
      }
    }

    return schema
  }
}

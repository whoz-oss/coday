/**
 * HTTP_CONFIG integration: tools for an agent to manage HTTP integration configurations.
 *
 * Only available to CODAY_ADMIN users. Operates exclusively at PROJECT level.
 * Sensitive fields (oauth2.client_secret, oauth2.tokens, apiKey, username) are
 * never readable nor writable by these tools — they are silently ignored on write
 * and omitted from read output.
 *
 * Exposes 7 tools split into two groups:
 *
 * Integration-level (first-level fields, no endpoints, no secrets):
 *   - list_http_integrations   list all HTTP integrations with endpoint names
 *   - get_http_integration     get one integration's non-sensitive config
 *   - upsert_http_integration  create or update non-sensitive fields
 *   - delete_http_integration  remove an integration entirely
 *
 * Endpoint-level (within one integration):
 *   - get_endpoint     get one endpoint's full definition
 *   - upsert_endpoint  create or replace one endpoint by name
 *   - delete_endpoint  remove one endpoint by name
 */
import {
  AssistantToolFactory,
  CommandContext,
  CodayTool,
  FunctionTool,
  IntegrationConfig,
  Interactor,
} from '@coday/model'
import { IntegrationConfigService, isUserAdmin } from '@coday/service'
import { ConfigLevel } from '@coday/model'
import { HttpEndpointConfig } from '@coday/model'

/** Fields that must never be exposed or written by agent tools */
const SENSITIVE_OAUTH2_FIELDS = ['client_secret', 'tokens'] as const
const SENSITIVE_ROOT_FIELDS = ['apiKey', 'username'] as const

export class HttpConfigTools extends AssistantToolFactory {
  static readonly TYPE = 'HTTP_CONFIG' as const

  constructor(
    interactor: Interactor,
    private readonly integrationConfig: IntegrationConfigService
  ) {
    super(interactor, HttpConfigTools.TYPE, {})
  }

  protected async buildTools(context: CommandContext): Promise<CodayTool[]> {
    if (!isUserAdmin(context.username)) {
      this.interactor.debug(`[HTTP_CONFIG] user '${context.username}' is not CODAY_ADMIN, tools not available`)
      return []
    }

    return [
      this.buildListHttpIntegrations(),
      this.buildGetHttpIntegration(),
      this.buildUpsertHttpIntegration(),
      this.buildDeleteHttpIntegration(),
      this.buildGetEndpoint(),
      this.buildUpsertEndpoint(),
      this.buildDeleteEndpoint(),
    ]
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Return project-level HTTP integrations (raw, unmasked) */
  private getProjectHttpIntegrations(): Record<string, IntegrationConfig> {
    const all = this.integrationConfig.getUnmaskedIntegrationsAtLevel(ConfigLevel.PROJECT)
    return Object.fromEntries(Object.entries(all).filter(([, cfg]) => cfg.type === 'HTTP'))
  }

  /** Strip sensitive fields before returning config to the agent */
  private sanitizeForAgent(config: IntegrationConfig): Record<string, unknown> {
    const result: Record<string, unknown> = { ...config }

    for (const field of SENSITIVE_ROOT_FIELDS) {
      delete result[field]
    }

    if (result['oauth2'] && typeof result['oauth2'] === 'object') {
      const oauth2 = { ...(result['oauth2'] as Record<string, unknown>) }
      for (const field of SENSITIVE_OAUTH2_FIELDS) {
        delete oauth2[field]
      }
      result['oauth2'] = oauth2
    }

    return result
  }

  /** Merge agent-provided data onto existing config, ignoring sensitive fields */
  private mergeIgnoringSensitive(existing: IntegrationConfig, incoming: Record<string, unknown>): IntegrationConfig {
    const merged: IntegrationConfig = { ...existing }

    for (const [key, value] of Object.entries(incoming)) {
      // Skip sensitive root fields silently
      if ((SENSITIVE_ROOT_FIELDS as readonly string[]).includes(key)) continue

      if (key === 'oauth2' && value && typeof value === 'object') {
        const existingOauth2 = existing.oauth2 ?? {}
        const incomingOauth2 = value as Record<string, unknown>
        const mergedOauth2: Record<string, unknown> = { ...existingOauth2 }
        for (const [oKey, oVal] of Object.entries(incomingOauth2)) {
          if (!(SENSITIVE_OAUTH2_FIELDS as readonly string[]).includes(oKey)) {
            mergedOauth2[oKey] = oVal
          }
        }
        merged.oauth2 = mergedOauth2 as IntegrationConfig['oauth2']
      } else if (key !== 'http') {
        // http block is managed separately via endpoint tools
        ;(merged as Record<string, unknown>)[key] = value
      }
    }

    return merged
  }

  // ---------------------------------------------------------------------------
  // Integration-level tools
  // ---------------------------------------------------------------------------

  private buildListHttpIntegrations(): CodayTool {
    const tool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: `${this.name}__list_http_integrations`,
        description:
          'List all HTTP integrations defined at project level. Returns non-sensitive config fields and the list of endpoint names for each integration.',
        parameters: { type: 'object', properties: {} },
        parse: JSON.parse,
        function: async () => {
          const integrations = this.getProjectHttpIntegrations()
          return Object.entries(integrations).map(([name, cfg]) => ({
            name,
            type: cfg.type,
            baseUrl: cfg.http?.baseUrl,
            oauth2: cfg.oauth2
              ? this.sanitizeForAgent({ oauth2: cfg.oauth2 } as IntegrationConfig)['oauth2']
              : undefined,
            endpoints: (cfg.http?.endpoints ?? []).map((e) => e.name),
          }))
        },
      },
    }
    return tool
  }

  private buildGetHttpIntegration(): CodayTool {
    const tool: FunctionTool<{ integrationName: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__get_http_integration`,
        description:
          'Get the non-sensitive configuration of a specific HTTP integration (no secrets, endpoint names only — use get_endpoint for full endpoint details).',
        parameters: {
          type: 'object',
          properties: {
            integrationName: { type: 'string', description: 'Name of the integration as defined in the config' },
          },
          ...({ required: ['integrationName'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName }) => {
          const integrations = this.getProjectHttpIntegrations()
          const cfg = integrations[integrationName]
          if (!cfg) return { error: `Integration '${integrationName}' not found` }
          return {
            name: integrationName,
            ...this.sanitizeForAgent(cfg),
            http: cfg.http
              ? { baseUrl: cfg.http.baseUrl, endpointNames: (cfg.http.endpoints ?? []).map((e) => e.name) }
              : undefined,
          }
        },
      },
    }
    return tool
  }

  private buildUpsertHttpIntegration(): CodayTool {
    type Args = {
      integrationName: string
      baseUrl?: string
      oauth2?: Record<string, unknown>
    }
    const tool: FunctionTool<Args> = {
      type: 'function',
      function: {
        name: `${this.name}__upsert_http_integration`,
        description:
          'Create or update an HTTP integration at project level. Only non-sensitive fields are accepted: baseUrl, oauth2 public fields (client_id, redirect_uri, authorization_endpoint, token_endpoint, scope). Sensitive fields (client_secret, tokens, apiKey) are silently ignored. Existing endpoints are always preserved.',
        parameters: {
          type: 'object',
          properties: {
            integrationName: {
              type: 'string',
              description: 'Name of the integration (uppercase recommended, e.g. MY_API)',
            },
            baseUrl: { type: 'string', description: 'Base URL for all HTTP requests of this integration' },
            oauth2: {
              type: 'object',
              description: 'OAuth2 public configuration. Omit sensitive fields (client_secret, tokens).',
              properties: {
                client_id: { type: 'string' },
                redirect_uri: { type: 'string' },
                authorization_endpoint: { type: 'string' },
                token_endpoint: { type: 'string' },
                scope: { type: 'string', description: 'Space-separated scopes or array of scope strings' },
              },
            },
          },
          ...({ required: ['integrationName'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName, baseUrl, oauth2 }) => {
          const integrations = this.getProjectHttpIntegrations()
          const existing: IntegrationConfig = integrations[integrationName] ?? { type: 'HTTP' }
          const incoming: Record<string, unknown> = {}
          if (oauth2) incoming['oauth2'] = oauth2
          const merged = this.mergeIgnoringSensitive(existing, incoming)
          if (baseUrl !== undefined) {
            merged.http = { ...(merged.http ?? {}), baseUrl }
          }
          merged.type = 'HTTP'
          await this.integrationConfig.saveIntegration(integrationName, merged, ConfigLevel.PROJECT)
          return { success: true, integrationName }
        },
      },
    }
    return tool
  }

  private buildDeleteHttpIntegration(): CodayTool {
    const tool: FunctionTool<{ integrationName: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__delete_http_integration`,
        description: 'Delete an HTTP integration entirely from the project configuration.',
        parameters: {
          type: 'object',
          properties: {
            integrationName: { type: 'string', description: 'Name of the integration to delete' },
          },
          ...({ required: ['integrationName'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName }) => {
          const integrations = this.getProjectHttpIntegrations()
          if (!integrations[integrationName]) {
            return { error: `Integration '${integrationName}' not found` }
          }
          await this.integrationConfig.deleteIntegration(integrationName, ConfigLevel.PROJECT)
          return { success: true, integrationName }
        },
      },
    }
    return tool
  }

  // ---------------------------------------------------------------------------
  // Endpoint-level tools
  // ---------------------------------------------------------------------------

  private buildGetEndpoint(): CodayTool {
    const tool: FunctionTool<{ integrationName: string; endpointName: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__get_endpoint`,
        description: 'Get the full definition of a specific endpoint within an HTTP integration.',
        parameters: {
          type: 'object',
          properties: {
            integrationName: { type: 'string', description: 'Name of the integration' },
            endpointName: { type: 'string', description: 'Name of the endpoint' },
          },
          ...({ required: ['integrationName', 'endpointName'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName, endpointName }) => {
          const integrations = this.getProjectHttpIntegrations()
          const cfg = integrations[integrationName]
          if (!cfg) return { error: `Integration '${integrationName}' not found` }
          const endpoint = (cfg.http?.endpoints ?? []).find((e) => e.name === endpointName)
          if (!endpoint) return { error: `Endpoint '${endpointName}' not found in '${integrationName}'` }
          return endpoint
        },
      },
    }
    return tool
  }

  private buildUpsertEndpoint(): CodayTool {
    const tool: FunctionTool<{ integrationName: string; endpoint: HttpEndpointConfig }> = {
      type: 'function',
      function: {
        name: `${this.name}__upsert_endpoint`,
        description:
          'Create or replace an endpoint within an HTTP integration, identified by endpoint.name. If an endpoint with that name already exists it is fully replaced. Other endpoints are preserved.',
        parameters: {
          type: 'object',
          properties: {
            integrationName: { type: 'string', description: 'Name of the integration' },
            endpoint: {
              type: 'object',
              description: 'Full endpoint definition',
              properties: {
                name: { type: 'string', description: 'Unique name within this integration (used as tool name suffix)' },
                method: { type: 'string', description: 'HTTP method: GET, POST, PUT or DELETE' },
                path: { type: 'string', description: 'Path relative to baseUrl, e.g. /users/{userId}' },
                description: { type: 'string', description: 'Description exposed to the AI as tool description' },
                params: {
                  type: 'array',
                  description: 'List of parameters',
                  items: {
                    type: 'object',
                    properties: {
                      name: { type: 'string' },
                      type: { type: 'string', description: 'string | number | boolean' },
                      description: { type: 'string' },
                      required: { type: 'boolean' },
                      location: { type: 'string', description: 'path | query | body (default: query)' },
                    },
                  },
                },
                keepPaths: {
                  type: 'array',
                  items: { type: 'string' },
                  description: 'Dot-notation paths to keep in response',
                },
                ignorePaths: {
                  type: 'array',
                  items: { type: 'string' },
                  description: 'Dot-notation paths to remove from response',
                },
                responseFormat: { type: 'string', description: 'json (default) or yaml' },
              },
            },
          },
          ...({ required: ['integrationName', 'endpoint'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName, endpoint }) => {
          const integrations = this.getProjectHttpIntegrations()
          const cfg = integrations[integrationName]
          if (!cfg) return { error: `Integration '${integrationName}' not found` }
          const existingEndpoints = cfg.http?.endpoints ?? []
          const updated = [...existingEndpoints.filter((e) => e.name !== endpoint.name), endpoint]
          const updatedConfig: IntegrationConfig = {
            ...cfg,
            http: { ...(cfg.http ?? { baseUrl: '' }), endpoints: updated },
          }
          await this.integrationConfig.saveIntegration(integrationName, updatedConfig, ConfigLevel.PROJECT)
          return { success: true, integrationName, endpointName: endpoint.name }
        },
      },
    }
    return tool
  }

  private buildDeleteEndpoint(): CodayTool {
    const tool: FunctionTool<{ integrationName: string; endpointName: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__delete_endpoint`,
        description: 'Remove a specific endpoint from an HTTP integration. Other endpoints are preserved.',
        parameters: {
          type: 'object',
          properties: {
            integrationName: { type: 'string', description: 'Name of the integration' },
            endpointName: { type: 'string', description: 'Name of the endpoint to remove' },
          },
          ...({ required: ['integrationName', 'endpointName'] } as object),
        },
        parse: JSON.parse,
        function: async ({ integrationName, endpointName }) => {
          const integrations = this.getProjectHttpIntegrations()
          const cfg = integrations[integrationName]
          if (!cfg) return { error: `Integration '${integrationName}' not found` }
          const existingEndpoints = cfg.http?.endpoints ?? []
          if (!existingEndpoints.find((e) => e.name === endpointName)) {
            return { error: `Endpoint '${endpointName}' not found in '${integrationName}'` }
          }
          const updatedConfig: IntegrationConfig = {
            ...cfg,
            http: {
              ...(cfg.http ?? { baseUrl: '' }),
              endpoints: existingEndpoints.filter((e) => e.name !== endpointName),
            },
          }
          await this.integrationConfig.saveIntegration(integrationName, updatedConfig, ConfigLevel.PROJECT)
          return { success: true, integrationName, endpointName }
        },
      },
    }
    return tool
  }
}

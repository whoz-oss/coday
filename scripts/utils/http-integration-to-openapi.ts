/**
 * Converts a Coday TypeScript HTTP integration (`integration.<NAME>.http.{baseUrl, endpoints[]}` in a
 * project YAML, see libs/model/src/lib/integration-config.ts) into an OpenAPI 3.0.3 document accepted by
 * the AgentOS `HTTP_API` plugin, plus the AgentOS IntegrationConfig YAML skeleton pointing at it.
 * Pure functions, no file I/O: the CLI wrapper lives in scripts/http-integration-to-openapi.ts.
 *
 * The types below mirror HttpParamConfig / HttpEndpointConfig / HttpConfig of @coday/model; they are
 * redeclared so that the script stays free of workspace path aliases.
 */
import * as yaml from 'yaml'

export type HttpParamConfig = {
  name: string
  type: 'string' | 'number' | 'boolean'
  description: string
  required?: boolean
  location?: 'path' | 'query' | 'body'
}

export type HttpEndpointConfig = {
  name: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  path: string
  description: string
  params?: HttpParamConfig[]
  keepPaths?: string[]
  ignorePaths?: string[]
  responseFormat?: 'json' | 'yaml'
}

export type HttpIntegrationConfig = {
  baseUrl: string
  endpoints?: HttpEndpointConfig[]
}

export type Conversion = {
  /** The OpenAPI 3.0.3 document, as a plain object. */
  openApi: Record<string, unknown>
  /** The AgentOS IntegrationConfig skeleton, as a plain object. */
  integrationConfig: Record<string, unknown>
  /** Human-readable notes about what could not be carried over as is. */
  warnings: string[]
}

const METHODS_WITH_BODY = new Set(['POST', 'PUT'])
const AUTH_SETTING_PLACEHOLDER = '<AUTH_SETTING_NAME>'

export function convertHttpIntegration(name: string, http: HttpIntegrationConfig): Conversion {
  const warnings: string[] = []
  const paths: Record<string, Record<string, Record<string, unknown>>> = {}
  const kept: HttpEndpointConfig[] = []
  for (const endpoint of http.endpoints ?? []) {
    const pathItem = paths[endpoint.path] ?? {}
    const method = endpoint.method.toLowerCase()
    const existing = pathItem[method]
    if (existing) {
      warnings.push(
        `${endpoint.name}: duplicates ${endpoint.method} ${endpoint.path} of ${existing.operationId}, dropped`
      )
      continue
    }
    pathItem[method] = toOperation(endpoint, warnings)
    paths[endpoint.path] = pathItem
    kept.push(endpoint)
  }
  const openApi: Record<string, unknown> = {
    openapi: '3.0.3',
    info: { title: name, version: '1.0.0' },
    servers: [{ url: http.baseUrl }],
    paths,
  }
  return { openApi, integrationConfig: toIntegrationConfig(name, openApi, kept), warnings }
}

function toOperation(endpoint: HttpEndpointConfig, warnings: string[]): Record<string, unknown> {
  const params = endpoint.params ?? []
  const bodyAllowed = METHODS_WITH_BODY.has(endpoint.method)
  const bodyParams = params.filter((p) => p.location === 'body')
  if (bodyParams.length > 0 && !bodyAllowed) {
    warnings.push(
      `${endpoint.name}: HTTP_API sends no body on ${endpoint.method}; body params ` +
        `${bodyParams.map((p) => p.name).join(', ')} were converted to query parameters`
    )
  }
  // A path param stays in the path; a query param, and a body param of a body-less method, go to the query.
  const parameters = params
    .filter((p) => p.location !== 'body' || !bodyAllowed)
    .map((p) => toParameter(p, p.location === 'path' ? 'path' : 'query'))
  const operation: Record<string, unknown> = {
    operationId: endpoint.name,
    summary: endpoint.description,
  }
  if (parameters.length > 0) operation.parameters = parameters
  if (bodyAllowed && bodyParams.length > 0) operation.requestBody = toRequestBody(bodyParams)
  operation.responses = { '200': { description: 'Successful response' } }
  return operation
}

function toParameter(param: HttpParamConfig, inLocation: 'path' | 'query'): Record<string, unknown> {
  const parameter: Record<string, unknown> = { name: param.name, in: inLocation, description: param.description }
  if (inLocation === 'path' || param.required) parameter.required = true
  parameter.schema = { type: param.type }
  return parameter
}

function toRequestBody(bodyParams: HttpParamConfig[]): Record<string, unknown> {
  const properties: Record<string, unknown> = {}
  for (const param of bodyParams) {
    properties[param.name] = { type: param.type, description: param.description }
  }
  const required = bodyParams.filter((p) => p.required).map((p) => p.name)
  const schema: Record<string, unknown> = { type: 'object', properties }
  if (required.length > 0) schema.required = required
  return {
    required: required.length > 0,
    content: { 'application/json': { schema } },
  }
}

/** @param endpoints The endpoints that became operations (duplicates already dropped). */
function toIntegrationConfig(
  name: string,
  openApi: Record<string, unknown>,
  endpoints: HttpEndpointConfig[]
): Record<string, unknown> {
  const operations = endpoints
    .map((endpoint) => {
      const override: Record<string, unknown> = { operationId: endpoint.name }
      if (endpoint.keepPaths?.length) override.keepPaths = endpoint.keepPaths
      if (endpoint.ignorePaths?.length) override.ignorePaths = endpoint.ignorePaths
      if (endpoint.responseFormat) override.responseFormat = endpoint.responseFormat
      return override
    })
    .filter((override) => Object.keys(override).length > 1)
  const parameters: Record<string, unknown> = { spec: { inline: toYaml(openApi) } }
  if (endpoints.some((endpoint) => endpoint.method !== 'GET')) parameters.allowMutations = true
  if (operations.length > 0) parameters.operations = operations
  return {
    name,
    integrationType: 'HTTP_API',
    authSettingName: AUTH_SETTING_PLACEHOLDER,
    parameters,
  }
}

export function toYaml(value: unknown): string {
  return yaml.stringify(value, { lineWidth: 0 })
}

import { readFileSync } from 'fs'
import { join } from 'path'
import * as yaml from 'yaml'
import { convertHttpIntegration, HttpIntegrationConfig, toYaml } from './http-integration-to-openapi'

/** The example of libs/integrations/http/src/lib/http.tools.ts, as parsed from a project YAML. */
const MY_CALENDAR: HttpIntegrationConfig = {
  baseUrl: 'https://www.googleapis.com/calendar/v3',
  endpoints: [
    {
      name: 'getEvents',
      method: 'GET',
      path: '/calendars/{calendarId}/events',
      description: 'List events from a Google Calendar',
      responseFormat: 'yaml',
      keepPaths: ['items.*.summary', 'items.*.start', 'items.*.end'],
      params: [
        {
          name: 'calendarId',
          type: 'string',
          description: "Calendar ID, use 'primary' for main calendar",
          required: true,
          location: 'path',
        },
        {
          name: 'timeMin',
          type: 'string',
          description: 'Lower bound ISO 8601 (e.g. 2024-01-15T00:00:00Z)',
          location: 'query',
        },
        {
          name: 'maxResults',
          type: 'number',
          description: 'Max events to return (default 10, max 250)',
          location: 'query',
        },
      ],
    },
  ],
}

/** The operation `method` of `path` in an OpenAPI document produced by the converter; fails when absent. */
function operationOf(doc: Record<string, unknown>, path: string, method: string): Record<string, unknown> {
  const paths = doc.paths as Record<string, Record<string, Record<string, unknown>> | undefined>
  const operation = paths[path]?.[method]
  if (!operation) throw new Error(`no ${method} ${path} in the document`)
  return operation
}

/** The fixture the AgentOS plugin round-trip test reads: it must be exactly what the converter produces. */
const PLUGIN_FIXTURE = join(
  __dirname,
  '..',
  '..',
  'agentos',
  'agentos-http-plugin',
  'src',
  'test',
  'resources',
  'openapi',
  'converted-my-calendar.yaml'
)

describe('convertHttpIntegration', () => {
  const { openApi, integrationConfig, warnings } = convertHttpIntegration('MY_CALENDAR', MY_CALENDAR)

  it('produces an OpenAPI 3.0.3 document with the base URL as server', () => {
    expect(openApi.openapi).toBe('3.0.3')
    expect(openApi.info).toEqual({ title: 'MY_CALENDAR', version: '1.0.0' })
    expect(openApi.servers).toEqual([{ url: 'https://www.googleapis.com/calendar/v3' }])
  })

  it('maps an endpoint to an operation named after it, with path and query parameters', () => {
    const operation = operationOf(openApi, '/calendars/{calendarId}/events', 'get')
    expect(operation.operationId).toBe('getEvents')
    expect(operation.summary).toBe('List events from a Google Calendar')
    expect(operation.parameters).toEqual([
      {
        name: 'calendarId',
        in: 'path',
        description: "Calendar ID, use 'primary' for main calendar",
        required: true,
        schema: { type: 'string' },
      },
      {
        name: 'timeMin',
        in: 'query',
        description: 'Lower bound ISO 8601 (e.g. 2024-01-15T00:00:00Z)',
        schema: { type: 'string' },
      },
      {
        name: 'maxResults',
        in: 'query',
        description: 'Max events to return (default 10, max 250)',
        schema: { type: 'number' },
      },
    ])
    expect(operation.requestBody).toBeUndefined()
    expect(operation.responses).toEqual({ '200': { description: 'Successful response' } })
    expect(warnings).toEqual([])
  })

  it('groups body params into a JSON request body and defaults the location to query', () => {
    const { openApi: doc } = convertHttpIntegration('X', {
      baseUrl: 'https://api.example.com',
      endpoints: [
        {
          name: 'createEvent',
          method: 'POST',
          path: '/events',
          description: 'Create',
          params: [
            { name: 'summary', type: 'string', description: 'Title', required: true, location: 'body' },
            { name: 'allDay', type: 'boolean', description: 'All day', location: 'body' },
            { name: 'sendUpdates', type: 'string', description: 'Notify' },
          ],
        },
      ],
    })
    const operation = operationOf(doc, '/events', 'post')
    expect(operation.parameters).toEqual([
      { name: 'sendUpdates', in: 'query', description: 'Notify', schema: { type: 'string' } },
    ])
    expect(operation.requestBody).toEqual({
      required: true,
      content: {
        'application/json': {
          schema: {
            type: 'object',
            properties: {
              summary: { type: 'string', description: 'Title' },
              allDay: { type: 'boolean', description: 'All day' },
            },
            required: ['summary'],
          },
        },
      },
    })
  })

  it('turns body params of a GET or DELETE endpoint into query parameters with a warning', () => {
    const { openApi: doc, warnings: notes } = convertHttpIntegration('X', {
      baseUrl: 'https://api.example.com',
      endpoints: [
        {
          name: 'search',
          method: 'GET',
          path: '/search',
          description: 'Search',
          params: [{ name: 'q', type: 'string', description: 'Query', location: 'body' }],
        },
      ],
    })
    const operation = operationOf(doc, '/search', 'get')
    expect(operation.parameters).toEqual([{ name: 'q', in: 'query', description: 'Query', schema: { type: 'string' } }])
    expect(operation.requestBody).toBeUndefined()
    expect(notes).toEqual(['search: HTTP_API sends no body on GET; body params q were converted to query parameters'])
  })

  it('keeps the first of two endpoints sharing a path and method and warns about the dropped one', () => {
    const {
      openApi: doc,
      integrationConfig: config,
      warnings: notes,
    } = convertHttpIntegration('X', {
      baseUrl: 'https://api.example.com',
      endpoints: [
        { name: 'listA', method: 'GET', path: '/items', description: 'A' },
        { name: 'listB', method: 'GET', path: '/items', description: 'B', keepPaths: ['id'] },
      ],
    })
    expect(operationOf(doc, '/items', 'get').operationId).toBe('listA')
    expect(notes).toEqual(['listB: duplicates GET /items of listA, dropped'])
    expect((config.parameters as Record<string, unknown>).operations).toBeUndefined()
  })

  it('builds the IntegrationConfig skeleton with the inline document and per-operation shaping', () => {
    expect(integrationConfig.name).toBe('MY_CALENDAR')
    expect(integrationConfig.integrationType).toBe('HTTP_API')
    expect(integrationConfig.authSettingName).toBe('<AUTH_SETTING_NAME>')
    const parameters = integrationConfig.parameters as Record<string, unknown>
    expect(parameters.allowMutations).toBeUndefined()
    expect(parameters.operations).toEqual([
      {
        operationId: 'getEvents',
        keepPaths: ['items.*.summary', 'items.*.start', 'items.*.end'],
        responseFormat: 'yaml',
      },
    ])
    const inline = (parameters.spec as { inline: string }).inline
    expect(yaml.parse(inline)).toEqual(openApi)
  })

  it('enables mutations in the skeleton only when a non-GET endpoint exists', () => {
    const { integrationConfig: writable } = convertHttpIntegration('X', {
      baseUrl: 'https://api.example.com',
      endpoints: [{ name: 'del', method: 'DELETE', path: '/x/{id}', description: 'Delete' }],
    })
    expect((writable.parameters as Record<string, unknown>).allowMutations).toBe(true)
  })

  it('matches the fixture the AgentOS plugin round-trip test reads', () => {
    expect(toYaml(openApi)).toBe(readFileSync(PLUGIN_FIXTURE, 'utf8'))
  })
})

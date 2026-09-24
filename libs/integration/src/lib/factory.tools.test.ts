import { CodayTool, CommandContext, Interactor } from '@coday/model'
import { FactoryTools, validateWorkflowProjection } from './factory.tools'

const projection = {
  schemaVersion: '1',
  workflowId: 'demo.id',
  workflowType: 'demo',
  title: 'Demo',
  status: 'running',
  steps: [],
}
const configuredProject = {
  root: '/tmp',
  name: 'sprint',
  description: '',
  factory: { enabled: true, baseUrl: 'http://127.0.0.1:3141/', namespaceId: '0d4bd471-df37-43d8-a8f7-c989f95e71d7' },
}
const interactor = {} as Interactor

type ExecutableTool = CodayTool & {
  function: CodayTool['function'] & { function: (input: unknown) => unknown }
}

type ToolSchema = { properties: Record<string, unknown> }

function requireExecutableTool(tools: CodayTool[], expectedName: string): ExecutableTool {
  const candidate = tools.find((item) => item.function.name === expectedName)
  if (!candidate) throw new Error(`Expected tool ${expectedName} was not exposed.`)
  if (typeof candidate.function.function !== 'function') throw new Error(`Tool ${expectedName} is not executable.`)
  return candidate as ExecutableTool
}

async function invokeJson(tool: ExecutableTool, input: unknown): Promise<Record<string, any>> {
  const output = await tool.function.function(input)
  if (typeof output !== 'string') throw new Error(`Tool ${tool.function.name} returned a non-string result.`)
  return JSON.parse(output) as Record<string, any>
}

function requireSchema(tool: ExecutableTool): ToolSchema {
  const schema = tool.function.parameters
  if (!schema || typeof schema !== 'object' || !('properties' in schema)) {
    throw new Error(`Tool ${tool.function.name} has no object properties schema.`)
  }
  return schema as ToolSchema
}

function requireFetchCall(fetchMock: jest.SpyInstance): [string | URL | Request, RequestInit] {
  const call = fetchMock.mock.calls[0]
  if (!call) throw new Error('Expected fetch to be called.')
  const [url, request] = call
  if (!request) throw new Error('Expected fetch request options.')
  return [url, request]
}

async function tool(project: any = configuredProject, username = 'benjamin.valdes', agent = 'ProductEngineer') {
  const factory = new FactoryTools(interactor, 'FACTORY', {})
  const context = new CommandContext(project, username)
  context.aiThread = { id: 'thread-123' } as any
  return requireExecutableTool(
    await factory.getTools(context, ['publish_projection'], agent),
    'FACTORY__publish_projection'
  )
}

describe('FactoryTools transitional adapter', () => {
  afterEach(() => jest.restoreAllMocks())

  it('discovers only explicitly allowlisted capabilities', async () => {
    const factory = new FactoryTools(interactor, 'FACTORY', {})
    const context = new CommandContext(configuredProject as any, 'user')
    expect(
      (await factory.getTools(context, ['get_workflow'], 'ProductEngineer')).map((it) => it.function.name)
    ).toEqual(['FACTORY__get_workflow'])
    expect(
      (await factory.getTools(context, ['publish_projection'], 'ProductEngineer')).map((it) => it.function.name)
    ).toEqual(['FACTORY__publish_projection'])
    expect(
      (await factory.getTools(context, ['transition_workflow'], 'ProductEngineer')).map((it) => it.function.name)
    ).toEqual(['FACTORY__transition_workflow'])
    expect(await factory.getTools(context, ['another_tool'], 'ProductEngineer')).toEqual([])
  })

  it.each(['absent', 'existing', 'removed', 'purged'])(
    'looks up %s with workflowId only and trusted project namespace',
    async (state) => {
      const payload =
        state === 'existing'
          ? {
              data: {
                namespaceId: configuredProject.factory.namespaceId,
                state,
                workflowId: 'demo.id',
                revision: 4,
                projection: { ...projection, schemaVersion: '2', steps: [] },
              },
            }
          : { data: { namespaceId: configuredProject.factory.namespaceId, state, workflowId: 'demo.id' } }
      const fetchMock = jest
        .spyOn(global, 'fetch')
        .mockResolvedValue(new Response(JSON.stringify(payload), { status: 200 }))
      const factory = new FactoryTools(interactor, 'FACTORY', {})
      const context = new CommandContext(configuredProject as any, 'benjamin.valdes')
      const lookup = requireExecutableTool(
        await factory.getTools(context, ['get_workflow'], 'ProductEngineer'),
        'FACTORY__get_workflow'
      )
      expect(Object.keys(requireSchema(lookup).properties)).toEqual(['workflowId'])
      const result = await invokeJson(lookup, { workflowId: 'demo.id' })
      expect(result.state).toBe(state)
      expect(result.workflowId).toBe('demo.id')
      if (state === 'existing') expect(result).toMatchObject({ revision: 4, workflowType: 'demo' })
      const [url, request] = requireFetchCall(fetchMock)
      expect(url).toBe(
        `http://127.0.0.1:3141/api/factory/workflows/demo.id?namespaceId=${configuredProject.factory.namespaceId}`
      )
      expect(request).toMatchObject({ signal: expect.any(AbortSignal) })
    }
  )

  it('rejects lookup attribution fields supplied by the model', async () => {
    const fetchMock = jest.spyOn(global, 'fetch')
    const factory = new FactoryTools(interactor, 'FACTORY', {})
    const context = new CommandContext(configuredProject as any, 'user')
    const lookup = requireExecutableTool(
      await factory.getTools(context, ['get_workflow'], 'ProductEngineer'),
      'FACTORY__get_workflow'
    )
    expect(
      (await invokeJson(lookup, { workflowId: 'demo.id', namespaceId: configuredProject.factory.namespaceId })).error
        .code
    ).toBe('INVALID_REQUEST')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('exposes transition_workflow as the governed alias with a strict model-only schema', async () => {
    const factory = new FactoryTools(interactor, 'FACTORY', {})
    const context = new CommandContext(configuredProject as any, 'benjamin.valdes')
    context.aiThread = { id: 'thread-123' } as any
    const transition = requireExecutableTool(
      await factory.getTools(context, ['transition_workflow'], 'ProductEngineer'),
      'FACTORY__transition_workflow'
    )
    expect(Object.keys(requireSchema(transition).properties)).toEqual([
      'workflowId',
      'stepId',
      'expectedRevision',
      'requestedStatus',
      'evidenceIds',
      'idempotencyKey',
    ])
    expect((transition.function.parameters as { additionalProperties: boolean }).additionalProperties).toBe(false)
  })

  it.each([
    [{ ...configuredProject, factory: undefined }, 'disabled'],
    [{ ...configuredProject, factory: { enabled: false } }, 'disabled'],
    [
      { ...configuredProject, factory: { enabled: true, baseUrl: 'http://localhost:3141', namespaceId: 'not-a-uuid' } },
      'valid UUID',
    ],
  ])('fails closed for project config', async (project, message) => {
    const fetchMock = jest.spyOn(global, 'fetch')
    const result = await invokeJson(await tool(project), projection)
    expect(result.error.code).toBe('FACTORY_UNAVAILABLE')
    expect(result.error.message).toContain(message)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('keeps trusted identity outside the model schema and sends the execution envelope', async () => {
    const fetchMock = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(
        new Response(JSON.stringify({ data: { workflowId: 'demo.id', revision: 2, changed: true } }), { status: 200 })
      )
    const exposed = await tool()
    const properties = requireSchema(exposed).properties
    expect(properties.namespaceId).toBeUndefined()
    expect(properties.baseUrl).toBeUndefined()
    expect(properties.actorId).toBeUndefined()
    expect(properties.agentId).toBeUndefined()
    expect(properties.caseId).toBeUndefined()
    expect(properties.threadId).toBeUndefined()
    expect(properties.runtimeId).toBeUndefined()
    const result = await invokeJson(exposed, projection)
    expect(result).toMatchObject({ workflowId: 'demo.id', revision: 2, changed: true })
    expect(result.updatedAt).toEqual(expect.any(String))
    const [url, request] = requireFetchCall(fetchMock)
    expect(url).toBe('http://127.0.0.1:3141/api/factory/workflows/demo.id/projection')
    if (typeof request.body !== 'string') throw new Error('Expected a JSON request body.')
    expect(JSON.parse(request.body)).toEqual({
      projection,
      execution: {
        namespaceId: configuredProject.factory.namespaceId,
        runtimeId: 'coday-express-transitional',
        kind: 'coday-express',
        actorId: 'benjamin.valdes',
        agentId: 'ProductEngineer',
        threadId: 'thread-123',
      },
    })
  })

  it.each(['REVISION_CONFLICT', 'WORKFLOW_REMOVED', 'INVALID_PROJECTION'])(
    'preserves Factory error %s',
    async (code) => {
      jest
        .spyOn(global, 'fetch')
        .mockResolvedValue(new Response(JSON.stringify({ error: { code, message: 'safe' } }), { status: 409 }))
      expect(await invokeJson(await tool(), projection)).toEqual({
        error: { code, message: 'safe' },
      })
    }
  )

  it('maps network, timeout, and malformed responses deterministically', async () => {
    const fetchMock = jest.spyOn(global, 'fetch')
    fetchMock.mockRejectedValueOnce(new Error('secret network details'))
    expect((await invokeJson(await tool(), projection)).error.code).toBe('FACTORY_UNAVAILABLE')
    fetchMock.mockRejectedValueOnce(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    expect((await invokeJson(await tool(), projection)).error).toEqual({
      code: 'FACTORY_TIMEOUT',
      message: 'Factory publication timed out.',
    })
    fetchMock.mockResolvedValueOnce(new Response('{bad', { status: 200 }))
    expect((await invokeJson(await tool(), projection)).error.code).toBe('MALFORMED_FACTORY_RESPONSE')
  })
})

describe('validateWorkflowProjection', () => {
  it('rejects unsafe IDs, duplicate/missing dependencies and cycles', () => {
    expect(validateWorkflowProjection(projection)).toContain('workflowId')
    expect(
      validateWorkflowProjection({
        ...projection,
        workflowId: 'demo',
        steps: [
          { id: 'a', name: 'A', status: 'ready' },
          { id: 'a', name: 'B', status: 'ready' },
        ],
      })
    ).toContain('unique')
    expect(
      validateWorkflowProjection({
        ...projection,
        workflowId: 'demo',
        steps: [{ id: 'a', name: 'A', status: 'ready', dependsOn: ['missing'] }],
      })
    ).toContain('dependency')
    expect(
      validateWorkflowProjection({
        ...projection,
        workflowId: 'demo',
        steps: [
          { id: 'a', name: 'A', status: 'ready', dependsOn: ['b'] },
          { id: 'b', name: 'B', status: 'ready', dependsOn: ['a'] },
        ],
      })
    ).toContain('cycle')
  })
})

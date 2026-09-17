import { CommandContext, Interactor } from '@coday/model'
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

async function tool(project: any = configuredProject, username = 'benjamin.valdes', agent = 'ProductEngineer') {
  const factory = new FactoryTools(interactor, 'FACTORY', {})
  const context = new CommandContext(project, username)
  context.aiThread = { id: 'thread-123' } as any
  return (await factory.getTools(context, ['publish_projection'], agent))[0]
}

describe('FactoryTools transitional adapter', () => {
  afterEach(() => jest.restoreAllMocks())

  it('discovers only the explicitly allowlisted tool', async () => {
    const factory = new FactoryTools(interactor, 'FACTORY', {})
    const context = new CommandContext(configuredProject as any, 'user')
    expect(
      (await factory.getTools(context, ['publish_projection'], 'ProductEngineer')).map((it) => it.function.name)
    ).toEqual(['FACTORY__publish_projection'])
    expect(await factory.getTools(context, ['another_tool'], 'ProductEngineer')).toEqual([])
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
    const result = JSON.parse(await (await tool(project)).function.function(projection))
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
    const properties = (exposed.function.parameters as any).properties
    expect(properties.namespaceId).toBeUndefined()
    expect(properties.baseUrl).toBeUndefined()
    expect(properties.actorId).toBeUndefined()
    expect(properties.agentId).toBeUndefined()
    expect(properties.caseId).toBeUndefined()
    expect(properties.threadId).toBeUndefined()
    expect(properties.runtimeId).toBeUndefined()
    const result = JSON.parse(await exposed.function.function(projection))
    expect(result).toMatchObject({ workflowId: 'demo.id', revision: 2, changed: true })
    expect(result.updatedAt).toEqual(expect.any(String))
    const [url, request] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://127.0.0.1:3141/api/factory/workflows/demo.id/projection')
    expect(JSON.parse(request!.body as string)).toEqual({
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
      expect(JSON.parse(await (await tool()).function.function(projection))).toEqual({
        error: { code, message: 'safe' },
      })
    }
  )

  it('maps network, timeout, and malformed responses deterministically', async () => {
    const fetchMock = jest.spyOn(global, 'fetch')
    fetchMock.mockRejectedValueOnce(new Error('secret network details'))
    expect(JSON.parse(await (await tool()).function.function(projection)).error.code).toBe('FACTORY_UNAVAILABLE')
    fetchMock.mockRejectedValueOnce(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    expect(JSON.parse(await (await tool()).function.function(projection)).error).toEqual({
      code: 'FACTORY_TIMEOUT',
      message: 'Factory publication timed out.',
    })
    fetchMock.mockResolvedValueOnce(new Response('{bad', { status: 200 }))
    expect(JSON.parse(await (await tool()).function.function(projection)).error.code).toBe('MALFORMED_FACTORY_RESPONSE')
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

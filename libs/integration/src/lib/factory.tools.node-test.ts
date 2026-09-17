import assert from 'node:assert/strict'
import { afterEach, describe, it, mock } from 'node:test'
import { CommandContext, Interactor } from '@coday/model'
import { FactoryTools } from './factory.tools'

const namespaceId = '0d4bd471-df37-43d8-a8f7-c989f95e71d7'
const project = {
  root: '/tmp',
  name: 'sprint',
  description: '',
  factory: { enabled: true, baseUrl: 'http://127.0.0.1:3141/', namespaceId },
}
const interactor = {} as Interactor

afterEach(() => mock.restoreAll())

async function exposed(capability: string) {
  const context = new CommandContext(project as never, 'benjamin.valdes')
  context.aiThread = { id: 'thread-123' } as never
  const tools = await new FactoryTools(interactor, 'FACTORY', {}).getTools(context, [capability], 'ProductEngineer')
  const tool = tools.find((candidate) => candidate.function.name === `FACTORY__${capability}`)
  assert.ok(tool, `FACTORY__${capability} must be exposed`)
  assert.equal(typeof tool.function.function, 'function')
  return tool
}

async function invoke(tool: Awaited<ReturnType<typeof exposed>>, input: unknown) {
  const raw = await tool.function.function(input)
  assert.equal(typeof raw, 'string')
  return JSON.parse(raw as string)
}

describe('FactoryTools Phase 1 without Jest/Haste', () => {
  it('filters capabilities and exposes workflowId-only lookup schema', async () => {
    const lookup = await exposed('get_workflow')
    assert.deepEqual(Object.keys((lookup.function.parameters as { properties: object }).properties), ['workflowId'])
    assert.equal((await exposed('publish_projection')).function.name, 'FACTORY__publish_projection')
    const context = new CommandContext(project as never, 'user')
    assert.deepEqual(
      await new FactoryTools(interactor, 'FACTORY', {}).getTools(context, ['unknown'], 'ProductEngineer'),
      []
    )
  })

  for (const state of ['absent', 'existing', 'removed', 'purged']) {
    it(`maps ${state} using the trusted project namespace`, async () => {
      let requestedUrl: string | URL | Request | undefined
      mock.method(globalThis, 'fetch', async (url: string | URL | Request) => {
        requestedUrl = url
        const data =
          state === 'existing'
            ? {
                namespaceId,
                state,
                workflowId: 'demo.id',
                revision: 4,
                projection: {
                  schemaVersion: '1',
                  workflowId: 'demo.id',
                  workflowType: 'demo',
                  title: 'Demo',
                  status: 'running',
                  steps: [],
                },
              }
            : { namespaceId, state, workflowId: 'demo.id' }
        return new Response(JSON.stringify({ data }), { status: 200 })
      })
      const result = await invoke(await exposed('get_workflow'), { workflowId: 'demo.id' })
      assert.equal(result.state, state)
      assert.equal(result.workflowId, 'demo.id')
      if (state === 'existing')
        assert.deepEqual(
          { revision: result.revision, workflowType: result.workflowType },
          { revision: 4, workflowType: 'demo' }
        )
      assert.equal(
        String(requestedUrl),
        `http://127.0.0.1:3141/api/factory/workflows/demo.id?namespaceId=${namespaceId}`
      )
    })
  }

  it('rejects model-supplied attribution without calling Factory', async () => {
    let called = false
    mock.method(globalThis, 'fetch', async () => {
      called = true
      return new Response()
    })
    const result = await invoke(await exposed('get_workflow'), { workflowId: 'demo.id', namespaceId })
    assert.equal(result.error.code, 'INVALID_REQUEST')
    assert.equal(called, false)
  })

  it('preserves Factory errors and maps unavailable, timeout and malformed responses', async () => {
    mock.method(
      globalThis,
      'fetch',
      async () =>
        new Response(JSON.stringify({ error: { code: 'WORKFLOW_REMOVED', message: 'safe' } }), { status: 409 })
    )
    assert.deepEqual(await invoke(await exposed('get_workflow'), { workflowId: 'demo.id' }), {
      error: { code: 'WORKFLOW_REMOVED', message: 'safe' },
    })
    mock.restoreAll()
    mock.method(globalThis, 'fetch', async () => {
      throw new Error('secret')
    })
    assert.equal(
      (await invoke(await exposed('get_workflow'), { workflowId: 'demo.id' })).error.code,
      'FACTORY_UNAVAILABLE'
    )
    mock.restoreAll()
    mock.method(globalThis, 'fetch', async () => {
      throw Object.assign(new Error('aborted'), { name: 'AbortError' })
    })
    assert.equal((await invoke(await exposed('get_workflow'), { workflowId: 'demo.id' })).error.code, 'FACTORY_TIMEOUT')
    mock.restoreAll()
    mock.method(globalThis, 'fetch', async () => new Response('{bad', { status: 200 }))
    assert.equal(
      (await invoke(await exposed('get_workflow'), { workflowId: 'demo.id' })).error.code,
      'MALFORMED_FACTORY_RESPONSE'
    )
  })
})

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
    assert.equal((await exposed('start_workflow')).function.name, 'FACTORY__start_workflow')
    assert.equal((await exposed('publish_projection')).function.name, 'FACTORY__publish_projection')
    assert.equal((await exposed('record_agent_result')).function.name, 'FACTORY__record_agent_result')
    assert.equal((await exposed('record_artifact')).function.name, 'FACTORY__record_artifact')
    assert.equal((await exposed('request_transition')).function.name, 'FACTORY__request_transition')
    const context = new CommandContext(project as never, 'user')
    assert.deepEqual(
      await new FactoryTools(interactor, 'FACTORY', {}).getTools(context, ['unknown'], 'ProductEngineer'),
      []
    )
  })

  it('starts independently with a strict schema and trusted Express context', async () => {
    const start = await exposed('start_workflow')
    assert.deepEqual(Object.keys((start.function.parameters as { properties: object }).properties), [
      'workflowId',
      'workflowType',
      'title',
    ])
    assert.equal((start.function.parameters as { additionalProperties: boolean }).additionalProperties, false)
    let requestedUrl: string | URL | Request | undefined
    let request: RequestInit | undefined
    mock.method(globalThis, 'fetch', async (url: string | URL | Request, init?: RequestInit) => {
      requestedUrl = url
      request = init
      return new Response(
        JSON.stringify({ data: { workflowId: 'demo.id', revision: 1, created: true, idempotent: false } }),
        { status: 201 }
      )
    })
    const result = await invoke(start, { workflowId: 'demo.id', workflowType: 'demo', title: 'Demo' })
    assert.equal(result.created, true)
    assert.equal(String(requestedUrl), 'http://127.0.0.1:3141/api/factory/workflows/demo.id/start')
    assert.deepEqual(JSON.parse(request?.body as string), {
      workflow: { workflowId: 'demo.id', workflowType: 'demo', title: 'Demo' },
      execution: {
        namespaceId,
        runtimeId: 'coday-express-transitional',
        kind: 'coday-express',
        actorId: 'benjamin.valdes',
        agentId: 'ProductEngineer',
        threadId: 'thread-123',
      },
    })
  })

  it('rejects start attribution fields and preserves Factory errors', async () => {
    let called = false
    mock.method(globalThis, 'fetch', async () => {
      called = true
      return new Response()
    })
    assert.equal(
      (
        await invoke(await exposed('start_workflow'), {
          workflowId: 'demo.id',
          workflowType: 'demo',
          title: 'Demo',
          namespaceId,
        })
      ).error.code,
      'INVALID_START_REQUEST'
    )
    assert.equal(called, false)
    mock.restoreAll()
    mock.method(
      globalThis,
      'fetch',
      async () =>
        new Response(JSON.stringify({ error: { code: 'WORKFLOW_DEFINITION_AMBIGUOUS', message: 'safe' } }), {
          status: 409,
        })
    )
    assert.equal(
      (await invoke(await exposed('start_workflow'), { workflowId: 'demo.id', workflowType: 'demo', title: 'Demo' }))
        .error.code,
      'WORKFLOW_DEFINITION_AMBIGUOUS'
    )
  })

  for (const capability of ['record_agent_result', 'record_artifact'] as const) {
    it(`records ${capability} with an independent strict schema and trusted Express source`, async () => {
      const tool = await exposed(capability)
      const schema = tool.function.parameters as { additionalProperties: boolean; properties: Record<string, unknown> }
      assert.equal(schema.additionalProperties, false)
      assert.deepEqual(
        Object.keys(schema.properties),
        capability === 'record_agent_result'
          ? ['workflowId', 'stepId', 'idempotencyKey', 'outcome', 'facts']
          : ['workflowId', 'stepId', 'idempotencyKey', 'artifactRef', 'artifactHash']
      )
      let requestedUrl: string | URL | Request | undefined
      let request: RequestInit | undefined
      mock.method(globalThis, 'fetch', async (url: string | URL | Request, init?: RequestInit) => {
        requestedUrl = url
        request = init
        return new Response(
          JSON.stringify({
            data: {
              namespaceId,
              workflowId: 'wf-1',
              created: false,
              idempotent: true,
              evidence: { evidenceId: 'factory-id' },
            },
          }),
          { status: 200 }
        )
      })
      const business =
        capability === 'record_agent_result'
          ? {
              workflowId: 'wf-1',
              stepId: 'implement',
              outcome: 'pass',
              facts: { resultCode: 'DONE' },
              idempotencyKey: 'turn-1',
            }
          : {
              workflowId: 'wf-1',
              stepId: 'implement',
              artifactRef: 'opaque://artifact',
              artifactHash: `sha256:${'a'.repeat(64)}`,
              idempotencyKey: 'artifact-1',
            }
      const result = await invoke(tool, business)
      assert.equal(result.idempotent, true)
      assert.equal(String(requestedUrl), 'http://127.0.0.1:3141/api/factory/workflows/wf-1/evidence')
      assert.deepEqual(JSON.parse(request?.body as string), {
        evidence: { ...business, kind: capability === 'record_agent_result' ? 'agent-result' : 'artifact' },
        execution: {
          namespaceId,
          runtimeId: 'coday-express-transitional',
          kind: 'coday-express',
          agentId: 'ProductEngineer',
          threadId: 'thread-123',
          actorId: 'benjamin.valdes',
        },
      })
    })
  }

  it('requests transitions with strict business schema and trusted Express execution', async () => {
    const tool = await exposed('request_transition')
    const schema = tool.function.parameters as { properties: Record<string, unknown>; additionalProperties: boolean }
    assert.equal(schema.additionalProperties, false)
    assert.deepEqual(Object.keys(schema.properties), [
      'workflowId',
      'stepId',
      'expectedRevision',
      'requestedStatus',
      'evidenceIds',
      'idempotencyKey',
    ])
    for (const forbidden of ['requestId', 'namespaceId', 'runtimeId', 'actorId', 'agentId', 'caseId', 'threadId'])
      assert.equal(schema.properties[forbidden], undefined)
    let requestedUrl: string | URL | Request | undefined
    let request: RequestInit | undefined
    mock.method(globalThis, 'fetch', async (url: string | URL | Request, init?: RequestInit) => {
      requestedUrl = url
      request = init
      return new Response(
        JSON.stringify({
          data: {
            workflowId: 'wf-1',
            requestId: 'factory-id',
            revision: 3,
            changed: true,
            idempotent: false,
            projection: {},
          },
        }),
        { status: 200 }
      )
    })
    const transition = {
      workflowId: 'wf-1',
      stepId: 'build',
      expectedRevision: 2,
      requestedStatus: 'completed',
      evidenceIds: ['evidence-1'],
      idempotencyKey: 'transition-1',
    }
    const result = await invoke(tool, transition)
    assert.equal(result.revision, 3)
    assert.equal(String(requestedUrl), 'http://127.0.0.1:3141/api/factory/workflows/wf-1/transitions')
    assert.deepEqual(JSON.parse(request?.body as string), {
      transition,
      execution: {
        namespaceId,
        runtimeId: 'coday-express-transitional',
        kind: 'coday-express',
        agentId: 'ProductEngineer',
        threadId: 'thread-123',
        actorId: 'benjamin.valdes',
      },
    })
  })

  it('rejects transition trust fields and maps idempotent policy and malformed responses', async () => {
    for (const field of ['requestId', 'namespaceId', 'runtimeId', 'actorId', 'agentId', 'caseId', 'threadId']) {
      let called = false
      mock.method(globalThis, 'fetch', async () => {
        called = true
        return new Response()
      })
      const result = await invoke(await exposed('request_transition'), {
        workflowId: 'wf-1',
        stepId: 'build',
        expectedRevision: 2,
        requestedStatus: 'running',
        evidenceIds: [],
        [field]: 'model',
      })
      assert.equal(result.error.code, 'INVALID_TRANSITION_REQUEST')
      assert.equal(called, false)
      mock.restoreAll()
    }
    mock.method(
      globalThis,
      'fetch',
      async () =>
        new Response(
          JSON.stringify({
            data: {
              workflowId: 'wf-1',
              requestId: 'factory-id',
              revision: 2,
              changed: false,
              idempotent: true,
              projection: {},
            },
          }),
          { status: 200 }
        )
    )
    assert.equal(
      (
        await invoke(await exposed('request_transition'), {
          workflowId: 'wf-1',
          stepId: 'build',
          expectedRevision: 2,
          requestedStatus: 'running',
          evidenceIds: [],
        })
      ).idempotent,
      true
    )
    mock.restoreAll()
    mock.method(
      globalThis,
      'fetch',
      async () =>
        new Response(JSON.stringify({ error: { code: 'ACTOR_NOT_AUTHORIZED', message: 'bounded' } }), { status: 409 })
    )
    assert.equal(
      (
        await invoke(await exposed('request_transition'), {
          workflowId: 'wf-1',
          stepId: 'build',
          expectedRevision: 2,
          requestedStatus: 'running',
          evidenceIds: [],
        })
      ).error.code,
      'ACTOR_NOT_AUTHORIZED'
    )
    mock.restoreAll()
    mock.method(globalThis, 'fetch', async () => new Response('{bad', { status: 200 }))
    assert.equal(
      (
        await invoke(await exposed('request_transition'), {
          workflowId: 'wf-1',
          stepId: 'build',
          expectedRevision: 2,
          requestedStatus: 'running',
          evidenceIds: [],
        })
      ).error.code,
      'MALFORMED_FACTORY_RESPONSE'
    )
  })

  it('rejects evidence attribution before HTTP and maps Factory errors and malformed success', async () => {
    for (const field of ['source', 'namespaceId', 'evidenceId', 'observedAt']) {
      let called = false
      mock.method(globalThis, 'fetch', async () => {
        called = true
        return new Response()
      })
      const result = await invoke(await exposed('record_agent_result'), {
        workflowId: 'wf-1',
        stepId: 'implement',
        facts: { resultCode: 'DONE' },
        [field]: 'model',
      })
      assert.equal(result.error.code, 'INVALID_EVIDENCE')
      assert.equal(called, false)
      mock.restoreAll()
    }
    mock.method(
      globalThis,
      'fetch',
      async () =>
        new Response(JSON.stringify({ error: { code: 'WORKFLOW_REMOVED', message: 'removed' } }), { status: 410 })
    )
    assert.equal(
      (
        await invoke(await exposed('record_artifact'), {
          workflowId: 'wf-1',
          stepId: 'implement',
          artifactRef: 'ref',
          artifactHash: `sha256:${'a'.repeat(64)}`,
        })
      ).error.code,
      'WORKFLOW_REMOVED'
    )
    mock.restoreAll()
    mock.method(globalThis, 'fetch', async () => new Response('{bad', { status: 200 }))
    assert.equal(
      (
        await invoke(await exposed('record_artifact'), {
          workflowId: 'wf-1',
          stepId: 'implement',
          artifactRef: 'ref',
          artifactHash: `sha256:${'a'.repeat(64)}`,
        })
      ).error.code,
      'MALFORMED_FACTORY_RESPONSE'
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

import { AgentosClient } from './agentos-client'
import { ensureTranscriptionModel } from './ensure-transcription'

/** In-memory AgentOS behind a mocked fetch: platform providers and their models only. */
function fakeAgentos(providers: Array<Record<string, any>>, models: Array<Record<string, any>>) {
  const created: Array<Record<string, any>> = []
  const fetchFn = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    const json = (data: unknown) => new Response(JSON.stringify(data), { status: 200 })
    if (url.pathname === '/api/ai-providers') return json(providers)
    const byParent = url.pathname.match(/^\/api\/ai-models\/by-parentId\/(.+)$/)
    if (byParent) return json([...models, ...created].filter((m) => m['aiProviderId'] === byParent[1]))
    if (init?.method === 'POST' && url.pathname === '/api/ai-models') {
      created.push(JSON.parse(String(init.body)))
      return json({ id: 'new' })
    }
    return new Response('not found', { status: 404 })
  }) as typeof fetch
  return { created, client: new AgentosClient('http://agentos', 'me@acme.com', fetchFn) }
}

const chat = (providerId: string, priority: number) => ({
  aiProviderId: providerId,
  alias: 'BIG',
  apiModelName: 'x',
  priority,
})

describe('ensureTranscriptionModel', () => {
  it('adds WHISPER to OpenAI at the priority of its other models when the platform has none', async () => {
    const { client, created } = fakeAgentos(
      [
        { id: 'a', name: 'anthropic', apiType: 'Anthropic' },
        { id: 'o', name: 'openai', apiType: 'OpenAI' },
      ],
      [chat('a', 20), chat('o', 10)]
    )
    expect(await ensureTranscriptionModel(client)).toBe(1)
    expect(created).toEqual([
      expect.objectContaining({ aiProviderId: 'o', alias: 'WHISPER', apiModelName: 'whisper-1', priority: 10 }),
    ])
  })

  it('does nothing when a WHISPER model already exists on any platform provider', async () => {
    const { client, created } = fakeAgentos(
      [
        { id: 'o', name: 'openai', apiType: 'OpenAI' },
        { id: 'r', name: 'requesty', apiType: 'OpenAI' },
      ],
      [chat('o', 10), chat('r', 20), { aiProviderId: 'r', alias: 'WHISPER', apiModelName: 'x', priority: 20 }]
    )
    expect(await ensureTranscriptionModel(client)).toBe(0)
    expect(created).toHaveLength(0)
  })

  it('does nothing when no platform provider has a transcription API (Anthropic alone)', async () => {
    const { client, created } = fakeAgentos([{ id: 'a', name: 'anthropic', apiType: 'Anthropic' }], [chat('a', 10)])
    expect(await ensureTranscriptionModel(client)).toBe(0)
    expect(created).toHaveLength(0)
  })
})

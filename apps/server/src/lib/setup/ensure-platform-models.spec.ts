import { AgentosClient } from './agentos-client'
import { ensurePlatformModels } from './ensure-platform-models'

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
const model = (providerId: string, alias: string, priority: number) => ({
  aiProviderId: providerId,
  alias,
  apiModelName: 'x',
  priority,
})

const ANTHROPIC = { id: 'a', name: 'anthropic', apiType: 'Anthropic' }
const OPENAI = { id: 'o', name: 'openai', apiType: 'OpenAI' }

describe('ensurePlatformModels', () => {
  it('adds `default` (the SMALL model) to every provider and WHISPER to OpenAI, at the provider priority', async () => {
    const { client, created } = fakeAgentos([ANTHROPIC, OPENAI], [chat('a', 20), chat('o', 10)])
    expect(await ensurePlatformModels(client)).toBe(3)
    expect(created).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          aiProviderId: 'a',
          alias: 'default',
          apiModelName: 'claude-haiku-5-5',
          priority: 20,
        }),
        expect.objectContaining({ aiProviderId: 'o', alias: 'default', apiModelName: 'gpt-6-luna', priority: 10 }),
        expect.objectContaining({ aiProviderId: 'o', alias: 'WHISPER', apiModelName: 'whisper-1', priority: 10 }),
      ])
    )
    expect(created).toHaveLength(3)
  })

  it('gives `default` the pricing and limits of the SMALL model', async () => {
    const { client, created } = fakeAgentos([ANTHROPIC], [chat('a', 20)])
    await ensurePlatformModels(client)
    expect(created[0]).toMatchObject({
      alias: 'default',
      contextWindow: 1_000_000,
      maxCompletionTokens: 128_000,
      pricing: { inputMTokens: 0.1, outputMTokens: 0.5 },
    })
  })

  it('does nothing when both models already exist on the platform', async () => {
    const { client, created } = fakeAgentos(
      [OPENAI, { id: 'r', name: 'requesty', apiType: 'OpenAI' }],
      [chat('o', 10), model('o', 'DEFAULT', 10), model('r', 'WHISPER', 20)]
    )
    expect(await ensurePlatformModels(client)).toBe(0)
    expect(created).toHaveLength(0)
  })

  it('adds only the one that is missing', async () => {
    const { client, created } = fakeAgentos([ANTHROPIC, OPENAI], [chat('a', 20), model('a', 'default', 20)])
    expect(await ensurePlatformModels(client)).toBe(1)
    expect(created).toEqual([expect.objectContaining({ aiProviderId: 'o', alias: 'WHISPER' })])
  })

  it('adds no WHISPER when no platform provider has a transcription API (Anthropic alone)', async () => {
    const { client, created } = fakeAgentos([ANTHROPIC], [chat('a', 10)])
    expect(await ensurePlatformModels(client)).toBe(1)
    expect(created).toEqual([expect.objectContaining({ alias: 'default' })])
  })

  it('ignores platform providers that are not in the catalogue', async () => {
    const { client, created } = fakeAgentos([{ id: 'm', name: 'mistral', apiType: 'OpenAI' }], [chat('m', 10)])
    expect(await ensurePlatformModels(client)).toBe(0)
    expect(created).toHaveLength(0)
  })
})

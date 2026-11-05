import { AgentosClient } from './agentos-client'
import { ApplyValidationError, DEFAULT_AGENT_NAME, runApply, validateApplyInput } from './apply'
import {
  CatalogueProvider,
  DEFAULT_MODEL_ALIAS,
  getCatalogueProvider,
  MODEL_ALIASES,
  MODEL_CATALOGUE,
  priorityForRank,
  TRANSCRIPTION_ALIAS,
} from './model-catalogue'
import { ApplyTracker } from './progress'

/** Scope of a provider: platform when it has neither a namespace nor a user. */
const scopeOf = (provider: Record<string, any>): string =>
  `${provider['namespaceId'] ?? 'platform'}/${provider['userId'] ?? 'platform'}`

/** In-memory AgentOS behind a mocked fetch: just enough REST to run the apply flow. */
class FakeAgentos {
  providers: Array<Record<string, any>> = []
  models: Array<Record<string, any>> = []
  namespaces: Array<Record<string, any>> = []
  agents: Array<Record<string, any>> = [{ name: 'Coday', enabled: true }]
  /** The calling user. A non-admin gets a 403 on platform providers, like the real AgentOS. */
  me: Record<string, any> = { id: 'user-1', externalId: 'me@acme.com', isAdmin: true }
  calls: Array<{ method: string; path: string; body?: any; headers: Record<string, string> }> = []
  /** Number of GET namespace lookups answered with [] before the namespace shows up. */
  namespaceDelay = 0
  failNext: { method: string; path: RegExp } | null = null
  private seq = 0

  readonly fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    this.calls.push({ method, path: url.pathname, body, headers: init?.headers as Record<string, string> })
    const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })

    if (this.failNext && this.failNext.method === method && this.failNext.path.test(url.pathname)) {
      this.failNext = null
      return new Response('boom', { status: 500 })
    }
    const p = url.pathname
    if (method === 'GET' && p === '/api/ai-providers') {
      // Like AgentOS: `namespaceId=none` lists the platform providers, no parameter lists the caller's own.
      const platform = url.searchParams.get('namespaceId') === 'none'
      return json(this.providers.filter((x) => (platform ? scopeOf(x) === scopeOf({}) : x['userId'] === this.me['id'])))
    }
    if (method === 'POST' && p === '/api/ai-providers') {
      if (!this.me['isAdmin']) return new Response('Platform-level AiProvider requires Super Admin', { status: 403 })
      if (this.providers.some((x) => x['name'] === body.name && scopeOf(x) === scopeOf(body))) {
        return new Response('AiProvider already exists', { status: 409 })
      }
      const created = { ...body, id: `prov-${++this.seq}` }
      this.providers.push(created)
      return json(created, 201)
    }
    let m = p.match(/^\/api\/ai-providers\/(.+)$/)
    if (method === 'PUT' && m) {
      const i = this.providers.findIndex((x) => x['id'] === m![1])
      this.providers[i] = { ...body, id: m[1] }
      return json(this.providers[i])
    }
    m = p.match(/^\/api\/ai-models\/by-parentId\/(.+)$/)
    if (method === 'GET' && m) return json(this.models.filter((x) => x['aiProviderId'] === m![1]))
    if (method === 'POST' && p === '/api/ai-models') {
      const created = { ...body, id: `model-${++this.seq}` }
      this.models.push(created)
      return json(created, 201)
    }
    m = p.match(/^\/api\/ai-models\/(.+)$/)
    if (method === 'PUT' && m) {
      const i = this.models.findIndex((x) => x['id'] === m![1])
      this.models[i] = { ...body, id: m[1] }
      return json(this.models[i])
    }
    if (method === 'POST' && p === '/api/namespaces/by-external-ids') {
      if (this.namespaceDelay > 0) {
        this.namespaceDelay--
        return json([])
      }
      return json(this.namespaces.filter((n) => body.includes(n['externalId'])))
    }
    m = p.match(/^\/api\/namespaces\/(.+)$/)
    if (method === 'PUT' && m) {
      const i = this.namespaces.findIndex((x) => x['id'] === m![1])
      this.namespaces[i] = { ...body, id: m[1] }
      return json(this.namespaces[i])
    }
    if (method === 'GET' && p === '/api/users/me') return json(this.me)
    if (method === 'POST' && p === '/api/agent-configs/search') return json(this.agents)
    return new Response('not found', { status: 404 })
  }) as typeof fetch

  count(method: string, pattern: RegExp): number {
    return this.calls.filter((c) => c.method === method && pattern.test(c.path)).length
  }
}

const EMAIL = 'me@acme.com'
const input = (providers: Array<{ id: string; rank: number }>) => ({
  providers: providers.map((p) => ({ ...p, apiKey: `sk-secret-${p.id}-key` })),
  sharedExchangePath: '/home/me/Coday',
})

function setup(fake = new FakeAgentos()) {
  fake.namespaces.push({ id: 'ns-1', name: 'me', externalId: `user:${EMAIL}`, defaultAgentName: 'Coday' })
  const tracker = new ApplyTracker()
  const mkdir = jest.fn()
  const deps = {
    client: new AgentosClient('http://agentos', EMAIL, fake.fetch),
    waitForAgentos: async () => true,
    tracker,
    userEmail: EMAIL,
    sleep: async () => undefined,
    mkdir,
    namespaceAttempts: 5,
    namespaceIntervalMs: 0,
  }
  return { fake, tracker, deps, mkdir }
}

describe('runApply', () => {
  it('creates platform providers, 4 aliased models each, and configures the personal namespace', async () => {
    const { fake, tracker, deps, mkdir } = setup()
    const done = jest.fn()

    const ok = await runApply(
      input([
        { id: 'anthropic', rank: 1 },
        { id: 'openai', rank: 2 },
      ]),
      deps,
      done
    )

    expect(ok).toBe(true)
    expect(done).toHaveBeenCalledTimes(1)
    expect(tracker.get()).toMatchObject({ status: 'done', namespaceId: 'ns-1' })
    expect(fake.providers.map((p) => p['name'])).toEqual(['anthropic', 'openai'])
    for (const p of fake.providers) {
      expect(p['namespaceId']).toBeNull()
      expect(p['userId']).toBeNull()
    }
    expect(fake.providers[0]).toMatchObject({ apiType: 'Anthropic', apiKey: 'sk-secret-anthropic-key' })
    expect(fake.models).toHaveLength(11) // 4 chat aliases + default each, + WHISPER for openai
    for (const provider of fake.providers) {
      const aliases = fake.models.filter((m) => m['aiProviderId'] === provider['id']).map((m) => m['alias'])
      const chat = [...MODEL_ALIASES, DEFAULT_MODEL_ALIAS]
      const expected = provider['name'] === 'openai' ? [...chat, TRANSCRIPTION_ALIAS] : chat
      expect(aliases.sort()).toEqual(expected.sort())
    }
    expect(mkdir).toHaveBeenCalledWith('/home/me/Coday')
    expect(fake.namespaces[0]).toMatchObject({
      sharedExchangePath: '/home/me/Coday',
      defaultAgentName: DEFAULT_AGENT_NAME,
    })
  })

  it('calls AgentOS as the first-run user', async () => {
    const { fake, deps } = setup()
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    expect(fake.calls.length).toBeGreaterThan(0)
    for (const call of fake.calls) expect(call.headers['X-External-User-Id']).toBe(EMAIL)
  })

  it('derives the model priority from the rank: rank 1 is highest', async () => {
    const { fake, deps } = setup()
    await runApply(
      input([
        { id: 'openai', rank: 2 },
        { id: 'anthropic', rank: 1 },
      ]),
      deps
    )
    const priorityOf = (providerName: string) => {
      const provider = fake.providers.find((p) => p['name'] === providerName)
      return fake.models.find((m) => m['aiProviderId'] === provider?.['id'])?.['priority']
    }
    expect(priorityOf('anthropic')).toBe(priorityForRank(1, 2))
    expect(priorityOf('openai')).toBe(priorityForRank(2, 2))
    expect(priorityOf('anthropic')).toBeGreaterThan(priorityOf('openai'))
  })

  it('uses the catalogue model names, limits and Requesty base URL', async () => {
    const { fake, deps } = setup()
    await runApply(input([{ id: 'requesty', rank: 1 }]), deps)
    expect(fake.providers[0]).toMatchObject({ apiType: 'OpenAI', baseUrl: 'https://router.eu.requesty.ai' })
    const big = fake.models.find((m) => m['alias'] === 'BIG')
    expect(big).toMatchObject({
      apiModelName: MODEL_CATALOGUE.requesty.models.BIG.apiModelName,
      contextWindow: MODEL_CATALOGUE.requesty.models.BIG.contextWindow,
      maxCompletionTokens: MODEL_CATALOGUE.requesty.models.BIG.maxCompletionTokens,
    })
  })

  it('is idempotent: a second run creates nothing new', async () => {
    const { fake, deps } = setup()
    const run = () =>
      runApply(
        input([
          { id: 'anthropic', rank: 1 },
          { id: 'openai', rank: 2 },
        ]),
        deps
      )
    await run()
    const providers = fake.providers.length
    const models = fake.models.length

    expect(await run()).toBe(true)

    expect(fake.providers).toHaveLength(providers)
    expect(fake.models).toHaveLength(models)
    expect(fake.count('POST', /^\/api\/ai-providers$/)).toBe(2)
    expect(fake.count('POST', /^\/api\/ai-models$/)).toBe(11)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(0)
  })

  it('converges after a partial failure (models failed, retry completes them)', async () => {
    const { fake, tracker, deps } = setup()
    fake.failNext = { method: 'POST', path: /^\/api\/ai-models$/ }

    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(false)
    expect(tracker.get().steps.providers.state).toBe('done')
    expect(tracker.get().steps.models.state).toBe('error')
    expect(fake.providers).toHaveLength(1)

    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(true)
    expect(fake.providers).toHaveLength(1) // matched by name, not duplicated
    expect(fake.models).toHaveLength(5)
  })

  it('matches existing providers by name case-insensitively and refreshes their key', async () => {
    const { fake, deps } = setup()
    fake.providers.push({ id: 'old', name: 'Anthropic', apiType: 'Anthropic', namespaceId: null, userId: null })
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    expect(fake.providers).toHaveLength(1)
    expect(fake.providers[0]).toMatchObject({ id: 'old', apiKey: 'sk-secret-anthropic-key' })
  })

  it('creates the platform provider next to a personal one of the same name, leaving it untouched', async () => {
    const { fake, deps } = setup()
    const personal = {
      id: 'mine',
      name: 'anthropic',
      apiType: 'Anthropic',
      apiKey: 'k',
      namespaceId: null,
      userId: 'user-1',
    }
    fake.providers.push({ ...personal })

    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(true)

    expect(fake.providers).toHaveLength(2)
    expect(fake.providers.find((p) => p['id'] === 'mine')).toEqual(personal)
    const platform = fake.providers.find((p) => p['id'] !== 'mine')
    expect(platform).toMatchObject({ namespaceId: null, userId: null, apiKey: 'sk-secret-anthropic-key' })
    expect(fake.models.every((m) => m['aiProviderId'] === platform?.['id'])).toBe(true)
  })

  it('moves an existing model to the new priority when the ranking changed', async () => {
    const { fake, deps } = setup()
    const whisperOf = (name: string) => {
      const provider = fake.providers.find((p) => p['name'] === name)
      return fake.models.find((m) => m['aiProviderId'] === provider?.['id'] && m['alias'] === TRANSCRIPTION_ALIAS)
    }
    await runApply(
      input([
        { id: 'anthropic', rank: 1 },
        { id: 'openai', rank: 2 },
      ]),
      deps
    )
    expect(whisperOf('openai')?.['priority']).toBe(priorityForRank(2, 2))
    await runApply(
      input([
        { id: 'anthropic', rank: 2 },
        { id: 'openai', rank: 1 },
      ]),
      deps
    )
    const priorityOf = (name: string) => {
      const provider = fake.providers.find((p) => p['name'] === name)
      return fake.models.find((m) => m['aiProviderId'] === provider?.['id'] && m['alias'] === 'BIG')?.['priority']
    }
    expect(priorityOf('openai')).toBeGreaterThan(priorityOf('anthropic'))
    expect(whisperOf('openai')?.['priority']).toBe(priorityForRank(1, 2))
  })

  it('retries the personal namespace lookup: it is created asynchronously', async () => {
    const { fake, tracker, deps } = setup()
    fake.namespaceDelay = 3
    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(true)
    expect(fake.count('POST', /by-external-ids/)).toBe(4)
    expect(tracker.get().namespaceId).toBe('ns-1')
  })

  it('fails with a clear message when the personal namespace never appears', async () => {
    const { fake, tracker, deps } = setup()
    fake.namespaceDelay = 99
    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(false)
    expect(tracker.get().steps.namespace.state).toBe('error')
    expect(tracker.get().error).toMatch(/personal namespace/)
  })

  it('skips the namespace PUT when it is already configured', async () => {
    const { fake, deps } = setup()
    fake.namespaces[0] = { ...fake.namespaces[0], sharedExchangePath: '/home/me/Coday', defaultAgentName: 'Coday' }
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    expect(fake.count('PUT', /^\/api\/namespaces\//)).toBe(0)
  })

  it('does not take a disabled Coday for the default one', async () => {
    const { fake, tracker, deps } = setup()
    fake.agents = [{ name: 'Coday', enabled: false }]
    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(false)
    expect(tracker.get().steps.agent.state).toBe('error')
  })

  it('fails when the default agent is not deployed', async () => {
    const { fake, tracker, deps } = setup()
    fake.agents = []
    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(false)
    expect(tracker.get().steps.agent.state).toBe('error')
    expect(tracker.get().steps.namespace.state).toBe('done')
  })

  it('does not mark the setup done on failure', async () => {
    const { fake, deps } = setup()
    fake.agents = []
    const done = jest.fn()
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps, done)
    expect(done).not.toHaveBeenCalled()
  })

  it('gives up cleanly when AgentOS never starts', async () => {
    const { tracker, deps } = setup()
    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), { ...deps, waitForAgentos: async () => false })).toBe(
      false
    )
    expect(tracker.get().error).toMatch(/AgentOS did not start/)
  })

  it('never lets an API key reach the recorded error', async () => {
    const { fake, tracker, deps } = setup()
    fake.failNext = { method: 'POST', path: /^\/api\/ai-providers$/ }
    const originalFetch = fake.fetch
    // An upstream error echoing the request body (keys included) must be scrubbed.
    const echoing = (async (url: any, init?: RequestInit) => {
      const res = await originalFetch(url, init)
      return res.status === 500 ? new Response(String(init?.body), { status: 500 }) : res
    }) as typeof fetch
    const echoDeps = { ...deps, client: new AgentosClient('http://agentos', EMAIL, echoing) }

    await runApply(input([{ id: 'anthropic', rank: 1 }]), echoDeps)

    expect(tracker.get().error).toBeDefined()
    expect(tracker.get().error).not.toContain('sk-secret-anthropic-key')
    expect(JSON.stringify(tracker.get())).not.toContain('sk-secret')
  })
})

describe('runApply transcription model', () => {
  const whisperModels = (fake: FakeAgentos) => fake.models.filter((m) => m['alias'] === TRANSCRIPTION_ALIAS)
  const providerId = (fake: FakeAgentos, name: string) => fake.providers.find((p) => p['name'] === name)?.['id']

  it('creates a platform WHISPER model named whisper-1 for OpenAI, with the provider priority and no pricing', async () => {
    const { fake, deps } = setup()
    expect(await runApply(input([{ id: 'openai', rank: 1 }]), deps)).toBe(true)
    const whisper = whisperModels(fake)
    expect(whisper).toHaveLength(1)
    expect(whisper[0]).toMatchObject({
      aiProviderId: providerId(fake, 'openai'),
      apiModelName: 'whisper-1',
      alias: 'WHISPER',
      priority: priorityForRank(1, 1),
    })
    expect(whisper[0]).not.toHaveProperty('pricing')
    expect(whisper[0]).not.toHaveProperty('contextWindow')
    expect(whisper[0]).not.toHaveProperty('maxCompletionTokens')
  })

  it('creates openai/whisper-1 for Requesty', async () => {
    const { fake, deps } = setup()
    await runApply(input([{ id: 'requesty', rank: 1 }]), deps)
    expect(whisperModels(fake)).toHaveLength(1)
    expect(whisperModels(fake)[0]).toMatchObject({
      aiProviderId: providerId(fake, 'requesty'),
      apiModelName: 'openai/whisper-1',
    })
  })

  it('creates none for Anthropic alone, which has no transcription API', async () => {
    const { fake, deps } = setup()
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    expect(whisperModels(fake)).toHaveLength(0)
    expect(fake.models).toHaveLength(5)
  })

  it('gives each provider its own WHISPER at its own priority: the best-ranked one wins', async () => {
    const { fake, deps } = setup()
    await runApply(
      input([
        { id: 'anthropic', rank: 1 },
        { id: 'requesty', rank: 2 },
        { id: 'openai', rank: 3 },
      ]),
      deps
    )
    const byProvider = (name: string) => whisperModels(fake).find((m) => m['aiProviderId'] === providerId(fake, name))
    expect(whisperModels(fake)).toHaveLength(2)
    expect(byProvider('requesty')?.['priority']).toBe(priorityForRank(2, 3))
    expect(byProvider('openai')?.['priority']).toBe(priorityForRank(3, 3))
    expect(byProvider('requesty')?.['priority']).toBeGreaterThan(byProvider('openai')?.['priority'])
  })

  it('creates it once and updates it only when something differs', async () => {
    const { fake, deps } = setup()
    const run = () => runApply(input([{ id: 'openai', rank: 1 }]), deps)
    await run()
    await run()
    expect(whisperModels(fake)).toHaveLength(1)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(0)

    whisperModels(fake)[0]!['apiModelName'] = 'old-name'
    await run()
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(1)
    expect(whisperModels(fake)[0]).toMatchObject({ apiModelName: 'whisper-1' })
  })

  it('does not let the pricing refresh touch it: a correct WHISPER is not rewritten when chat prices are stale', async () => {
    const { fake, deps } = setup()
    const run = () => runApply(input([{ id: 'openai', rank: 1 }]), deps)
    await run()
    for (const model of fake.models) if (model['alias'] !== TRANSCRIPTION_ALIAS) delete model['pricing']
    await run()
    // 4 chat models and `default` refreshed, WHISPER left alone
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(5)
  })
})

describe('runApply default model', () => {
  const defaultOf = (fake: FakeAgentos, name: string) => {
    const provider = fake.providers.find((p) => p['name'] === name)
    return fake.models.find((m) => m['aiProviderId'] === provider?.['id'] && m['alias'] === DEFAULT_MODEL_ALIAS)
  }

  it('gives every provider a `default` model that is a copy of its SMALL one, pricing and limits included', async () => {
    const { fake, deps } = setup()
    await runApply(
      input([
        { id: 'anthropic', rank: 1 },
        { id: 'requesty', rank: 2 },
      ]),
      deps
    )
    for (const id of ['anthropic', 'requesty'] as const) {
      const small = MODEL_CATALOGUE[id].models.SMALL
      expect(defaultOf(fake, id)).toMatchObject({
        apiModelName: small.apiModelName,
        pricing: small.pricing,
        contextWindow: small.contextWindow,
        maxCompletionTokens: small.maxCompletionTokens,
      })
    }
  })

  it('lets the best-ranked provider win: its `default` has the highest priority', async () => {
    const { fake, deps } = setup()
    await runApply(
      input([
        { id: 'openai', rank: 2 },
        { id: 'anthropic', rank: 1 },
      ]),
      deps
    )
    expect(defaultOf(fake, 'anthropic')?.['priority']).toBe(priorityForRank(1, 2))
    expect(defaultOf(fake, 'anthropic')?.['priority']).toBeGreaterThan(defaultOf(fake, 'openai')?.['priority'])
  })

  it('creates it once, and follows the SMALL model when the catalogue moves it', async () => {
    const { fake, deps } = setup()
    const run = () => runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    await run()
    await run()
    expect(fake.models.filter((m) => m['alias'] === DEFAULT_MODEL_ALIAS)).toHaveLength(1)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(0)

    defaultOf(fake, 'anthropic')!['apiModelName'] = 'old-small'
    await run()
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(1)
    expect(defaultOf(fake, 'anthropic')?.['apiModelName']).toBe(MODEL_CATALOGUE.anthropic.models.SMALL.apiModelName)
  })
})

describe('runApply pricing', () => {
  it('sends the catalogue pricing with every created model', async () => {
    const { fake, deps } = setup()
    await runApply(
      input([
        { id: 'anthropic', rank: 1 },
        { id: 'requesty', rank: 2 },
      ]),
      deps
    )
    for (const provider of fake.providers) {
      const catalogue = getCatalogueProvider(provider['name'] as string) as CatalogueProvider
      for (const alias of MODEL_ALIASES) {
        const model = fake.models.find((m) => m['aiProviderId'] === provider['id'] && m['alias'] === alias)
        expect(model?.['pricing']).toEqual(catalogue.models[alias].pricing)
      }
    }
  })

  it('adds the pricing to models created before it existed, once, then leaves them alone', async () => {
    const { fake, deps } = setup()
    const run = () => runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    await run()
    for (const model of fake.models) delete model['pricing']

    expect(await run()).toBe(true)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(5)
    expect(fake.models.every((m) => m['pricing'])).toBe(true)

    expect(await run()).toBe(true)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(5)
  })

  it('reads the nulls AgentOS answers for unset rates as no rate: right prices are not rewritten', async () => {
    const { fake, deps } = setup()
    const run = () => runApply(input([{ id: 'requesty', rank: 1 }]), deps)
    await run()
    for (const model of fake.models) model['pricing'] = { ...model['pricing'], cacheWrite: null }

    expect(await run()).toBe(true)
    expect(fake.count('PUT', /^\/api\/ai-models\//)).toBe(0)
  })
})

describe('runApply as the first-run user', () => {
  it('starts with a call as that user, so AgentOS creates the user and its personal namespace first', async () => {
    const { fake, deps } = setup()
    await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)
    expect(fake.calls[0]).toMatchObject({ method: 'GET', path: '/api/users/me' })
  })

  it('says why when the user is not an administrator of an AgentOS that already had users', async () => {
    const { fake, tracker, deps } = setup()
    fake.me['isAdmin'] = false

    expect(await runApply(input([{ id: 'anthropic', rank: 1 }]), deps)).toBe(false)

    expect(tracker.get().error).toMatch(/not an administrator/)
    expect(tracker.get().error).toContain(EMAIL)
    expect(tracker.get().steps.providers.state).toBe('error')
    expect(fake.providers).toHaveLength(0)
  })
})

describe('validateApplyInput', () => {
  const valid = {
    providers: [{ id: 'anthropic', apiKey: 'sk-ant-12345678', rank: 1 }],
    sharedExchangePath: '/home/me/Coday',
  }

  it('accepts a valid request and trims the key', () => {
    const parsed = validateApplyInput({
      ...valid,
      providers: [{ ...valid.providers[0], apiKey: '  sk-ant-12345678  ' }],
    })
    expect(parsed.providers[0]?.apiKey).toBe('sk-ant-12345678')
  })

  it.each([
    ['no body', undefined],
    ['no provider', { ...valid, providers: [] }],
    ['an unknown provider', { ...valid, providers: [{ id: 'nope', apiKey: 'sk-12345678', rank: 1 }] }],
    ['a duplicate provider', { ...valid, providers: [valid.providers[0], valid.providers[0]] }],
    ['a short key', { ...valid, providers: [{ id: 'openai', apiKey: 'x', rank: 1 }] }],
    ['a missing rank', { ...valid, providers: [{ id: 'openai', apiKey: 'sk-12345678' }] }],
    ['a relative folder', { ...valid, sharedExchangePath: 'Coday' }],
    ['no folder', { providers: valid.providers }],
  ])('rejects %s', (_label, body) => {
    expect(() => validateApplyInput(body)).toThrow(ApplyValidationError)
  })

  it.each([
    ['the Coday folder itself', '/home/me/.coday'],
    ['a folder inside it', '/home/me/.coday/agentos/data'],
    ['a folder that contains it', '/home/me'],
  ])('refuses %s as the files folder: the agents would reach the data and keys of Coday', (_label, folder) => {
    expect(() => validateApplyInput({ ...valid, sharedExchangePath: folder }, '/home/me/.coday')).toThrow(
      ApplyValidationError
    )
  })

  it('accepts a folder next to the Coday folder', () => {
    for (const folder of ['/home/me/Coday', '/home/me/.coday-files']) {
      expect(validateApplyInput({ ...valid, sharedExchangePath: folder }, '/home/me/.coday').sharedExchangePath).toBe(
        folder
      )
    }
  })
})

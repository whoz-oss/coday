import {
  CATALOGUE_PROVIDER_IDS,
  getCatalogueProvider,
  MODEL_ALIASES,
  MODEL_CATALOGUE,
  priorityForRank,
} from './model-catalogue'

describe('model catalogue shape', () => {
  it('offers anthropic, openai and requesty', () => {
    expect([...CATALOGUE_PROVIDER_IDS].sort()).toEqual(['anthropic', 'openai', 'requesty'])
  })

  it.each(CATALOGUE_PROVIDER_IDS)('%s has the tier aliases and only Requesty adds default', (id) => {
    const provider = MODEL_CATALOGUE[id]
    const aliases = id === 'requesty' ? [...MODEL_ALIASES, 'default'] : [...MODEL_ALIASES]
    expect(Object.keys(provider.models).sort()).toEqual(aliases.sort())
    expect(MODEL_ALIASES).toHaveLength(4)
  })

  it.each(CATALOGUE_PROVIDER_IDS)('%s models have distinct, non-empty api names and sane limits', (id) => {
    const models = MODEL_ALIASES.map((alias) => MODEL_CATALOGUE[id].models[alias])
    const names = models.map((m) => m.apiModelName)
    expect(names.every((n) => n.length > 0)).toBe(true)
    expect(new Set(names).size).toBe(names.length)
    for (const m of models) {
      expect(m.contextWindow).toBeGreaterThan(0)
      if (id === 'requesty' && m.apiModelName.startsWith('policy/')) {
        expect(m.maxCompletionTokens).toBeUndefined()
      } else {
        expect(m.maxCompletionTokens).toBeGreaterThan(0)
      }
    }
  })

  it('declares the API type and base URL AgentOS needs', () => {
    expect(MODEL_CATALOGUE.anthropic.apiType).toBe('Anthropic')
    expect(MODEL_CATALOGUE.openai.apiType).toBe('OpenAI')
    expect(MODEL_CATALOGUE.requesty.apiType).toBe('OpenAI')
    expect(MODEL_CATALOGUE.requesty.baseUrl).toBe('https://router.eu.requesty.ai')
  })

  it('routes Requesty tiers through policies, preserving FRONTIER and direct providers', () => {
    const models = MODEL_CATALOGUE.requesty.models
    expect(models.SMALL.apiModelName).toBe('policy/claude-haiku')
    expect(models.default).toEqual(models.SMALL)
    expect(models.BIG.apiModelName).toBe('policy/claude-sonnet')
    expect(models.BIGGEST.apiModelName).toBe('policy/claude-opus')
    expect(models.FRONTIER.apiModelName).toBe('anthropic/claude-fable-5.1')
    expect(MODEL_CATALOGUE.anthropic.models.SMALL.apiModelName).toBe('claude-haiku-5-5')
    expect(MODEL_CATALOGUE.anthropic.models.BIG.apiModelName).toBe('claude-sonnet-5-5')
    expect(MODEL_CATALOGUE.anthropic.models.BIGGEST.apiModelName).toBe('claude-opus-5-5')
    expect(MODEL_CATALOGUE.openai.models.BIG.apiModelName).toBe('gpt-6.1-terra')
    for (const id of ['anthropic', 'openai'] as const) {
      for (const m of Object.values(MODEL_CATALOGUE[id].models)) expect(m.apiModelName).not.toContain('/')
    }
  })

  it('uses the provider id as the AgentOS provider name (the idempotency key)', () => {
    for (const id of CATALOGUE_PROVIDER_IDS) expect(MODEL_CATALOGUE[id].name).toBe(id)
    expect(getCatalogueProvider('nope')).toBeUndefined()
  })
})

describe('model pricing', () => {
  it.each(CATALOGUE_PROVIDER_IDS)('%s prices every model, with a cache read cheaper than a fresh input', (id) => {
    for (const { pricing } of Object.values(MODEL_CATALOGUE[id].models)) {
      expect(pricing.inputMTokens).toBeGreaterThan(0)
      expect(pricing.outputMTokens).toBeGreaterThan(pricing.inputMTokens)
      expect(pricing.cacheRead).toBeGreaterThan(0)
      expect(pricing.cacheRead).toBeLessThan(pricing.inputMTokens)
    }
  })

  it.each(CATALOGUE_PROVIDER_IDS)('%s goes from the priciest alias (FRONTIER) down to the cheapest (SMALL)', (id) => {
    const inputs = MODEL_ALIASES.map((alias) => MODEL_CATALOGUE[id].models[alias].pricing.inputMTokens)
    expect(inputs).toEqual([...inputs].sort((a, b) => b - a))
  })

  it('retains the SMALL YAML estimates for Requesty Haiku and default', () => {
    const small = MODEL_CATALOGUE.requesty.models.SMALL
    expect(small.contextWindow).toBe(200_000)
    expect(small.pricing).toEqual({ inputMTokens: 1, outputMTokens: 5, cacheRead: 0.1, cacheWrite: 1.25 })
    expect(MODEL_CATALOGUE.requesty.models.default).toEqual(small)
  })

  it('only gives usable cache write rates to Anthropic, except the retained Requesty Haiku estimate', () => {
    for (const id of CATALOGUE_PROVIDER_IDS) {
      for (const model of Object.values(MODEL_CATALOGUE[id].models)) {
        const retainedHaiku = id === 'requesty' && model.apiModelName === 'policy/claude-haiku'
        expect(model.pricing.cacheWrite !== undefined).toBe(MODEL_CATALOGUE[id].apiType === 'Anthropic' || retainedHaiku)
      }
    }
  })
})

describe('priorityForRank', () => {
  it('gives rank 1 the highest priority', () => {
    expect(priorityForRank(1, 3)).toBeGreaterThan(priorityForRank(2, 3))
    expect(priorityForRank(2, 3)).toBeGreaterThan(priorityForRank(3, 3))
  })

  it('clamps out-of-range ranks', () => {
    expect(priorityForRank(0, 2)).toBe(priorityForRank(1, 2))
    expect(priorityForRank(9, 2)).toBe(priorityForRank(2, 2))
  })

  it('stays positive for a single provider', () => {
    expect(priorityForRank(1, 1)).toBeGreaterThan(0)
  })
})

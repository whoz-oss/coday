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

  it.each(CATALOGUE_PROVIDER_IDS)('%s has exactly the 4 aliases FRONTIER, BIGGEST, BIG, SMALL', (id) => {
    const provider = MODEL_CATALOGUE[id]
    expect(Object.keys(provider.models).sort()).toEqual([...MODEL_ALIASES].sort())
    expect(MODEL_ALIASES).toHaveLength(4)
  })

  it.each(CATALOGUE_PROVIDER_IDS)('%s models have distinct, non-empty api names and sane limits', (id) => {
    const models = Object.values(MODEL_CATALOGUE[id].models)
    const names = models.map((m) => m.apiModelName)
    expect(names.every((n) => n.length > 0)).toBe(true)
    expect(new Set(names).size).toBe(names.length)
    for (const m of models) {
      expect(m.contextWindow).toBeGreaterThan(0)
      expect(m.maxCompletionTokens).toBeGreaterThan(0)
    }
  })

  it('declares the API type and base URL AgentOS needs', () => {
    expect(MODEL_CATALOGUE.anthropic.apiType).toBe('Anthropic')
    expect(MODEL_CATALOGUE.openai.apiType).toBe('OpenAI')
    expect(MODEL_CATALOGUE.requesty.apiType).toBe('OpenAI')
    expect(MODEL_CATALOGUE.requesty.baseUrl).toBe('https://router.requesty.ai/v1')
  })

  it('prefixes every Requesty model with its lab, and only those', () => {
    for (const m of Object.values(MODEL_CATALOGUE.requesty.models))
      expect(m.apiModelName).toMatch(/^(anthropic|openai)\//)
    for (const id of ['anthropic', 'openai'] as const) {
      for (const m of Object.values(MODEL_CATALOGUE[id].models)) expect(m.apiModelName).not.toContain('/')
    }
  })

  it('uses the provider id as the AgentOS provider name (the idempotency key)', () => {
    for (const id of CATALOGUE_PROVIDER_IDS) expect(MODEL_CATALOGUE[id].name).toBe(id)
    expect(getCatalogueProvider('nope')).toBeUndefined()
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

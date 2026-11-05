import {
  CATALOGUE_PROVIDER_IDS,
  getCatalogueProvider,
  MODEL_ALIASES,
  MODEL_CATALOGUE,
  priorityForRank,
  TRANSCRIPTION_ALIAS,
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
    expect(MODEL_CATALOGUE.requesty.baseUrl).toBe('https://router.eu.requesty.ai')
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

describe('transcription model', () => {
  it('is whisper-1 for OpenAI, openai/whisper-1 for Requesty, and absent for Anthropic', () => {
    expect(MODEL_CATALOGUE.openai.transcription).toMatchObject({ alias: 'WHISPER', apiModelName: 'whisper-1' })
    expect(MODEL_CATALOGUE.requesty.transcription).toMatchObject({
      alias: 'WHISPER',
      apiModelName: 'openai/whisper-1',
    })
    expect(MODEL_CATALOGUE.anthropic.transcription).toBeUndefined()
  })

  it('stays out of the chat aliases and carries no pricing nor limits', () => {
    expect(TRANSCRIPTION_ALIAS).toBe('WHISPER')
    expect(MODEL_ALIASES).not.toContain(TRANSCRIPTION_ALIAS)
    for (const id of CATALOGUE_PROVIDER_IDS) {
      const transcription = MODEL_CATALOGUE[id].transcription
      if (!transcription) continue
      expect(transcription.description.length).toBeGreaterThan(0)
      expect(transcription).not.toHaveProperty('pricing')
      expect(transcription).not.toHaveProperty('contextWindow')
      expect(transcription).not.toHaveProperty('maxCompletionTokens')
    }
  })
})

describe('model pricing', () => {
  it.each(CATALOGUE_PROVIDER_IDS)('%s prices every model, with a cache read cheaper than a fresh input', (id) => {
    for (const alias of MODEL_ALIASES) {
      const { pricing } = MODEL_CATALOGUE[id].models[alias]
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

  it('only gives a cache write rate where AgentOS can use it (the Anthropic API type)', () => {
    for (const id of CATALOGUE_PROVIDER_IDS) {
      for (const model of Object.values(MODEL_CATALOGUE[id].models)) {
        expect(model.pricing.cacheWrite !== undefined).toBe(MODEL_CATALOGUE[id].apiType === 'Anthropic')
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

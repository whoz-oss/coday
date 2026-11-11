import { AiModel } from '@whoz-oss/agentos-api-client'
import { buildModelOptions } from './model-options'

const model = (overrides: Partial<AiModel>): AiModel => ({
  aiProviderId: 'p-1',
  apiModelName: 'gpt-4o',
  priority: 0,
  ...overrides,
})

describe('buildModelOptions', () => {
  it('lists aliases first, labelled with the API model they resolve to', () => {
    const { aliases } = buildModelOptions([model({ alias: 'BIG', apiModelName: 'gpt-4o' })], null)
    expect(aliases).toEqual([{ value: 'BIG', label: 'BIG (gpt-4o)' }])
  })

  it('lists the API model names no alias takes', () => {
    const { apiNames } = buildModelOptions([model({ apiModelName: 'claude-sonnet' })], null)
    expect(apiNames).toEqual([{ value: 'claude-sonnet', label: 'claude-sonnet' }])
  })

  it('does not offer an API name that an alias shadows, since it would resolve to another model', () => {
    // The server tries aliases first: `gpt-4o` as a name would reach the model aliased `gpt-4o`.
    const { aliases, apiNames } = buildModelOptions(
      [model({ alias: 'gpt-4o', apiModelName: 'gpt-4.1' }), model({ apiModelName: 'gpt-4o' })],
      null
    )
    expect(aliases.map((o) => o.value)).toEqual(['gpt-4o'])
    expect(apiNames.map((o) => o.value)).toEqual(['gpt-4.1'])
  })

  it('collapses an API name shared by several configs', () => {
    const { apiNames } = buildModelOptions([model({}), model({ temperature: 0.2 })], null)
    expect(apiNames).toHaveLength(1)
  })

  it('labels an alias with the model that wins it: namespace over platform, then priority', () => {
    const { aliases } = buildModelOptions(
      [
        model({ alias: 'BIG', apiModelName: 'platform-model', priority: 100 }),
        model({ alias: 'big', apiModelName: 'namespace-model', namespaceId: 'ns-1', priority: 0 }),
      ],
      null
    )
    expect(aliases).toHaveLength(1)
    expect(aliases[0].label).toContain('namespace-model')
  })

  it('carries nothing extra when the stored value is listed, or empty', () => {
    const models = [model({ alias: 'BIG' })]
    expect(buildModelOptions(models, 'BIG').current).toBeNull()
    expect(buildModelOptions(models, null).current).toBeNull()
    expect(buildModelOptions(models, '').current).toBeNull()
  })

  it('keeps a stored value nothing resolves, flagged', () => {
    expect(buildModelOptions([model({ alias: 'BIG' })], 'RETIRED').current).toEqual({
      value: 'RETIRED',
      label: 'RETIRED (not in the list)',
    })
  })

  it('keeps a stored value that differs only by case verbatim, without flagging it', () => {
    // The server matches case-insensitively, so `big` works; rewriting it would edit the config unasked.
    expect(buildModelOptions([model({ alias: 'BIG' })], 'big').current).toEqual({ value: 'big', label: 'big' })
  })
})

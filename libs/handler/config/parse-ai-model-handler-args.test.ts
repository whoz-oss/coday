import { parseAiModelHandlerArgs } from './parse-ai-model-handler-args'

describe('parseAiModelHandlerArgs', () => {
  it('returns empty fields for no arguments', () => {
    expect(parseAiModelHandlerArgs('')).toEqual({
      isProject: false,
      aiProviderNameStart: undefined,
      aiModelName: undefined
    })
  })

  it('parses only provider', () => {
    expect(parseAiModelHandlerArgs('openai')).toEqual({
      isProject: false,
      aiProviderNameStart: 'openai',
      aiModelName: undefined
    })
  })

  it('parses provider and model', () => {
    expect(parseAiModelHandlerArgs('openai gpt-4')).toEqual({
      isProject: false,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })

  it('parses provider and --project flag, flag after', () => {
    expect(parseAiModelHandlerArgs('openai --project')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: undefined
    })
  })

  it('parses provider and --project flag, flag before', () => {
    expect(parseAiModelHandlerArgs('--project openai')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: undefined
    })
  })

  it('parses provider, model, and --project flag (flag after)', () => {
    expect(parseAiModelHandlerArgs('openai gpt-4 --project')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })

  it('parses provider, model, and --project flag (flag between)', () => {
    expect(parseAiModelHandlerArgs('openai --project gpt-4')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })

  it('parses -p as project flag', () => {
    expect(parseAiModelHandlerArgs('openai gpt-4 -p')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })

  it('parses only flags, is project', () => {
    expect(parseAiModelHandlerArgs('--project')).toEqual({
      isProject: true,
      aiProviderNameStart: undefined,
      aiModelName: undefined
    })
    expect(parseAiModelHandlerArgs('-p')).toEqual({
      isProject: true,
      aiProviderNameStart: undefined,
      aiModelName: undefined
    })
  })

  it('throws on too many non-flag arguments', () => {
    expect(() => parseAiModelHandlerArgs('a b c')).toThrow('Too many arguments')
    expect(() => parseAiModelHandlerArgs('a --project b c')).toThrow('Too many arguments')
  })

  it('ignores duplicate project flags', () => {
    expect(parseAiModelHandlerArgs('--project openai -p gpt-4')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })

  it('handles extra whitespace', () => {
    expect(parseAiModelHandlerArgs('  openai    gpt-4   --project  ')).toEqual({
      isProject: true,
      aiProviderNameStart: 'openai',
      aiModelName: 'gpt-4'
    })
  })
})

import { AiModel } from '@whoz-oss/agentos-api-client'
import { chatModelOptions, chatModels, isChatModel } from './chat-model.utils'

const model = (overrides: Partial<AiModel>): AiModel => ({
  aiProviderId: 'p-1',
  apiModelName: 'gpt-4o',
  priority: 0,
  ...overrides,
})

describe('chat model helpers', () => {
  describe('isChatModel()', () => {
    it('accepts an ordinary model', () => {
      expect(isChatModel(model({ alias: 'BIG', apiModelName: 'claude-opus' }))).toBe(true)
      expect(isChatModel(model({ apiModelName: 'gpt-4o' }))).toBe(true)
    })

    it('rejects the WHISPER alias, whatever its case', () => {
      expect(isChatModel(model({ alias: 'WHISPER' }))).toBe(false)
      expect(isChatModel(model({ alias: 'whisper' }))).toBe(false)
    })

    it('rejects a transcription API model name, even without an alias', () => {
      expect(isChatModel(model({ apiModelName: 'whisper-1' }))).toBe(false)
      expect(isChatModel(model({ apiModelName: 'openai/Whisper-1' }))).toBe(false)
    })
  })

  describe('chatModels()', () => {
    it('keeps the chat models only, in order', () => {
      const big = model({ alias: 'BIG' })
      const small = model({ alias: 'SMALL', apiModelName: 'gpt-mini' })
      const whisper = model({ alias: 'WHISPER', apiModelName: 'whisper-1' })

      expect(chatModels([big, whisper, small])).toEqual([big, small])
    })
  })

  describe('chatModelOptions()', () => {
    it('lists the aliases, then the API names no alias takes, without any transcription model', () => {
      const options = chatModelOptions([
        model({ alias: 'BIG', apiModelName: 'claude-opus' }),
        model({ apiModelName: 'claude-haiku' }),
        model({ alias: 'WHISPER', apiModelName: 'whisper-1' }),
        model({ apiModelName: 'openai/whisper-1' }),
      ])

      expect(options).toEqual([
        { value: 'BIG', label: 'BIG (claude-opus)' },
        { value: 'claude-haiku', label: 'claude-haiku' },
        { value: 'claude-opus', label: 'claude-opus' },
      ])
    })

    it('is empty when there is no chat model', () => {
      expect(chatModelOptions([model({ alias: 'WHISPER', apiModelName: 'whisper-1' })])).toEqual([])
    })
  })
})

import {
  AGENT_MODEL_CHOICE,
  filterModelChoices,
  findModelChoice,
  modelChoices,
  modelCommandArg,
  modelLabel,
  parseModelCommand,
  sameModel,
} from './model-command'

const CHOICES = modelChoices([
  { value: 'BIG', label: 'BIG (claude-opus)' },
  { value: 'SMALL', label: 'SMALL (claude-haiku)' },
  { value: 'gpt-4o', label: 'gpt-4o' },
])

describe('model command', () => {
  describe('modelChoices()', () => {
    it('starts with the way back to the agents own models, then one choice per option', () => {
      expect(CHOICES.map((c) => c.model)).toEqual([null, 'BIG', 'SMALL', 'gpt-4o'])
      expect(CHOICES[0]).toBe(AGENT_MODEL_CHOICE)
    })

    it('describes an alias by the API model it resolves to, and an API name by nothing', () => {
      expect(CHOICES[1]).toEqual({ model: 'BIG', label: 'BIG', description: 'claude-opus' })
      expect(CHOICES[3]).toEqual({ model: 'gpt-4o', label: 'gpt-4o', description: '' })
    })

    it('only offers the way back when there is no model', () => {
      expect(modelChoices([])).toEqual([AGENT_MODEL_CHOICE])
    })
  })

  describe('modelCommandArg()', () => {
    it('opens from two letters of the command on', () => {
      expect(modelCommandArg('/mo')).toBe('')
      expect(modelCommandArg('/model')).toBe('')
      expect(modelCommandArg('/MODEL')).toBe('')
    })

    it('leaves a lone slash, one letter and other commands to the others', () => {
      expect(modelCommandArg('/')).toBeNull()
      expect(modelCommandArg('/m')).toBeNull()
      expect(modelCommandArg('/review')).toBeNull()
      expect(modelCommandArg('/effort high')).toBeNull()
      expect(modelCommandArg('hello /model')).toBeNull()
    })

    it('returns the name typed after the command', () => {
      expect(modelCommandArg('/model ')).toBe('')
      expect(modelCommandArg('/model B')).toBe('B')
      expect(modelCommandArg('/model BIG')).toBe('BIG')
    })

    it('needs the full command name before a name', () => {
      expect(modelCommandArg('/mod BIG')).toBeNull()
    })
  })

  describe('filterModelChoices()', () => {
    it('filters by label prefix, case-insensitively', () => {
      expect(filterModelChoices(CHOICES, 'b').map((c) => c.label)).toEqual(['BIG'])
      expect(filterModelChoices(CHOICES, 'S').map((c) => c.label)).toEqual(['SMALL'])
      expect(filterModelChoices(CHOICES, '')).toEqual(CHOICES)
      expect(filterModelChoices(CHOICES, 'zz')).toEqual([])
    })
  })

  describe('parseModelCommand()', () => {
    it('returns the name typed after the command', () => {
      expect(parseModelCommand('/model BIG')).toBe('BIG')
      expect(parseModelCommand('  /Model gpt-4o ')).toBe('gpt-4o')
    })

    it('returns null when the name is missing, so nothing is sent to the agent', () => {
      expect(parseModelCommand('/model')).toBeNull()
      expect(parseModelCommand('/model   ')).toBeNull()
    })

    it('returns undefined for anything that is not a /model command', () => {
      expect(parseModelCommand('Which /model is it?')).toBeUndefined()
      expect(parseModelCommand('/modelling')).toBeUndefined()
      expect(parseModelCommand('/model BIG now')).toBeUndefined()
      expect(parseModelCommand('/effort high')).toBeUndefined()
    })
  })

  describe('findModelChoice()', () => {
    it('finds a choice by label, case-insensitively, the way back included', () => {
      expect(findModelChoice(CHOICES, 'big')?.model).toBe('BIG')
      expect(findModelChoice(CHOICES, ' SMALL ')?.model).toBe('SMALL')
      expect(findModelChoice(CHOICES, 'agent')).toBe(AGENT_MODEL_CHOICE)
    })

    it('finds nothing for an unknown name', () => {
      expect(findModelChoice(CHOICES, 'nope')).toBeUndefined()
    })
  })

  describe('sameModel()', () => {
    it('compares case-insensitively, and reads no model as no model', () => {
      expect(sameModel('BIG', 'big')).toBe(true)
      expect(sameModel('BIG', 'SMALL')).toBe(false)
      expect(sameModel(null, undefined)).toBe(true)
      expect(sameModel(null, 'BIG')).toBe(false)
    })
  })

  describe('modelLabel()', () => {
    it('names the model, and "agent default" when the case has no override', () => {
      expect(modelLabel('BIG')).toBe('BIG')
      expect(modelLabel(null)).toBe('agent default')
      expect(modelLabel(undefined)).toBe('agent default')
      expect(modelLabel('  ')).toBe('agent default')
    })
  })
})

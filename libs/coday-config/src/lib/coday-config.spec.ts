import { normalizeCodayConfig, DEFAULT_CODAY_CONFIG, CodayConfig } from './coday-config'

describe('CodayConfig', () => {
  describe('DEFAULT_CODAY_CONFIG', () => {
    it('should have version 1', () => {
      expect(DEFAULT_CODAY_CONFIG.version).toBe(1)
    })

    it('should have no other properties', () => {
      expect(Object.keys(DEFAULT_CODAY_CONFIG)).toEqual(['version'])
    })
  })

  describe('normalizeCodayConfig', () => {
    it('should map deprecated description to context', () => {
      const config: CodayConfig = {
        version: 1,
        description: 'This is a description',
      }

      const normalized = normalizeCodayConfig(config)

      expect(normalized.context).toBe('This is a description')
      expect(normalized.description).toBe('This is a description') // Original preserved
    })

    it('should map deprecated bio to context', () => {
      const config: CodayConfig = {
        version: 1,
        bio: 'This is a bio',
      }

      const normalized = normalizeCodayConfig(config)

      expect(normalized.context).toBe('This is a bio')
      expect(normalized.bio).toBe('This is a bio') // Original preserved
    })

    it('should concatenate description and bio if both present', () => {
      const config: CodayConfig = {
        version: 1,
        description: 'Project description',
        bio: 'User bio',
      }

      const normalized = normalizeCodayConfig(config)

      expect(normalized.context).toBe('Project description\n\n---\n\nUser bio')
    })

    it('should not override existing context', () => {
      const config: CodayConfig = {
        version: 1,
        context: 'Existing context',
        description: 'Description',
        bio: 'Bio',
      }

      const normalized = normalizeCodayConfig(config)

      expect(normalized.context).toBe('Existing context')
    })

    it('should preserve all other properties', () => {
      const config: CodayConfig = {
        version: 1,
        description: 'Test',
        defaultAgent: 'sway',
        ai: [{ name: 'openai' }],
      }

      const normalized = normalizeCodayConfig(config)

      expect(normalized.version).toBe(1)
      expect(normalized.defaultAgent).toBe('sway')
      expect(normalized.ai).toEqual([{ name: 'openai' }])
    })
  })
})

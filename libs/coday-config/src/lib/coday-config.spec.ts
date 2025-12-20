import { DEFAULT_CODAY_CONFIG, CodayConfig, UserConfig } from './coday-config'

describe('CodayConfig', () => {
  describe('DEFAULT_CODAY_CONFIG', () => {
    it('should have version 1', () => {
      expect(DEFAULT_CODAY_CONFIG.version).toBe(1)
    })

    it('should have no other properties', () => {
      expect(Object.keys(DEFAULT_CODAY_CONFIG)).toEqual(['version'])
    })
  })

  describe('CodayConfig interface', () => {
    it('should accept minimal config', () => {
      const config: CodayConfig = {
        version: 1,
      }

      expect(config.version).toBe(1)
    })

    it('should accept full config', () => {
      const config: CodayConfig = {
        version: 1,
        context: 'Test context',
        ai: [{ name: 'openai', apiKey: 'sk-xxx' }],
        mcp: { servers: [] },
        integrations: {},
        defaultAgent: 'sway',
      }

      expect(config.version).toBe(1)
      expect(config.context).toBe('Test context')
      expect(config.ai).toHaveLength(1)
      expect(config.defaultAgent).toBe('sway')
    })
  })

  describe('UserConfig interface', () => {
    it('should extend CodayConfig', () => {
      const config: UserConfig = {
        version: 1,
        context: 'User bio',
        projects: {
          'my-project': {
            version: 1,
            context: 'Project-specific context',
            defaultAgent: 'sway',
          },
        },
      }

      expect(config.version).toBe(1)
      expect(config.context).toBe('User bio')
      expect(config.projects?.['my-project']?.defaultAgent).toBe('sway')
    })

    it('should accept projects with nested CodayConfig', () => {
      const config: UserConfig = {
        version: 1,
        projects: {
          project1: {
            version: 1,
            context: 'Context 1',
          },
          project2: {
            version: 1,
            ai: [{ name: 'openai' }],
          },
        },
      }

      expect(Object.keys(config.projects || {})).toHaveLength(2)
      expect(config.projects?.project1?.context).toBe('Context 1')
      expect(config.projects?.project2?.ai).toHaveLength(1)
    })
  })
})

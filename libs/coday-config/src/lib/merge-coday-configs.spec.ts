import { mergeCodayConfigs } from './merge-coday-configs'
import { CodayConfig } from './coday-config'

describe('mergeCodayConfigs', () => {
  describe('Basic merging', () => {
    it('should return default config when no configs provided', () => {
      const result = mergeCodayConfigs()
      expect(result).toEqual({ version: 1 })
    })

    it('should handle null and undefined configs', () => {
      const config: CodayConfig = { version: 1, context: 'Test' }
      const result = mergeCodayConfigs(null, undefined, config, null)
      expect(result.context).toBe('Test')
    })

    it('should merge single config', () => {
      const config: CodayConfig = {
        version: 1,
        context: 'Test context',
        defaultAgent: 'sway',
      }
      const result = mergeCodayConfigs(config)
      expect(result).toEqual(config)
    })
  })

  describe('Context merging', () => {
    it('should concatenate contexts with separator', () => {
      const config1: CodayConfig = { version: 1, context: 'Context 1' }
      const config2: CodayConfig = { version: 1, context: 'Context 2' }
      const config3: CodayConfig = { version: 1, context: 'Context 3' }

      const result = mergeCodayConfigs(config1, config2, config3)

      expect(result.context).toBe('Context 1\n\n---\n\nContext 2\n\n---\n\nContext 3')
    })

    it('should handle missing contexts', () => {
      const config1: CodayConfig = { version: 1 }
      const config2: CodayConfig = { version: 1, context: 'Context 2' }
      const config3: CodayConfig = { version: 1 }

      const result = mergeCodayConfigs(config1, config2, config3)

      expect(result.context).toBe('Context 2')
    })
  })

  describe('AI provider merging', () => {
    it('should merge providers by name', () => {
      const config1: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai', apiKey: 'sk-xxx' }],
      }
      const config2: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai', url: 'https://api.openai.com' }],
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.ai).toHaveLength(1)
      expect(result.ai?.[0]).toEqual({
        name: 'openai',
        apiKey: 'sk-xxx',
        url: 'https://api.openai.com',
      })
    })

    it('should add new providers', () => {
      const config1: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai', apiKey: 'sk-xxx' }],
      }
      const config2: CodayConfig = {
        version: 1,
        ai: [{ name: 'anthropic', apiKey: 'sk-ant-xxx' }],
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.ai).toHaveLength(2)
      expect(result.ai?.find((p) => p.name === 'openai')).toBeDefined()
      expect(result.ai?.find((p) => p.name === 'anthropic')).toBeDefined()
    })

    it('should merge models within provider', () => {
      const config1: CodayConfig = {
        version: 1,
        ai: [
          {
            name: 'openai',
            models: [
              { name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 },
              { name: 'gpt-4o-mini', alias: 'SMALL', contextWindow: 128000 },
            ],
          },
        ],
      }
      const config2: CodayConfig = {
        version: 1,
        ai: [
          {
            name: 'openai',
            models: [{ name: 'gpt-4o', contextWindow: 200000 }],
          },
        ],
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.ai).toHaveLength(1)
      expect(result.ai?.[0]?.models).toHaveLength(2)

      const gpt4o = result.ai?.[0]?.models?.find((m) => m.name === 'gpt-4o')
      expect(gpt4o).toEqual({
        name: 'gpt-4o',
        alias: 'BIG',
        contextWindow: 200000,
      })
    })

    it('should add new models to provider', () => {
      const config1: CodayConfig = {
        version: 1,
        ai: [
          {
            name: 'openai',
            models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }],
          },
        ],
      }
      const config2: CodayConfig = {
        version: 1,
        ai: [
          {
            name: 'openai',
            models: [{ name: 'gpt-4o-mini', alias: 'SMALL', contextWindow: 128000 }],
          },
        ],
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.ai?.[0]?.models).toHaveLength(2)
      expect(result.ai?.[0]?.models?.find((m) => m.name === 'gpt-4o')).toBeDefined()
      expect(result.ai?.[0]?.models?.find((m) => m.name === 'gpt-4o-mini')).toBeDefined()
    })

    it('should override provider properties in later configs', () => {
      const config1: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai', apiKey: 'old-key' }],
      }
      const config2: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai', apiKey: 'new-key' }],
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.ai?.[0]?.apiKey).toBe('new-key')
    })
  })

  describe('MCP server merging', () => {
    it('should merge MCP servers by id', () => {
      const config1: CodayConfig = {
        version: 1,
        mcp: {
          servers: [
            {
              id: 'filesystem',
              name: 'Filesystem',
              command: 'npx',
              args: ['@modelcontextprotocol/server-filesystem'],
            },
          ],
        },
      }
      const config2: CodayConfig = {
        version: 1,
        mcp: {
          servers: [
            {
              id: 'filesystem',
              name: 'Filesystem',
              args: ['@modelcontextprotocol/server-filesystem', '/home/user'],
            },
          ],
        },
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.mcp?.servers).toHaveLength(1)
      expect(result.mcp?.servers?.[0]).toEqual({
        id: 'filesystem',
        name: 'Filesystem',
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/home/user'],
      })
    })

    it('should add new MCP servers', () => {
      const config1: CodayConfig = {
        version: 1,
        mcp: {
          servers: [
            {
              id: 'filesystem',
              name: 'Filesystem',
              command: 'npx',
              args: ['@modelcontextprotocol/server-filesystem'],
            },
          ],
        },
      }
      const config2: CodayConfig = {
        version: 1,
        mcp: {
          servers: [
            {
              id: 'playwright',
              name: 'Playwright',
              command: 'npx',
              args: ['@modelcontextprotocol/server-playwright'],
            },
          ],
        },
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.mcp?.servers).toHaveLength(2)
      expect(result.mcp?.servers?.find((s) => s.id === 'filesystem')).toBeDefined()
      expect(result.mcp?.servers?.find((s) => s.id === 'playwright')).toBeDefined()
    })

    it('should initialize mcp if not present', () => {
      const config1: CodayConfig = { version: 1 }
      const config2: CodayConfig = {
        version: 1,
        mcp: {
          servers: [
            {
              id: 'filesystem',
              name: 'Filesystem',
              command: 'npx',
              args: ['@modelcontextprotocol/server-filesystem'],
            },
          ],
        },
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.mcp?.servers).toHaveLength(1)
    })
  })

  describe('Integration merging', () => {
    it('should merge integrations by key', () => {
      const config1: CodayConfig = {
        version: 1,
        integrations: {
          github: { apiUrl: 'https://api.github.com' },
        },
      }
      const config2: CodayConfig = {
        version: 1,
        integrations: {
          github: { apiKey: 'ghp_xxx' },
        },
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.integrations?.github).toEqual({
        apiUrl: 'https://api.github.com',
        apiKey: 'ghp_xxx',
      })
    })

    it('should add new integrations', () => {
      const config1: CodayConfig = {
        version: 1,
        integrations: {
          github: { apiUrl: 'https://api.github.com' },
        },
      }
      const config2: CodayConfig = {
        version: 1,
        integrations: {
          slack: { apiUrl: 'https://slack.com/api' },
        },
      }

      const result = mergeCodayConfigs(config1, config2)

      expect(Object.keys(result.integrations || {})).toHaveLength(2)
      expect(result.integrations?.github).toBeDefined()
      expect(result.integrations?.slack).toBeDefined()
    })
  })

  describe('Simple property overrides', () => {
    it('should override defaultAgent with last value', () => {
      const config1: CodayConfig = { version: 1, defaultAgent: 'agent1' }
      const config2: CodayConfig = { version: 1, defaultAgent: 'agent2' }
      const config3: CodayConfig = { version: 1, defaultAgent: 'agent3' }

      const result = mergeCodayConfigs(config1, config2, config3)

      expect(result.defaultAgent).toBe('agent3')
    })

    it('should keep defaultAgent from earlier config if not overridden', () => {
      const config1: CodayConfig = { version: 1, defaultAgent: 'agent1' }
      const config2: CodayConfig = { version: 1 }

      const result = mergeCodayConfigs(config1, config2)

      expect(result.defaultAgent).toBe('agent1')
    })
  })

  describe('Complex real-world scenarios', () => {
    it('should merge complete user -> coday.yaml -> project -> user-project stack', () => {
      const userGlobal: CodayConfig = {
        version: 1,
        context: 'I am Vincent, a TypeScript developer',
        ai: [{ name: 'openai', apiKey: 'sk-user-xxx' }],
        mcp: {
          servers: [
            {
              id: 'filesystem',
              name: 'Filesystem',
              command: 'npx',
              args: ['@modelcontextprotocol/server-filesystem', '/home/vincent'],
            },
          ],
        },
      }

      const codayYaml: CodayConfig = {
        version: 1,
        context: 'Coday is an AI agent framework',
        ai: [
          {
            name: 'openai',
            models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }],
          },
        ],
        mcp: {
          servers: [
            {
              id: 'playwright',
              name: 'Playwright',
              command: 'npx',
              args: ['@modelcontextprotocol/server-playwright'],
            },
          ],
        },
        integrations: {
          github: { apiUrl: 'https://api.github.com' },
        },
      }

      const projectLocal: CodayConfig = {
        version: 1,
        context: 'Local development environment',
        integrations: {
          github: { apiKey: 'ghp_local_xxx' },
        },
      }

      const userProject: CodayConfig = {
        version: 1,
        context: 'Focus on testing',
        defaultAgent: 'sway',
        ai: [
          {
            name: 'openai',
            models: [{ name: 'gpt-4o', contextWindow: 200000 }],
          },
        ],
      }

      const result = mergeCodayConfigs(userGlobal, codayYaml, projectLocal, userProject)

      // Context should be concatenated
      expect(result.context).toContain('I am Vincent')
      expect(result.context).toContain('Coday is an AI agent framework')
      expect(result.context).toContain('Local development environment')
      expect(result.context).toContain('Focus on testing')

      // AI provider should be merged with all properties
      expect(result.ai).toHaveLength(1)
      expect(result.ai?.[0]).toMatchObject({
        name: 'openai',
        apiKey: 'sk-user-xxx',
      })
      expect(result.ai?.[0]?.models?.[0]).toMatchObject({
        name: 'gpt-4o',
        alias: 'BIG',
        contextWindow: 200000,
      })

      // MCP servers should be merged
      expect(result.mcp?.servers).toHaveLength(2)
      expect(result.mcp?.servers?.find((s) => s.id === 'filesystem')).toBeDefined()
      expect(result.mcp?.servers?.find((s) => s.id === 'playwright')).toBeDefined()

      // Integrations should be merged
      expect(result.integrations?.github).toEqual({
        apiUrl: 'https://api.github.com',
        apiKey: 'ghp_local_xxx',
      })

      // Default agent from user-project
      expect(result.defaultAgent).toBe('sway')
    })

    it('should handle partial configs at each level', () => {
      const config1: CodayConfig = {
        version: 1,
        context: 'Context 1',
      }

      const config2: CodayConfig = {
        version: 1,
        ai: [{ name: 'openai' }],
      }

      const config3: CodayConfig = {
        version: 1,
        mcp: { servers: [{ id: 'test', name: 'Test', command: 'test' }] },
      }

      const config4: CodayConfig = {
        version: 1,
        defaultAgent: 'sway',
      }

      const result = mergeCodayConfigs(config1, config2, config3, config4)

      expect(result.context).toBe('Context 1')
      expect(result.ai).toHaveLength(1)
      expect(result.mcp?.servers).toHaveLength(1)
      expect(result.defaultAgent).toBe('sway')
    })
  })
})

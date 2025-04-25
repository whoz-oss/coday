import { migrateData } from './data-migration'
import { aiPropertyToAi } from './ai-providers-to-ai.migration'
import { ProjectLocalConfig } from '../../model/project-local-config'
import { UserConfig } from '../../model/user-config'

describe('aiProviders to ai migration', () => {
  describe('ProjectLocalConfig migration', () => {
    it('should handle config with no version and no ai field', () => {
      // Create a sample config with aiProviders only
      const config: Partial<ProjectLocalConfig> = {
        path: '/test/project',
        aiProviders: {
          anthropic: {
            apiKey: 'test-anthropic-key',
          },
          openai: {
            apiKey: 'test-openai-key',
          },
        },
        integration: {},
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check version is incremented
      expect(result.version).toBe(2)

      // Check ai array is created with correct values
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(2)

      // Check anthropic provider
      const anthropicProvider = result.ai.find((p) => p.name === 'anthropic')
      expect(anthropicProvider).toBeDefined()
      expect(anthropicProvider.apiKey).toBe('test-anthropic-key')

      // Check openai provider
      const openaiProvider = result.ai.find((p) => p.name === 'openai')
      expect(openaiProvider).toBeDefined()
      expect(openaiProvider.apiKey).toBe('test-openai-key')

      // Original aiProviders should still be present
      expect(result.aiProviders).toBeDefined()
    })

    it('should handle config with no aiProviders', () => {
      // Create a sample config without aiProviders
      const config: Partial<ProjectLocalConfig> = {
        path: '/test/project',
        integration: {},
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check version is incremented
      expect(result.version).toBe(2)

      // Check ai array is created but empty
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(0)

      // Original aiProviders should be undefined
      expect(result.aiProviders).toBeUndefined()
    })

    it('should handle custom provider configuration', () => {
      // Create a sample config with a custom provider
      const config: Partial<ProjectLocalConfig> = {
        path: '/test/project',
        aiProviders: {
          localLlm: {
            apiKey: 'test-local-key',
            url: 'http://localhost:8080',
            model: 'llama2',
            contextWindow: 32000,
          },
        },
        integration: {},
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check version is incremented
      expect(result.version).toBe(2)

      // Check ai array is created with correct values
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(1)

      // Check custom provider
      const localProvider = result.ai.find((p) => p.name === 'localLlm')
      expect(localProvider).toBeDefined()
      expect(localProvider.apiKey).toBe('test-local-key')
      expect(localProvider.type).toBe('openai')
      expect(localProvider.url).toBe('http://localhost:8080')
      expect(localProvider.models).toHaveLength(1)
      expect(localProvider.models[0].name).toBe('llama2')
      expect(localProvider.models[0].contextWindow).toBe(32000)
    })
  })

  describe('UserConfig migration', () => {
    it('should handle UserConfig with aiProviders', () => {
      // Create a sample UserConfig
      const config: Partial<UserConfig> = {
        version: 1,
        aiProviders: {
          anthropic: {
            apiKey: 'user-anthropic-key',
          },
          google: {
            apiKey: 'user-google-key',
          },
          localLlm: {
            apiKey: 'user-local-key',
            url: 'http://localhost:1234',
            model: 'mixtral',
          },
        },
        projects: {
          'test-project': {
            integration: {},
          },
        },
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check version is incremented
      expect(result.version).toBe(2)

      // Check ai array is created with correct values
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(3)

      // Check anthropic provider
      const anthropicProvider = result.ai.find((p) => p.name === 'anthropic')
      expect(anthropicProvider).toBeDefined()
      expect(anthropicProvider.apiKey).toBe('user-anthropic-key')

      // Check google provider
      const googleProvider = result.ai.find((p) => p.name === 'google')
      expect(googleProvider).toBeDefined()
      expect(googleProvider.apiKey).toBe('user-google-key')

      // Check custom provider
      const localProvider = result.ai.find((p) => p.name === 'localLlm')
      expect(localProvider).toBeDefined()
      expect(localProvider.apiKey).toBe('user-local-key')
      expect(localProvider.type).toBe('openai')
      expect(localProvider.url).toBe('http://localhost:1234')
      expect(localProvider.models).toHaveLength(1)
      expect(localProvider.models[0].name).toBe('mixtral')
      expect(localProvider.models[0].contextWindow).toBe(64000) // Default value

      // Original aiProviders should still be present
      expect(result.aiProviders).toBeDefined()

      // Projects should be preserved
      expect(result.projects).toBeDefined()
      expect(result.projects['test-project']).toBeDefined()
    })

    it('should handle UserConfig with empty aiProviders', () => {
      // Create a sample UserConfig with empty aiProviders
      const config: Partial<UserConfig> = {
        version: 1,
        aiProviders: {},
        projects: {
          'test-project': {
            integration: {},
          },
        },
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check version is incremented
      expect(result.version).toBe(2)

      // Check ai array is created but empty
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(0)

      // Original aiProviders should still be present
      expect(result.aiProviders).toBeDefined()

      // Projects should be preserved
      expect(result.projects).toBeDefined()
    })

    it('should handle UserConfig with multiple default providers', () => {
      // Create a sample UserConfig with all default providers
      const config: Partial<UserConfig> = {
        version: 1,
        aiProviders: {
          anthropic: {
            apiKey: 'user-anthropic-key',
          },
          openai: {
            apiKey: 'user-openai-key',
          },
          google: {
            apiKey: 'user-google-key',
          },
        },
      }

      // Apply migration
      const result = migrateData(config, [aiPropertyToAi])

      // Check ai array is created with correct values
      expect(result.ai).toBeDefined()
      expect(result.ai).toHaveLength(3)

      // Check all providers are included
      const providerNames = result.ai.map((p) => p.name)
      expect(providerNames).toContain('anthropic')
      expect(providerNames).toContain('openai')
      expect(providerNames).toContain('google')

      // Default providers should not have type or models
      const anthropicProvider = result.ai.find((p) => p.name === 'anthropic')
      expect(anthropicProvider.type).toBeUndefined()
      expect(anthropicProvider.models).toBeUndefined()

      const openaiProvider = result.ai.find((p) => p.name === 'openai')
      expect(openaiProvider.type).toBeUndefined()
      expect(openaiProvider.models).toBeUndefined()

      const googleProvider = result.ai.find((p) => p.name === 'google')
      expect(googleProvider.type).toBeUndefined()
      expect(googleProvider.models).toBeUndefined()
    })
  })
})

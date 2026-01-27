import { AiClientProvider } from './ai-client-provider'
import { UserService } from '@coday/service'
import { ProjectStateService } from '@coday/service'
import { CodayLogger } from '@coday/model'
import { Interactor } from '@coday/model'
import { CommandContext } from '@coday/model'

describe('AiClientProvider - Auto-detection', () => {
  let provider: AiClientProvider
  let mockInteractor: jest.Mocked<Interactor>
  let mockUserService: jest.Mocked<UserService>
  let mockProjectService: jest.Mocked<ProjectStateService>
  let mockLogger: jest.Mocked<CodayLogger>
  let originalEnv: NodeJS.ProcessEnv

  beforeEach(() => {
    // Save original environment
    originalEnv = { ...process.env }

    // Create mocks
    mockInteractor = {
      displayText: jest.fn(),
      debug: jest.fn(),
      error: jest.fn(),
    } as any

    mockUserService = {
      config: { ai: [] },
    } as any

    mockProjectService = {
      selectedProject: null,
    } as any

    mockLogger = {} as any

    provider = new AiClientProvider(mockInteractor, mockUserService, mockProjectService, mockLogger)
  })

  afterEach(() => {
    // Restore original environment
    process.env = originalEnv
  })

  describe('Environment Variable Detection', () => {
    it('should auto-detect Anthropic provider when ANTHROPIC_API_KEY is set', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      expect(mockInteractor.debug).toHaveBeenCalledWith(expect.stringContaining('Auto-detected anthropic provider'))
      expect(mockInteractor.displayText).toHaveBeenCalledWith(expect.stringContaining('anthropic (auto-detected)'))
    })

    it('should auto-detect OpenAI provider when OPENAI_API_KEY is set', () => {
      // Arrange
      process.env.OPENAI_API_KEY = 'sk-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      expect(mockInteractor.debug).toHaveBeenCalledWith(expect.stringContaining('Auto-detected openai provider'))
      expect(mockInteractor.displayText).toHaveBeenCalledWith(expect.stringContaining('openai (auto-detected)'))
    })

    it('should auto-detect Google provider when GEMINI_API_KEY is set', () => {
      // Arrange
      process.env.GEMINI_API_KEY = 'test-gemini-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      expect(mockInteractor.debug).toHaveBeenCalledWith(expect.stringContaining('Auto-detected google provider'))
      expect(mockInteractor.displayText).toHaveBeenCalledWith(expect.stringContaining('google (auto-detected)'))
    })

    it('should auto-detect multiple providers when multiple keys are set', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      process.env.OPENAI_API_KEY = 'sk-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      expect(mockInteractor.debug).toHaveBeenCalledWith(expect.stringContaining('Auto-detected anthropic provider'))
      expect(mockInteractor.debug).toHaveBeenCalledWith(expect.stringContaining('Auto-detected openai provider'))
    })

    it('should not auto-detect when no environment variables are set', () => {
      // Arrange
      delete process.env.ANTHROPIC_API_KEY
      delete process.env.OPENAI_API_KEY
      delete process.env.GEMINI_API_KEY
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      expect(mockInteractor.debug).not.toHaveBeenCalledWith(expect.stringContaining('Auto-detected'))
    })
  })

  describe('Configuration Priority', () => {
    it('should merge explicit configuration with auto-detection', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-env-key'
      const context: CommandContext = {
        project: {
          ai: [
            {
              name: 'anthropic',
              apiKey: 'sk-ant-explicit-key',
              models: [
                {
                  name: 'claude-custom',
                  alias: 'custom',
                  contextWindow: 100000,
                },
              ],
            },
          ],
        },
      } as any

      // Act
      provider.init(context)

      // Assert
      // Explicit config means NO "(auto-detected)" label
      // Models from explicit config merged with client defaults
      const displayCall = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('anthropic')
      )
      expect(displayCall).toBeDefined()
      expect(displayCall![0]).not.toContain('(auto-detected)')
      expect(displayCall![0]).toContain('claude-custom')
      // Client default models (BIG/SMALL) are also present
      expect(displayCall![0]).toContain('BIG')
      expect(displayCall![0]).toContain('SMALL')
    })

    it('should merge user config with auto-detected config', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-env-key'
      mockUserService.config = {
        ai: [
          {
            name: 'anthropic',
            models: [
              {
                name: 'claude-user-model',
                alias: 'user',
                contextWindow: 150000,
              },
            ],
          },
        ],
      } as any
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      // User config means NO "(auto-detected)" label
      const displayCall = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('anthropic')
      )
      expect(displayCall).toBeDefined()
      expect(displayCall![0]).not.toContain('(auto-detected)')
      expect(displayCall![0]).toContain('claude-user-model')
      // Client default models are also present (merged)
      expect(displayCall![0]).toContain('BIG')
      expect(displayCall![0]).toContain('SMALL')
    })

    it('should show auto-detected label only when NO explicit config exists', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      process.env.OPENAI_API_KEY = 'sk-test-key'

      // Explicit config ONLY for OpenAI, not for Anthropic
      const context: CommandContext = {
        project: {
          ai: [
            {
              name: 'openai',
              models: [
                {
                  name: 'custom-gpt',
                  alias: 'custom',
                  contextWindow: 100000,
                },
              ],
            },
          ],
        },
      } as any

      // Act
      provider.init(context)

      // Assert
      const displayText = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('AI providers')
      )?.[0]

      expect(displayText).toBeDefined()
      // Anthropic has NO explicit config → should show "(auto-detected)"
      expect(displayText).toContain('anthropic (auto-detected)')
      // OpenAI HAS explicit config → should NOT show "(auto-detected)"
      expect(displayText).toContain('openai')
      expect(displayText).not.toContain('openai (auto-detected)')
    })
  })

  describe('Default Models from Client Implementations', () => {
    it('should use AnthropicClient default models when auto-detected', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      const displayCall = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('anthropic')
      )
      expect(displayCall).toBeDefined()
      // Models come from AnthropicClient.ANTHROPIC_DEFAULT_MODELS
      expect(displayCall![0]).toContain('claude-sonnet-4-5')
      expect(displayCall![0]).toContain('BIG')
      expect(displayCall![0]).toContain('SMALL')
    })

    it('should use OpenaiClient default models when auto-detected', () => {
      // Arrange
      process.env.OPENAI_API_KEY = 'sk-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      const displayCall = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('openai')
      )
      expect(displayCall).toBeDefined()
      // Models come from OpenaiClient default implementation
      // Exact models depend on OpenaiClient.models property
      expect(displayCall![0]).toContain('openai')
    })

    it('should use GoogleClient default models when auto-detected', () => {
      // Arrange
      process.env.GEMINI_API_KEY = 'test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)

      // Assert
      const displayCall = (mockInteractor.displayText as jest.Mock).mock.calls.find((call) =>
        call[0].includes('google')
      )
      expect(displayCall).toBeDefined()
      // Models come from GoogleClient.models property
      expect(displayCall![0]).toContain('gemini-2.5-pro')
      expect(displayCall![0]).toContain('gemini-2.5-flash')
      expect(displayCall![0]).toContain('BIG')
      expect(displayCall![0]).toContain('SMALL')
      // Verify it's marked as auto-detected since there's no explicit config
      expect(displayCall![0]).toContain('google (auto-detected)')
    })
  })

  describe('Client Creation', () => {
    it('should create a working client for auto-detected provider', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)
      const client = provider.getClient('anthropic')

      // Assert
      expect(client).toBeDefined()
      expect(client?.name.toLowerCase()).toBe('anthropic')
    })

    it('should be able to get client by model name from auto-detected provider', () => {
      // Arrange
      process.env.ANTHROPIC_API_KEY = 'sk-ant-test-key'
      const context: CommandContext = {
        project: { ai: [] },
      } as any

      // Act
      provider.init(context)
      // Use actual alias from AnthropicClient defaults (BIG/SMALL)
      const client = provider.getClient(undefined, 'BIG')

      // Assert
      expect(client).toBeDefined()
      expect(client?.name.toLowerCase()).toBe('anthropic')
    })
  })
})

import { ConfigMaskingService } from './config-masking.service'

describe('ConfigMaskingService', () => {
  let service: ConfigMaskingService

  beforeEach(() => {
    service = new ConfigMaskingService()
  })

  describe('maskConfig', () => {
    it('should mask apiKey fields', () => {
      const config = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-1234567890abcdef',
            type: 'openai',
          },
        ],
      }

      const masked = service.maskConfig(config)

      expect(masked.ai?.[0]?.apiKey).toBe('sk-1****cdef')
      expect(masked.ai?.[0]?.name).toBe('openai')
      expect(masked.ai?.[0]?.type).toBe('openai')
    })

    it('should mask password fields', () => {
      const config = {
        storage: {
          type: 'postgres',
          host: 'localhost',
          password: 'secretpassword123',
        },
      }

      const masked = service.maskConfig(config)

      expect(masked.storage.password).toBe('secr****d123')
      expect(masked.storage.host).toBe('localhost')
    })

    it('should mask all values in env objects', () => {
      const config = {
        mcp: {
          servers: [
            {
              id: 'test-server',
              command: 'node',
              env: {
                API_KEY: 'secret123',
                DATABASE_URL: 'postgres://...',
                PUBLIC_VAR: 'not-secret',
              },
            },
          ],
        },
      }

      const masked = service.maskConfig(config)

      expect(masked.mcp?.servers?.[0]?.env?.API_KEY).toBe('se****23') // 9 chars
      expect(masked.mcp?.servers?.[0]?.env?.DATABASE_URL).toBe('post****/...') // 14 chars
      expect(masked.mcp?.servers?.[0]?.env?.PUBLIC_VAR).toBe('no****et') // 10 chars
      expect(masked.mcp?.servers?.[0]?.command).toBe('node')
    })

    it('should handle nested structures', () => {
      const config = {
        projects: {
          'my-project': {
            integration: {
              github: {
                apiKey: 'ghp_1234567890',
                repo: 'my-repo',
              },
            },
          },
        },
      }

      const masked = service.maskConfig(config)

      expect(masked.projects['my-project'].integration.github.apiKey).toBe('ghp_****7890')
      expect(masked.projects['my-project'].integration.github.repo).toBe('my-repo')
    })

    it('should not mask empty or undefined values', () => {
      const config = {
        ai: [
          {
            name: 'openai',
            apiKey: '',
            token: undefined,
          },
        ],
      }

      const masked = service.maskConfig(config)

      expect(masked.ai?.[0]?.apiKey).toBe('')
      expect(masked.ai?.[0]?.token).toBeUndefined()
    })
  })

  describe('unmaskConfig', () => {
    it('should preserve original apiKey when masked pattern is sent', () => {
      const original = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-original-key',
          },
        ],
      }

      const incoming = {
        ai: [
          {
            name: 'openai-updated',
            apiKey: 'sk-o****l-key',
          },
        ],
      }

      const unmasked = service.unmaskConfig(incoming, original)

      expect(unmasked.ai?.[0]?.apiKey).toBe('sk-original-key')
      expect(unmasked.ai?.[0]?.name).toBe('openai-updated')
    })

    it('should use new apiKey when user provides a different value', () => {
      const original = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-original-key',
          },
        ],
      }

      const incoming = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-new-key-12345',
          },
        ],
      }

      const unmasked = service.unmaskConfig(incoming, original)

      expect(unmasked.ai?.[0]?.apiKey).toBe('sk-new-key-12345')
    })

    it('should preserve original env values when masked', () => {
      const original = {
        mcp: {
          servers: [
            {
              id: 'test',
              env: {
                API_KEY: 'secret123',
                DB_URL: 'postgres://...',
              },
            },
          ],
        },
      }

      const incoming = {
        mcp: {
          servers: [
            {
              id: 'test',
              env: {
                API_KEY: 'se****23',
                DB_URL: 'post****...',
              },
            },
          ],
        },
      }

      const unmasked = service.unmaskConfig(incoming, original)

      expect(unmasked.mcp?.servers?.[0]?.env?.API_KEY).toBe('secret123')
      expect(unmasked.mcp?.servers?.[0]?.env?.DB_URL).toBe('postgres://...')
    })

    it('should update env values when user provides new values', () => {
      const original = {
        mcp: {
          servers: [
            {
              id: 'test',
              env: {
                API_KEY: 'secret123',
              },
            },
          ],
        },
      }

      const incoming = {
        mcp: {
          servers: [
            {
              id: 'test',
              env: {
                API_KEY: 'newsecret456',
              },
            },
          ],
        },
      }

      const unmasked = service.unmaskConfig(incoming, original)

      expect(unmasked.mcp?.servers?.[0]?.env?.API_KEY).toBe('newsecret456')
    })

    it('should preserve original values for missing keys', () => {
      const original = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-original-key',
            url: 'https://api.openai.com',
          },
        ],
      }

      const incoming = {
        ai: [
          {
            name: 'openai',
            apiKey: 'sk-o****l-key',
          },
        ],
      }

      const unmasked = service.unmaskConfig(incoming, original)

      expect(unmasked.ai?.[0]?.apiKey).toBe('sk-original-key')
      expect((unmasked.ai?.[0] as any)?.url).toBe('https://api.openai.com')
    })
  })

  describe('masking patterns', () => {
    it('should mask short values (≤8 chars) completely', () => {
      const config = { apiKey: 'short', password: '12345678' }
      const masked = service.maskConfig(config)
      expect(masked.apiKey).toBe('****')
      expect(masked.password).toBe('****')
    })

    it('should show first 2 and last 2 for medium values (9-11 chars)', () => {
      const config = {
        apiKey: '123456789', // 9 chars
        password: '12345678901', // 11 chars
      }
      const masked = service.maskConfig(config)
      expect(masked.apiKey).toBe('12****89')
      expect(masked.password).toBe('12****01')
    })

    it('should show first 4 and last 4 for long values (≥12 chars)', () => {
      const config = {
        apiKey: 'sk-1234567890abcdef',
        password: 'verylongpassword123',
      }
      const masked = service.maskConfig(config)
      expect(masked.apiKey).toBe('sk-1****cdef')
      expect(masked.password).toBe('very****d123')
    })
  })

  describe('field name detection', () => {
    it('should mask fields containing "api_key" with underscore', () => {
      const config = { api_key: 'secret' }
      const masked = service.maskConfig(config)
      expect(masked.api_key).toBe('****')
    })

    it('should mask fields containing "token"', () => {
      const config = { authToken: 'secret', bearerToken: 'secret2' }
      const masked = service.maskConfig(config)
      expect(masked.authToken).toBe('****')
      expect(masked.bearerToken).toBe('****')
    })

    it('should mask fields containing "secret"', () => {
      const config = { clientSecret: 'secret', secretKey: 'secret2' }
      const masked = service.maskConfig(config)
      expect(masked.clientSecret).toBe('****')
      expect(masked.secretKey).toBe('****')
    })

    it('should be case-insensitive', () => {
      const config = { ApiKey: 'secret', APIKEY: 'secret2', apiKEY: 'secret3' }
      const masked = service.maskConfig(config)
      expect(masked.ApiKey).toBe('****')
      expect(masked.APIKEY).toBe('****')
      expect(masked.apiKEY).toBe('****')
    })
  })
})

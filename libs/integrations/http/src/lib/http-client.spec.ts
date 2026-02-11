/**
 * Unit tests for HttpClient
 * Demonstrates how to test HTTP integrations
 */

import { HttpClient } from './http-client'
import { HttpIntegrationConfig, HttpEndpoint } from './http-config'
import { Interactor } from '@coday/model'

describe('HttpClient', () => {
  let mockInteractor: jest.Mocked<Interactor>
  let client: HttpClient

  beforeEach(() => {
    mockInteractor = {
      debug: jest.fn(),
      error: jest.fn(),
      displayText: jest.fn(),
      warn: jest.fn(),
    } as any

    global.fetch = jest.fn()
  })

  afterEach(() => {
    jest.clearAllMocks()
  })

  describe('No Authentication', () => {
    it('should make a simple GET request', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        auth: { type: 'none' },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'getUser',
        method: 'GET',
        path: '/users/{userId}',
        params: [{ name: 'userId', type: 'string', required: true, location: 'path' }],
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => ({ id: '123', name: 'John' }),
      })

      const result = await client.request(endpoint, { userId: '123' })

      expect(fetch).toHaveBeenCalledWith(
        'https://api.example.com/users/123',
        expect.objectContaining({
          method: 'GET',
        })
      )
      expect(result).toEqual({ id: '123', name: 'John' })
    })

    it('should add query parameters for GET requests', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        auth: { type: 'none' },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'searchUsers',
        method: 'GET',
        path: '/users',
        params: [
          { name: 'query', type: 'string', required: true },
          { name: 'limit', type: 'number', required: false },
        ],
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => [],
      })

      await client.request(endpoint, { query: 'john', limit: 10 })

      expect(fetch).toHaveBeenCalledWith('https://api.example.com/users?query=john&limit=10', expect.any(Object))
    })
  })

  describe('Credentials Authentication', () => {
    it('should add custom headers for credentials', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        headers: {
          'X-Username': 'testuser',
          'X-API-Key': 'secret123',
        },
        auth: {
          type: 'credentials',
          usernameHeader: 'X-Username',
          apiKeyHeader: 'X-API-Key',
        },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'getData',
        method: 'GET',
        path: '/data',
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => ({}),
      })

      await client.request(endpoint, {})

      const [, options] = (fetch as jest.Mock).mock.calls[0]
      expect(options.headers['X-Username']).toBe('testuser')
      expect(options.headers['X-API-Key']).toBe('secret123')
    })

    it('should use Basic auth scheme', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        headers: {
          'X-Username': 'testuser',
          'X-API-Key': 'secret123',
        },
        auth: {
          type: 'credentials',
          scheme: 'Basic',
        },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'getData',
        method: 'GET',
        path: '/data',
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => ({}),
      })

      await client.request(endpoint, {})

      const [, options] = (fetch as jest.Mock).mock.calls[0]
      expect(options.headers['Authorization']).toMatch(/^Basic /)
    })
  })

  describe('Bearer Token Authentication', () => {
    it('should add Bearer token to Authorization header', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        headers: {
          Authorization: 'Bearer mytoken123',
        },
        auth: {
          type: 'bearer',
        },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'getData',
        method: 'GET',
        path: '/data',
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => ({}),
      })

      await client.request(endpoint, {})

      const [, options] = (fetch as jest.Mock).mock.calls[0]
      expect(options.headers['Authorization']).toBe('Bearer mytoken123')
    })
  })

  describe('POST Requests', () => {
    it('should send body parameters as JSON', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        auth: { type: 'none' },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'createUser',
        method: 'POST',
        path: '/users',
        params: [
          { name: 'name', type: 'string', required: true },
          { name: 'email', type: 'string', required: true },
        ],
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: true,
        headers: new Map([['content-type', 'application/json']]),
        json: async () => ({ id: '123' }),
      })

      await client.request(endpoint, { name: 'John', email: 'john@example.com' })

      const [, options] = (fetch as jest.Mock).mock.calls[0]
      expect(options.method).toBe('POST')
      expect(options.body).toBe('{"name":"John","email":"john@example.com"}')
      expect(options.headers['Content-Type']).toBe('application/json')
    })
  })

  describe('Error Handling', () => {
    it('should throw error on HTTP error status', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        auth: { type: 'none' },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'getData',
        method: 'GET',
        path: '/data',
      }

      ;(global.fetch as jest.Mock).mockResolvedValue({
        ok: false,
        status: 404,
        text: async () => 'Not found',
      })

      await expect(client.request(endpoint, {})).rejects.toThrow('HTTP 404: Not found')
      expect(mockInteractor.error).toHaveBeenCalled()
    })

    it('should throw error on required parameter missing', async () => {
      const config: HttpIntegrationConfig = {
        type: 'http',
        baseUrl: 'https://api.example.com',
        auth: { type: 'none' },
        endpoints: [],
      }

      client = new HttpClient(config, mockInteractor, 'test')

      const endpoint: HttpEndpoint = {
        name: 'createUser',
        method: 'POST',
        path: '/users',
        params: [{ name: 'name', type: 'string', required: true }],
      }

      await expect(client.request(endpoint, {})).rejects.toThrow("Required parameter 'name' is missing")
    })
  })
})

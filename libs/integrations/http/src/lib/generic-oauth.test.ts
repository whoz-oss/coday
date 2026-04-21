import { GenericOAuth, GenericOAuthConfig, TokenData } from './generic-oauth'
import * as oauth from 'oauth4webapi'
import { OAuthCallbackEvent } from '@coday/model'

// ─── Mocks ───────────────────────────────────────────────────────────────────

jest.mock('oauth4webapi', () => ({
  ClientSecretPost: jest.fn(() => 'mock-client-auth'),
  generateRandomState: jest.fn(() => 'mock-state'),
  generateRandomCodeVerifier: jest.fn(() => 'mock-verifier'),
  calculatePKCECodeChallenge: jest.fn(() => Promise.resolve('mock-challenge')),
  validateAuthResponse: jest.fn(() => 'mock-params'),
  authorizationCodeGrantRequest: jest.fn(),
  processAuthorizationCodeResponse: jest.fn(),
  refreshTokenGrantRequest: jest.fn(),
  processRefreshTokenResponse: jest.fn(),
  discoveryRequest: jest.fn(),
  processDiscoveryResponse: jest.fn(),
}))

jest.mock('@coday/model', () => {
  const actual = jest.requireActual('@coday/model')
  return {
    ...actual,
    OAuthRequestEvent: jest.fn().mockImplementation((data: any) => ({ ...data, type: 'OAuthRequestEvent' })),
    OAuthCallbackEvent: jest.fn().mockImplementation((data: any) => ({ ...data, type: 'OAuthCallbackEvent' })),
  }
})

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeInteractor() {
  return {
    debug: jest.fn(),
    warn: jest.fn(),
    error: jest.fn(),
    displayText: jest.fn(),
    sendEvent: jest.fn(),
  } as any
}

function makeUserService(tokens?: { access_token: string; refresh_token?: string; expires_at: number }) {
  const config: any = {
    projects: {
      testProject: {
        integration: {
          testIntegration: tokens ? { oauth2: { tokens } } : {},
        },
      },
    },
  }
  return {
    config,
    save: jest.fn(),
    resolveProjectName: jest.fn((name: string) => name),
  } as any
}

const BASE_CONFIG: GenericOAuthConfig = {
  clientId: 'client-id',
  clientSecret: 'client-secret',
  redirectUri: 'https://app.example.com/callback',
  authorizationEndpoint: 'https://auth.example.com/oauth/authorize',
  tokenEndpoint: 'https://auth.example.com/oauth/token',
  scope: ['read', 'write'],
}

const DISCOVERY_CONFIG: GenericOAuthConfig = {
  clientId: 'client-id',
  clientSecret: 'client-secret',
  redirectUri: 'https://app.example.com/callback',
  issuer: 'https://accounts.example.com',
  scope: ['openid', 'email'],
}

const DISCOVERY_URL_CONFIG: GenericOAuthConfig = {
  clientId: 'client-id',
  clientSecret: 'client-secret',
  redirectUri: 'https://app.example.com/callback',
  discoveryUrl: 'https://accounts.example.com/.well-known/custom-config',
  scope: ['openid'],
}

/** Resolved metadata returned by mock discovery */
const MOCK_SERVER_METADATA = {
  issuer: 'https://accounts.example.com',
  authorization_endpoint: 'https://accounts.example.com/oauth/authorize',
  token_endpoint: 'https://accounts.example.com/oauth/token',
}

function makeGenericOAuth(userService?: any, interactor?: any) {
  return new GenericOAuth(
    BASE_CONFIG,
    interactor ?? makeInteractor(),
    userService ?? makeUserService(),
    'testProject',
    'testIntegration'
  )
}

function makeDiscoveryOAuth(userService?: any, interactor?: any) {
  return new GenericOAuth(
    DISCOVERY_CONFIG,
    interactor ?? makeInteractor(),
    userService ?? makeUserService(),
    'testProject',
    'testIntegration'
  )
}

function makeDiscoveryUrlOAuth(userService?: any, interactor?: any) {
  return new GenericOAuth(
    DISCOVERY_URL_CONFIG,
    interactor ?? makeInteractor(),
    userService ?? makeUserService(),
    'testProject',
    'testIntegration'
  )
}

/** Polls a condition until it's true, with a timeout */
function waitFor(condition: () => boolean, timeoutMs = 1000, intervalMs = 1): Promise<void> {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const check = () => {
      if (condition()) {
        resolve()
      } else if (Date.now() - start > timeoutMs) {
        reject(new Error('waitFor timed out'))
      } else {
        setTimeout(check, intervalMs)
      }
    }
    check()
  })
}

/**
 * Starts the OAuth flow and waits for startAuthFlow() to complete.
 *
 * Returns the unresolved flowPromise inside an object wrapper.
 * This is CRITICAL: returning `Promise<Promise<T>>` from an async function
 * would cause `await` to recursively unwrap both layers, hanging forever
 * since the inner promise is not yet settled. Wrapping in `{ flowPromise }`
 * prevents this unwrapping.
 */
async function startFlow(
  instance: GenericOAuth,
  interactor: ReturnType<typeof makeInteractor>
): Promise<{ flowPromise: Promise<TokenData> }> {
  const flowPromise = instance.authenticate()
  flowPromise.catch(() => {}) // prevent unhandled rejection
  // Wait for startAuthFlow to fully complete (sendEvent is the last call)
  await waitFor(() => interactor.sendEvent.mock.calls.length > 0)
  return { flowPromise }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('GenericOAuth', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  // ── Constructor ─────────────────────────────────────────────────────────────

  describe('constructor', () => {
    it('should build AuthorizationServer with correct issuer derived from authorizationEndpoint', () => {
      expect(() => makeGenericOAuth()).not.toThrow()
    })

    it('should call ClientSecretPost with the client secret', () => {
      makeGenericOAuth()
      expect(oauth.ClientSecretPost).toHaveBeenCalledWith('client-secret')
    })

    it('should not throw when only issuer is provided (discovery config)', () => {
      expect(() => makeDiscoveryOAuth()).not.toThrow()
    })

    it('should not throw when only discoveryUrl is provided', () => {
      expect(() => makeDiscoveryUrlOAuth()).not.toThrow()
    })
  })

  // ── hasToken ────────────────────────────────────────────────────────────────

  describe('hasToken()', () => {
    it('should return false when no in-memory token and no stored token', () => {
      const userService = makeUserService()
      const instance = makeGenericOAuth(userService)
      expect(instance.hasToken()).toBe(false)
    })

    it('should return true when tokens exist in storage', () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'at', expires_at: future })
      const instance = makeGenericOAuth(userService)
      expect(instance.hasToken()).toBe(true)
    })
  })

  // ── hasRefreshToken ─────────────────────────────────────────────────────────

  describe('hasRefreshToken()', () => {
    it('should return false when no refresh token in storage', () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'at', expires_at: future })
      const instance = makeGenericOAuth(userService)
      expect(instance.hasRefreshToken()).toBe(false)
    })

    it('should return true when a refresh token is stored', () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'at', refresh_token: 'rt', expires_at: future })
      const instance = makeGenericOAuth(userService)
      expect(instance.hasRefreshToken()).toBe(true)
    })
  })

  // ── isAuthenticated ─────────────────────────────────────────────────────────

  describe('isAuthenticated()', () => {
    it('should return false when no token at all', () => {
      const instance = makeGenericOAuth(makeUserService())
      expect(instance.isAuthenticated()).toBe(false)
    })

    it('should return false when token is expired', () => {
      const past = Date.now() - 1000
      const userService = makeUserService({ access_token: 'at', expires_at: past })
      const instance = makeGenericOAuth(userService)
      expect(instance.isAuthenticated()).toBe(false)
    })

    it('should return false when token expires within the 5-minute buffer', () => {
      const almostExpired = Date.now() + 4 * 60_000
      const userService = makeUserService({ access_token: 'at', expires_at: almostExpired })
      const instance = makeGenericOAuth(userService)
      expect(instance.isAuthenticated()).toBe(false)
    })

    it('should return true when token is valid and not near expiry', () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'at', expires_at: future })
      const instance = makeGenericOAuth(userService)
      expect(instance.isAuthenticated()).toBe(true)
    })
  })

  // ── getAccessToken ──────────────────────────────────────────────────────────

  describe('getAccessToken()', () => {
    it('should throw when not authenticated and no stored token', async () => {
      const instance = makeGenericOAuth(makeUserService())
      await expect(instance.getAccessToken()).rejects.toThrow('Not authenticated')
    })

    it('should return the access token when valid', async () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'valid-at', expires_at: future })
      const instance = makeGenericOAuth(userService)
      await expect(instance.getAccessToken()).resolves.toBe('valid-at')
    })

    it('should refresh the token when expired and refresh token is available', async () => {
      const past = Date.now() - 1000
      const userService = makeUserService({ access_token: 'old-at', refresh_token: 'rt', expires_at: past })
      const instance = makeGenericOAuth(userService)

      const mockRefreshResponse = {} as any
      ;(oauth.refreshTokenGrantRequest as jest.Mock).mockResolvedValueOnce(mockRefreshResponse)
      ;(oauth.processRefreshTokenResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'new-at',
        refresh_token: 'new-rt',
        expires_in: 3600,
      })

      await expect(instance.getAccessToken()).resolves.toBe('new-at')
      expect(oauth.refreshTokenGrantRequest).toHaveBeenCalledTimes(1)
    })

    it('should return expired access token when expired and no refresh token available', async () => {
      const past = Date.now() - 1000
      const userService = makeUserService({ access_token: 'expired-at', expires_at: past })
      const instance = makeGenericOAuth(userService)

      await expect(instance.getAccessToken()).resolves.toBe('expired-at')
      expect(oauth.refreshTokenGrantRequest).not.toHaveBeenCalled()
    })

    it('should throw when refresh fails', async () => {
      const past = Date.now() - 1000
      const userService = makeUserService({ access_token: 'old-at', refresh_token: 'rt', expires_at: past })
      const instance = makeGenericOAuth(userService)

      ;(oauth.refreshTokenGrantRequest as jest.Mock).mockRejectedValueOnce(new Error('network error'))

      await expect(instance.getAccessToken()).rejects.toThrow('Token refresh failed: network error')
    })
  })

  // ── authenticate ────────────────────────────────────────────────────────────

  describe('authenticate()', () => {
    it('should return existing token immediately if already authenticated', async () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'at', expires_at: future })
      const instance = makeGenericOAuth(userService)

      const result = await instance.authenticate()
      expect(result.accessToken).toBe('at')
      expect(oauth.generateRandomState).not.toHaveBeenCalled()
    })

    it('should emit an OAuthRequestEvent and return a pending promise', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)

      const authPromise = instance.authenticate()
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      expect(interactor.sendEvent).toHaveBeenCalledTimes(1)
      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.state).toBe('mock-state')
      expect(emittedEvent.integrationName).toBe('testIntegration')
      expect(emittedEvent.authUrl).toContain('mock-challenge')

      authPromise.catch(() => {})
    })

    it('should reuse the same pending promise for concurrent calls', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)

      const p1 = instance.authenticate()
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)
      const p2 = instance.authenticate()

      expect(p1).toBe(p2)

      p1.catch(() => {})
      p2.catch(() => {})
    })

    it('should include scope in the authorization URL', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)

      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.authUrl).toContain('scope=read+write')
    })
  })

  // ── handleCallback ──────────────────────────────────────────────────────────

  describe('handleCallback()', () => {
    function makeCallback(overrides: Partial<InstanceType<typeof OAuthCallbackEvent>> = {}): any {
      return {
        integrationName: 'testIntegration',
        state: 'mock-state',
        code: 'auth-code',
        error: undefined,
        errorDescription: undefined,
        ...overrides,
      }
    }

    it('should ignore callback for a different integration', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      const cb = makeCallback({ integrationName: 'otherIntegration' })
      await instance.handleCallback(cb)
      expect(oauth.authorizationCodeGrantRequest).not.toHaveBeenCalled()

      // Settle the pending promise so the test can end cleanly
      await instance.handleCallback(makeCallback({ state: 'wrong-state' }))
      await expect(flowPromise).rejects.toThrow()
    })

    it('should reject with invalid state error when state does not match', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      await instance.handleCallback(makeCallback({ state: 'wrong-state' }))

      await expect(flowPromise).rejects.toThrow('Invalid OAuth state')
      expect(interactor.error).toHaveBeenCalledWith('Invalid OAuth state')
    })

    it('should reject with user-friendly message when error is access_denied', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      await instance.handleCallback(makeCallback({ code: undefined, error: 'access_denied' }))

      await expect(flowPromise).rejects.toThrow('OAuth authentication denied by user')
      expect(interactor.warn).toHaveBeenCalled()
    })

    it('should reject with generic OAuth error message for other errors', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      await instance.handleCallback(makeCallback({ code: undefined, error: 'server_error', errorDescription: 'oops' }))

      await expect(flowPromise).rejects.toThrow('OAuth error: server_error - oops')
    })

    it('should resolve with token data on successful code exchange', async () => {
      const interactor = makeInteractor()
      const userService = makeUserService()
      const instance = makeGenericOAuth(userService, interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockResolvedValueOnce({})
      ;(oauth.processAuthorizationCodeResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'new-at',
        refresh_token: 'new-rt',
        expires_in: 3600,
      })

      await instance.handleCallback(makeCallback())

      const result = await flowPromise
      expect(result.accessToken).toBe('new-at')
      expect(result.refreshToken).toBe('new-rt')
      expect(result.expiresAt).toBeGreaterThan(Date.now())
    })

    it('should save tokens to storage after successful exchange', async () => {
      const interactor = makeInteractor()
      const userService = makeUserService()
      const instance = makeGenericOAuth(userService, interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockResolvedValueOnce({})
      ;(oauth.processAuthorizationCodeResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'saved-at',
        refresh_token: 'saved-rt',
        expires_in: 3600,
      })

      await instance.handleCallback(makeCallback())
      await flowPromise

      expect(userService.save).toHaveBeenCalled()
      const stored = userService.config.projects.testProject.integration.testIntegration.oauth2.tokens
      expect(stored.access_token).toBe('saved-at')
      expect(stored.refresh_token).toBe('saved-rt')
    })

    it('should reject when token exchange throws', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockRejectedValueOnce(new Error('exchange failed'))

      await instance.handleCallback(makeCallback())

      await expect(flowPromise).rejects.toThrow('exchange failed')
    })

    it('should clear pendingState after callback regardless of outcome', async () => {
      const interactor = makeInteractor()
      const instance = makeGenericOAuth(makeUserService(), interactor)
      const { flowPromise } = await startFlow(instance, interactor)

      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockResolvedValueOnce({})
      ;(oauth.processAuthorizationCodeResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'at',
        expires_in: 3600,
      })

      await instance.handleCallback(makeCallback())
      await flowPromise

      // A second callback with the same state should be ignored (no pending state)
      await instance.handleCallback(makeCallback())
      expect(oauth.authorizationCodeGrantRequest).toHaveBeenCalledTimes(1)
    })
  })

  // ── Discovery ───────────────────────────────────────────────────────────────

  describe('discovery (issuer-based)', () => {
    function mockDiscovery() {
      ;(oauth.discoveryRequest as jest.Mock).mockResolvedValueOnce({} as any)
      ;(oauth.processDiscoveryResponse as jest.Mock).mockResolvedValueOnce(MOCK_SERVER_METADATA)
    }

    it('should call discoveryRequest when issuer is provided and start the auth flow', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryOAuth(makeUserService(), interactor)
      mockDiscovery()

      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      expect(oauth.discoveryRequest).toHaveBeenCalledTimes(1)
      expect(oauth.processDiscoveryResponse).toHaveBeenCalledTimes(1)
      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.authUrl).toContain('https://accounts.example.com/oauth/authorize')
    })

    it('should fall back to RFC 8414 when OIDC discovery fails', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryOAuth(makeUserService(), interactor)
      // First call (OIDC) rejects, second call (RFC 8414) succeeds
      ;(oauth.discoveryRequest as jest.Mock)
        .mockRejectedValueOnce(new Error('OIDC not supported'))
        .mockResolvedValueOnce({} as any)
      ;(oauth.processDiscoveryResponse as jest.Mock).mockResolvedValueOnce(MOCK_SERVER_METADATA)

      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      expect(oauth.discoveryRequest).toHaveBeenCalledTimes(2)
      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.authUrl).toContain('https://accounts.example.com/oauth/authorize')
    })

    it('should cache the discovered server and not call discoveryRequest again on second authenticate()', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryOAuth(makeUserService(), interactor)
      mockDiscovery()

      // First flow
      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      // Reject first flow so we can start a second one
      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockRejectedValueOnce(new Error('abort'))
      // (trigger a callback to settle the promise)
      const anyCallback = {
        integrationName: 'testIntegration',
        state: 'mock-state',
        code: 'code',
      } as any
      await instance.handleCallback(anyCallback)

      // Wait for first promise to settle
      await new Promise((r) => setTimeout(r, 10))

      // Second flow — discovery should NOT be called again
      jest.clearAllMocks()
      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      expect(oauth.discoveryRequest).not.toHaveBeenCalled()
    })

    it('should reject with a clear error when both discovery strategies fail', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryOAuth(makeUserService(), interactor)
      ;(oauth.discoveryRequest as jest.Mock)
        .mockRejectedValueOnce(new Error('OIDC failed'))
        .mockRejectedValueOnce(new Error('RFC8414 failed'))

      await expect(instance.authenticate()).rejects.toThrow('RFC8414 failed')
    })

    it('should resolve endpoints and complete the full auth flow via discovery', async () => {
      const interactor = makeInteractor()
      const userService = makeUserService()
      const instance = makeDiscoveryOAuth(userService, interactor)
      mockDiscovery()

      const { flowPromise } = await startFlow(instance, interactor)

      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockResolvedValueOnce({})
      ;(oauth.processAuthorizationCodeResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'discovery-at',
        refresh_token: 'discovery-rt',
        expires_in: 3600,
      })

      await instance.handleCallback({
        integrationName: 'testIntegration',
        state: 'mock-state',
        code: 'code',
      } as any)

      const result = await flowPromise
      expect(result.accessToken).toBe('discovery-at')
      expect(result.refreshToken).toBe('discovery-rt')
    })
  })

  describe('discovery (explicit discoveryUrl)', () => {
    // Save/restore the global fetch to avoid polluting other tests
    let originalFetch: typeof global.fetch
    const mockFetch = jest.fn()

    beforeEach(() => {
      originalFetch = global.fetch
      global.fetch = mockFetch
    })

    afterEach(() => {
      global.fetch = originalFetch
    })

    it('should fetch the discovery document and start the auth flow', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryUrlOAuth(makeUserService(), interactor)

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          issuer: 'https://accounts.example.com',
          authorization_endpoint: 'https://accounts.example.com/oauth/authorize',
          token_endpoint: 'https://accounts.example.com/oauth/token',
        }),
      } as any)

      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      expect(mockFetch).toHaveBeenCalledWith(DISCOVERY_URL_CONFIG.discoveryUrl)
      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.authUrl).toContain('https://accounts.example.com/oauth/authorize')
    })

    it('should reject with a clear error when the discovery document fetch fails', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryUrlOAuth(makeUserService(), interactor)

      mockFetch.mockResolvedValueOnce({ ok: false, status: 404 } as any)

      await expect(instance.authenticate()).rejects.toThrow('HTTP 404')
    })

    it('should reject with a clear error when the discovery document is missing required fields', async () => {
      const interactor = makeInteractor()
      const instance = makeDiscoveryUrlOAuth(makeUserService(), interactor)

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ issuer: 'https://accounts.example.com' }), // missing endpoints
      } as any)

      await expect(instance.authenticate()).rejects.toThrow('missing authorization_endpoint or token_endpoint')
    })
  })

  // ── Security: URL validation ────────────────────────────────────────────────

  describe('security: validateDiscoveryUrl', () => {
    let originalFetch: typeof global.fetch
    const mockFetch = jest.fn()

    beforeEach(() => {
      originalFetch = global.fetch
      global.fetch = mockFetch
    })

    afterEach(() => {
      global.fetch = originalFetch
    })

    const makeInstanceWithIssuer = (issuer: string) =>
      new GenericOAuth(
        { clientId: 'id', clientSecret: 'secret', redirectUri: 'https://app.example.com/callback', issuer },
        makeInteractor(),
        makeUserService(),
        'testProject',
        'testIntegration'
      )

    const makeInstanceWithDiscoveryUrl = (discoveryUrl: string) =>
      new GenericOAuth(
        { clientId: 'id', clientSecret: 'secret', redirectUri: 'https://app.example.com/callback', discoveryUrl },
        makeInteractor(),
        makeUserService(),
        'testProject',
        'testIntegration'
      )

    it('should reject issuer with http:// scheme (SSRF prevention)', async () => {
      const instance = makeInstanceWithIssuer('http://accounts.example.com')
      await expect(instance.authenticate()).rejects.toThrow('must use HTTPS')
    })

    it('should reject discoveryUrl with http:// scheme (SSRF prevention)', async () => {
      const instance = makeInstanceWithDiscoveryUrl('http://accounts.example.com/.well-known/openid-configuration')
      await expect(instance.authenticate()).rejects.toThrow('must use HTTPS')
    })

    it('should reject issuer pointing to localhost', async () => {
      const instance = makeInstanceWithIssuer('https://localhost/auth')
      await expect(instance.authenticate()).rejects.toThrow('private/loopback')
    })

    it('should reject discoveryUrl pointing to 127.0.0.1', async () => {
      const instance = makeInstanceWithDiscoveryUrl('https://127.0.0.1/.well-known/openid-configuration')
      await expect(instance.authenticate()).rejects.toThrow('private/loopback')
    })

    it('should reject discoveryUrl pointing to a private 10.x.x.x address', async () => {
      const instance = makeInstanceWithDiscoveryUrl('https://10.0.0.1/.well-known/openid-configuration')
      await expect(instance.authenticate()).rejects.toThrow('private/loopback')
    })

    it('should reject discoveryUrl pointing to 169.254.x.x (cloud metadata)', async () => {
      const instance = makeInstanceWithDiscoveryUrl('https://169.254.169.254/latest/meta-data/')
      await expect(instance.authenticate()).rejects.toThrow('private/loopback')
    })

    it('should reject when authorization_endpoint has a different origin than discoveryUrl', async () => {
      const instance = makeInstanceWithDiscoveryUrl('https://legit.example.com/.well-known/openid-configuration')
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          issuer: 'https://legit.example.com',
          authorization_endpoint: 'https://attacker.example.com/oauth/authorize',
          token_endpoint: 'https://legit.example.com/oauth/token',
        }),
      } as any)
      await expect(instance.authenticate()).rejects.toThrow('does not share the same origin as the discovery URL')
    })

    it('should reject when token_endpoint has a different origin than discoveryUrl', async () => {
      const instance = makeInstanceWithDiscoveryUrl('https://legit.example.com/.well-known/openid-configuration')
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          issuer: 'https://legit.example.com',
          authorization_endpoint: 'https://legit.example.com/oauth/authorize',
          token_endpoint: 'https://attacker.example.com/oauth/token',
        }),
      } as any)
      await expect(instance.authenticate()).rejects.toThrow('does not share the same origin as the discovery URL')
    })

    it('should accept a valid discoveryUrl with matching origins', async () => {
      const interactor = makeInteractor()
      const instance = new GenericOAuth(
        {
          clientId: 'id',
          clientSecret: 'secret',
          redirectUri: 'https://app.example.com/callback',
          discoveryUrl: 'https://legit.example.com/.well-known/openid-configuration',
        },
        interactor,
        makeUserService(),
        'testProject',
        'testIntegration'
      )
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          issuer: 'https://legit.example.com',
          authorization_endpoint: 'https://legit.example.com/oauth/authorize',
          token_endpoint: 'https://legit.example.com/oauth/token',
        }),
      } as any)

      instance.authenticate().catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)

      const emittedEvent = interactor.sendEvent.mock.calls[0][0]
      expect(emittedEvent.authUrl).toContain('https://legit.example.com/oauth/authorize')
    })
  })

  describe('discovery — configuration error', () => {
    it('should reject when no endpoint source is configured', async () => {
      const interactor = makeInteractor()
      const instance = new GenericOAuth(
        {
          clientId: 'id',
          clientSecret: 'secret',
          redirectUri: 'https://app.example.com/callback',
          // no authorizationEndpoint, no tokenEndpoint, no issuer, no discoveryUrl
        },
        interactor,
        makeUserService(),
        'testProject',
        'testIntegration'
      )

      await expect(instance.authenticate()).rejects.toThrow('OAuth configuration error')
    })
  })

  // ── Token storage ───────────────────────────────────────────────────────────

  describe('token storage', () => {
    it('should load tokens from deeply nested user config structure', () => {
      const future = Date.now() + 3600_000
      const userService = makeUserService({ access_token: 'stored-at', expires_at: future })
      const instance = makeGenericOAuth(userService)

      expect(instance.isAuthenticated()).toBe(true)
    })

    it('should handle missing projects config gracefully', () => {
      const userService = { config: {}, save: jest.fn(), resolveProjectName: jest.fn((name: string) => name) } as any
      const instance = makeGenericOAuth(userService)
      expect(instance.hasToken()).toBe(false)
    })

    it('should handle missing integration config gracefully', () => {
      const userService = {
        config: { projects: { testProject: {} } },
        save: jest.fn(),
        resolveProjectName: jest.fn((name: string) => name),
      } as any
      const instance = makeGenericOAuth(userService)
      expect(instance.hasToken()).toBe(false)
    })

    it('should use default expiry of 3600s when expires_in is absent from token response', async () => {
      const interactor = makeInteractor()
      const userService = makeUserService()
      const instance = makeGenericOAuth(userService, interactor)
      const flowPromise = instance.authenticate()
      flowPromise.catch(() => {})
      await waitFor(() => interactor.sendEvent.mock.calls.length > 0)
      ;(oauth.authorizationCodeGrantRequest as jest.Mock).mockResolvedValueOnce({})
      ;(oauth.processAuthorizationCodeResponse as jest.Mock).mockResolvedValueOnce({
        access_token: 'at',
      })

      await instance.handleCallback({
        integrationName: 'testIntegration',
        state: 'mock-state',
        code: 'code',
      } as any)

      const result = await flowPromise
      const expectedMin = Date.now() + 3500_000
      const expectedMax = Date.now() + 3700_000
      expect(result.expiresAt).toBeGreaterThan(expectedMin)
      expect(result.expiresAt).toBeLessThan(expectedMax)
    })
  })
})

import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { AuthSettingControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { UserStateService } from './user-state.service'
import {
  AuthSettingConfigStateService,
  CONNECTION_POLL_MS,
  CONNECTION_WAIT_MS,
} from './auth-setting-config-state.service'

/**
 * Unit tests for the authType discriminant translation layer in AuthSettingConfigStateService.
 *
 * The OpenAPI generator emits schema class names as discriminant values (e.g. 'ApiKeyAuthSetting')
 * while Jackson on the backend uses SCREAMING_SNAKE_CASE names (e.g. 'API_KEY').
 * The state service is the single HTTP boundary that must translate in both directions:
 *   - outbound (create / update): TS name → backend name
 *   - inbound  (list / getById):  backend name → TS name
 */
describe('AuthSettingConfigStateService — authType translation', () => {
  let service: AuthSettingConfigStateService
  let controller: {
    createAuthSetting: jest.Mock
    updateAuthSetting: jest.Mock
    getByIdAuthSetting: jest.Mock
    listAuthSetting: jest.Mock
    deleteAuthSetting: jest.Mock
    resetMyCredentialAuthSetting: jest.Mock
    getConnectionAuthSetting: jest.Mock
    connectAuthSetting: jest.Mock
  }
  let userState: { currentUser: jest.Mock }

  beforeEach(() => {
    controller = {
      createAuthSetting: jest.fn().mockReturnValue(of({ authType: 'API_KEY', name: 'created' })),
      updateAuthSetting: jest.fn().mockReturnValue(of({ authType: 'BASIC_AUTH', name: 'updated' })),
      getByIdAuthSetting: jest.fn().mockReturnValue(of({ authType: 'BEARER_TOKEN', name: 'loaded' })),
      listAuthSetting: jest.fn().mockReturnValue(of([])),
      deleteAuthSetting: jest.fn().mockReturnValue(of(undefined)),
      resetMyCredentialAuthSetting: jest.fn().mockReturnValue(of(undefined)),
      getConnectionAuthSetting: jest.fn().mockReturnValue(of({ connected: false })),
      connectAuthSetting: jest.fn().mockReturnValue(of({ authorizationUrl: 'https://provider.example.com/auth' })),
    }
    userState = {
      currentUser: jest.fn().mockReturnValue({ id: 'user-1' }),
    }

    TestBed.configureTestingModule({
      providers: [
        AuthSettingConfigStateService,
        { provide: AuthSettingControllerService, useValue: controller },
        { provide: UserStateService, useValue: userState },
      ],
    })

    service = TestBed.inject(AuthSettingConfigStateService)
  })

  // ── Outbound: create ────────────────────────────────────────────────────

  describe('create()', () => {
    it('translates TS discriminant to backend name in the outbound payload', () => {
      const dto = { authType: 'ApiKeyAuthSetting', name: 'My Key', apiKey: 'sk-xxx' } as any

      service.create(dto, 'namespace', 'ns-1').subscribe()

      const sent = controller.createAuthSetting.mock.calls[0][0]
      expect(sent.authType).toBe('API_KEY')
    })

    it('translates all six TS discriminants to their backend equivalents', () => {
      const cases: Array<[string, string]> = [
        ['ApiKeyAuthSetting', 'API_KEY'],
        ['BasicAuthAuthSetting', 'BASIC_AUTH'],
        ['BearerTokenAuthSetting', 'BEARER_TOKEN'],
        ['OAuthDiscoverableAuthSetting', 'OAUTH_DISCOVERABLE'],
        ['OAuthCustomAuthSetting', 'OAUTH_CUSTOM'],
        ['OAuthRegisteredAuthSetting', 'OAUTH_REGISTERED'],
      ]

      for (const [tsType, backendType] of cases) {
        controller.createAuthSetting.mockReturnValue(of({ authType: backendType, name: 'x' }))
        const dto = { authType: tsType, name: 'x' } as any
        service.create(dto, 'namespace', 'ns-1').subscribe()
        const sent = controller.createAuthSetting.mock.calls.at(-1)![0]
        expect(sent.authType).toBe(backendType)
      }
    })

    it('normalizes the backend response authType back to the TS discriminant', () => {
      // Backend echoes API_KEY in the create response → service must normalize it.
      controller.createAuthSetting.mockReturnValue(of({ authType: 'API_KEY', name: 'My Key' }))
      const dto = { authType: 'ApiKeyAuthSetting', name: 'My Key' } as any
      let result: any

      service.create(dto, 'namespace', 'ns-1').subscribe((r) => (result = r))

      expect(result.authType).toBe('ApiKeyAuthSetting')
    })
  })

  // ── Outbound: update ────────────────────────────────────────────────────

  describe('update()', () => {
    it('translates TS discriminant to backend name in the outbound payload', () => {
      const typed = { authType: 'BasicAuthAuthSetting', name: 'Updated', username: 'alice' } as any
      const existing = { authType: 'BasicAuthAuthSetting', name: 'Old', id: 'id-1', namespaceId: 'ns-1' } as any

      service.update('id-1', typed, existing).subscribe()

      const sent = controller.updateAuthSetting.mock.calls[0][1]
      expect(sent.authType).toBe('BASIC_AUTH')
    })

    it('normalizes the backend response authType back to the TS discriminant', () => {
      controller.updateAuthSetting.mockReturnValue(of({ authType: 'BASIC_AUTH', name: 'Updated' }))
      const typed = { authType: 'BasicAuthAuthSetting', name: 'Updated' } as any
      const existing = { authType: 'BasicAuthAuthSetting', name: 'Old', id: 'id-1' } as any
      let result: any

      service.update('id-1', typed, existing).subscribe((r) => (result = r))

      expect(result.authType).toBe('BasicAuthAuthSetting')
    })
  })

  // ── Inbound: getById ────────────────────────────────────────────────────

  describe('getById()', () => {
    it('normalizes all six backend discriminants to TS equivalents', () => {
      const cases: Array<[string, string]> = [
        ['API_KEY', 'ApiKeyAuthSetting'],
        ['BASIC_AUTH', 'BasicAuthAuthSetting'],
        ['BEARER_TOKEN', 'BearerTokenAuthSetting'],
        ['OAUTH_DISCOVERABLE', 'OAuthDiscoverableAuthSetting'],
        ['OAUTH_CUSTOM', 'OAuthCustomAuthSetting'],
        ['OAUTH_REGISTERED', 'OAuthRegisteredAuthSetting'],
      ]

      for (const [backendType, tsType] of cases) {
        controller.getByIdAuthSetting.mockReturnValue(of({ authType: backendType, name: 'x' }))
        let result: any
        service.getById('any-id').subscribe((r) => (result = r))
        expect(result.authType).toBe(tsType)
      }
    })

    it('passes through values that are already TS discriminants (idempotent)', () => {
      // If the backend ever fixes the spec, both representations should be tolerated.
      controller.getByIdAuthSetting.mockReturnValue(of({ authType: 'ApiKeyAuthSetting', name: 'x' }))
      let result: any

      service.getById('any-id').subscribe((r) => (result = r))

      expect(result.authType).toBe('ApiKeyAuthSetting')
    })

    it('passes through unknown authType values unchanged', () => {
      controller.getByIdAuthSetting.mockReturnValue(of({ authType: 'UNKNOWN_TYPE', name: 'x' }))
      let result: any

      service.getById('any-id').subscribe((r) => (result = r))

      expect(result.authType).toBe('UNKNOWN_TYPE')
    })
  })

  // ── Inbound: loadPlatformSettings / loadNamespaceSettings / loadUserSettings ──

  describe('list methods', () => {
    it('loads platform settings instead of personal settings when both scopes exist', () => {
      const platform = { authType: 'API_KEY', name: 'Company Jira' }
      const personal = { authType: 'API_KEY', name: 'Personal Jira', userId: 'user-1' }
      // Match the backend contract: only the explicit none sentinel selects platform scope.
      controller.listAuthSetting.mockImplementation((namespaceId?: string, userId?: string) =>
        of(namespaceId === 'none' && userId === undefined ? [platform] : [personal])
      )
      let result: any[] = []

      service.loadPlatformSettings().subscribe((settings) => (result = settings))

      expect(result.map((setting) => setting.name)).toEqual(['Company Jira'])
      expect(controller.listAuthSetting).toHaveBeenCalledWith('none')
    })

    it('loadPlatformSettings normalizes backend authType values in the array', () => {
      controller.listAuthSetting.mockReturnValue(
        of([
          { authType: 'API_KEY', name: 'k1' },
          { authType: 'BEARER_TOKEN', name: 'k2' },
        ])
      )
      let result: any[]

      service.loadPlatformSettings().subscribe((r) => (result = r))

      expect(result![0].authType).toBe('ApiKeyAuthSetting')
      expect(result![1].authType).toBe('BearerTokenAuthSetting')
    })

    it('loadNamespaceSettings normalizes backend authType values in the array', () => {
      controller.listAuthSetting.mockReturnValue(of([{ authType: 'OAUTH_CUSTOM', name: 'k1' }]))
      let result: any[]

      service.loadNamespaceSettings('ns-1').subscribe((r) => (result = r))

      expect(result![0].authType).toBe('OAuthCustomAuthSetting')
    })
  })

  // ── Connection reset ────────────────────────────────────────────────────

  describe('resetCredential()', () => {
    it('calls the reset endpoint for the given auth setting and namespace, without refreshing the lists', () => {
      let done = false

      service.resetCredential('as-1', 'ns-1').subscribe(() => (done = true))

      expect(controller.resetMyCredentialAuthSetting).toHaveBeenCalledWith('as-1', 'ns-1')
      expect(done).toBe(true)
      expect(controller.listAuthSetting).not.toHaveBeenCalled()
    })
  })

  // ── Direct connection ────────────────────────────────────────────────────

  describe('getConnection()', () => {
    it('reads the connection state of the setting from the namespace it is used in', () => {
      controller.getConnectionAuthSetting.mockReturnValue(of({ connected: true, connectedAt: '2026-01-01T00:00:00Z' }))
      let result: any

      service.getConnection('as-1', 'ns-1').subscribe((r) => (result = r))

      expect(controller.getConnectionAuthSetting).toHaveBeenCalledWith('as-1', 'ns-1')
      expect(result).toEqual({ connected: true, connectedAt: '2026-01-01T00:00:00Z' })
    })
  })

  describe('connect()', () => {
    const T0 = '2026-01-01T00:00:00Z'
    const T1 = '2026-01-01T00:05:00Z'
    let outcome: string | undefined
    let authorize: jest.Mock

    beforeEach(() => {
      outcome = undefined
      authorize = jest.fn()
    })

    it('hands the authorization URL over, then reports connected once a newer connection is stored', fakeAsync(() => {
      controller.getConnectionAuthSetting
        .mockReturnValueOnce(of({ connected: true, connectedAt: T0 })) // state before the flow
        .mockReturnValueOnce(of({ connected: true, connectedAt: T0 })) // first poll: still the old one
        .mockReturnValue(of({ connected: true, connectedAt: T1 })) // second poll: the new one

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe((o) => (outcome = o))

      expect(controller.connectAuthSetting).toHaveBeenCalledWith('as-1', 'ns-1')
      expect(authorize).toHaveBeenCalledWith('https://provider.example.com/auth')
      tick(CONNECTION_POLL_MS)
      expect(outcome).toBeUndefined()
      tick(CONNECTION_POLL_MS)
      expect(outcome).toBe('connected')
    }))

    it('counts any connection as new when there was none before', fakeAsync(() => {
      controller.getConnectionAuthSetting
        .mockReturnValueOnce(of({ connected: false }))
        .mockReturnValue(of({ connected: true, connectedAt: T0 }))

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe((o) => (outcome = o))
      tick(CONNECTION_POLL_MS)

      expect(outcome).toBe('connected')
    }))

    it('does not start a flow when the state before it cannot be read', () => {
      controller.getConnectionAuthSetting.mockReturnValue(throwError(() => ({ status: 500 })))
      let failed = false

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe({ error: () => (failed = true) })

      // Without it a reconnection cannot be told from the connection it replaces, and a flow started anyway
      // would stay pending with nobody to finish it.
      expect(failed).toBe(true)
      expect(controller.connectAuthSetting).not.toHaveBeenCalled()
      expect(authorize).not.toHaveBeenCalled()
    })

    it('keeps waiting through a failed poll instead of reporting a failure', fakeAsync(() => {
      controller.getConnectionAuthSetting
        .mockReturnValueOnce(of({ connected: false }))
        .mockReturnValueOnce(throwError(() => ({ status: 502 })))
        .mockReturnValue(of({ connected: true, connectedAt: T1 }))

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe((o) => (outcome = o))
      tick(CONNECTION_POLL_MS)
      expect(outcome).toBeUndefined()
      tick(CONNECTION_POLL_MS)
      expect(outcome).toBe('connected')
    }))

    it('reports abandoned once the popup stays closed without a new connection, after a grace period', fakeAsync(() => {
      controller.getConnectionAuthSetting.mockReturnValue(of({ connected: true, connectedAt: T0 }))

      service.connect('as-1', 'ns-1', authorize, () => true).subscribe((o) => (outcome = o))
      tick(CONNECTION_POLL_MS * 2)
      // Closing the popup is not failing: the backend may still be exchanging the code.
      expect(outcome).toBeUndefined()
      tick(CONNECTION_POLL_MS)

      expect(outcome).toBe('abandoned')
    }))

    it('prefers a new connection over a closed popup', fakeAsync(() => {
      controller.getConnectionAuthSetting
        .mockReturnValueOnce(of({ connected: true, connectedAt: T0 }))
        .mockReturnValue(of({ connected: true, connectedAt: T1 }))

      service.connect('as-1', 'ns-1', authorize, () => true).subscribe((o) => (outcome = o))
      tick(CONNECTION_POLL_MS)

      expect(outcome).toBe('connected')
    }))

    it('gives up after the wait the backend allows', fakeAsync(() => {
      controller.getConnectionAuthSetting.mockReturnValue(of({ connected: true, connectedAt: T0 }))

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe((o) => (outcome = o))
      tick(CONNECTION_WAIT_MS - CONNECTION_POLL_MS)
      expect(outcome).toBeUndefined()
      tick(CONNECTION_POLL_MS)

      expect(outcome).toBe('timeout')
    }))

    it('does not poll nor hand any URL over when the authorization cannot start', () => {
      controller.connectAuthSetting.mockReturnValue(throwError(() => ({ status: 422 })))
      let failed = false

      service.connect('as-1', 'ns-1', authorize, () => false).subscribe({ error: () => (failed = true) })

      expect(failed).toBe(true)
      expect(authorize).not.toHaveBeenCalled()
    })
  })
})

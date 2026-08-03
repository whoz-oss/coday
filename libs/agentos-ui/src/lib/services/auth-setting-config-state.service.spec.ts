import { TestBed } from '@angular/core/testing'
import { AuthSettingControllerService } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { UserStateService } from './user-state.service'
import { AuthSettingConfigStateService } from './auth-setting-config-state.service'

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
  }
  let userState: { currentUser: jest.Mock }

  beforeEach(() => {
    controller = {
      createAuthSetting: jest.fn().mockReturnValue(of({ authType: 'API_KEY', name: 'created' })),
      updateAuthSetting: jest.fn().mockReturnValue(of({ authType: 'BASIC_AUTH', name: 'updated' })),
      getByIdAuthSetting: jest.fn().mockReturnValue(of({ authType: 'BEARER_TOKEN', name: 'loaded' })),
      listAuthSetting: jest.fn().mockReturnValue(of([])),
      deleteAuthSetting: jest.fn().mockReturnValue(of(undefined)),
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
})

import { TestBed } from '@angular/core/testing'
import {
  AiProvider,
  AiProviderApiTypeEnum,
  AiProviderControllerService,
  UserAiProvider,
  UserAiProviderControllerService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of } from 'rxjs'
import { AiProviderConfigStateService } from './ai-provider-config-state.service'

describe('AiProviderConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'

  const nsProvider: AiProvider = {
    id: 'ns-1',
    namespaceId: NS_ID,
    name: 'NS Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }
  const userOnNsProvider: UserAiProvider = {
    id: 'u-ns-1',
    namespaceId: NS_ID,
    userId: 'me',
    name: 'My NS Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
    apiKey: 'sk-ant-•••••',
  }
  const userGlobalProvider: UserAiProvider = {
    id: 'u-g-1',
    userId: 'me',
    name: 'My Global Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
    apiKey: 'sk-ant-•••••',
  }

  let nsController: jest.Mocked<AiProviderControllerService>
  let userController: jest.Mocked<UserAiProviderControllerService>
  let service: AiProviderConfigStateService

  beforeEach(() => {
    nsController = {
      listByNamespaceIdAiProvider: jest.fn().mockReturnValue(of([nsProvider])),
      createAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
      updateAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
      deleteAiProvider: jest.fn().mockReturnValue(of(undefined)),
      getByIdAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
    } as unknown as jest.Mocked<AiProviderControllerService>

    userController = {
      listUserAiProvider: jest.fn().mockImplementation((namespaceId: string) =>
        of({
          content: namespaceId === 'none' ? [userGlobalProvider] : [userOnNsProvider],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        })
      ),
      createUserAiProvider: jest.fn().mockReturnValue(of(userOnNsProvider)),
      updateUserAiProvider: jest.fn().mockReturnValue(of(userOnNsProvider)),
      deleteUserAiProvider: jest.fn().mockReturnValue(of(undefined)),
      getByIdUserAiProvider: jest.fn().mockReturnValue(of(userOnNsProvider)),
    } as unknown as jest.Mocked<UserAiProviderControllerService>

    TestBed.configureTestingModule({
      providers: [
        AiProviderConfigStateService,
        { provide: AiProviderControllerService, useValue: nsController },
        { provide: UserAiProviderControllerService, useValue: userController },
      ],
    })
    service = TestBed.inject(AiProviderConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model and hides the "none" sentinel', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsProvider])
      expect(vm.userOnNs).toEqual([userOnNsProvider])
      expect(vm.userGlobal).toEqual([userGlobalProvider])

      expect(nsController.listByNamespaceIdAiProvider).toHaveBeenCalledWith(NS_ID)
      expect(userController.listUserAiProvider).toHaveBeenCalledWith(NS_ID, 0, expect.any(Number))
      expect(userController.listUserAiProvider).toHaveBeenCalledWith('none', 0, expect.any(Number))
    })
  })

  describe('loadUserProviders wrapper', () => {
    it('translates "global" into the backend sentinel, never leaking it to callers', async () => {
      const result = await firstValueFrom(service.loadUserProviders('global'))
      expect(result).toEqual([userGlobalProvider])
      expect(userController.listUserAiProvider).toHaveBeenCalledWith('none', 0, expect.any(Number))
    })

    it('passes through a UUID as-is for user × namespace lookups', async () => {
      const result = await firstValueFrom(service.loadUserProviders(NS_ID))
      expect(result).toEqual([userOnNsProvider])
      expect(userController.listUserAiProvider).toHaveBeenCalledWith(NS_ID, 0, expect.any(Number))
    })
  })

  describe('create dispatch', () => {
    const draft = {
      name: 'New',
      apiType: AiProviderApiTypeEnum.Anthropic,
      description: null,
      baseUrl: null,
      apiKey: 'sk-ant-newkey',
    }

    it('routes namespace creates to the namespace controller with the namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      expect(nsController.createAiProvider).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'New', apiType: AiProviderApiTypeEnum.Anthropic, namespaceId: NS_ID })
      )
      expect(userController.createUserAiProvider).not.toHaveBeenCalled()
    })

    it('routes userOnNs creates to the user controller with the namespaceId set', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      expect(userController.createUserAiProvider).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'New', namespaceId: NS_ID })
      )
    })

    it('routes userGlobal creates to the user controller WITHOUT a namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'userGlobal', NS_ID))
      const arg = userController.createUserAiProvider.mock.calls[0][0]
      expect(arg.namespaceId).toBeUndefined()
      expect(arg.name).toBe('New')
    })

    it('throws when creating a namespace-scoped provider without a namespaceId', () => {
      expect(() => service.create(draft, 'namespace', null)).toThrow(/namespaceId/i)
    })

    it('throws when creating a userOnNs provider without a namespaceId', () => {
      expect(() => service.create(draft, 'userOnNs', null)).toThrow(/namespaceId/i)
    })
  })

  describe('apiKey masking on update', () => {
    const baseDraft = {
      name: 'Renamed',
      apiType: AiProviderApiTypeEnum.Anthropic,
      description: null,
      baseUrl: null,
    }

    it('omits apiKey from the payload when draft.apiKey is null (user did not touch the field)', async () => {
      await firstValueFrom(service.update(ITEM_ID, { ...baseDraft, apiKey: null }, 'userOnNs', userOnNsProvider))
      const payload = userController.updateUserAiProvider.mock.calls[0][1]
      expect('apiKey' in payload).toBe(false)
    })

    it('sends the new apiKey when the user typed a new value', async () => {
      await firstValueFrom(
        service.update(ITEM_ID, { ...baseDraft, apiKey: 'sk-ant-fresh' }, 'userOnNs', userOnNsProvider)
      )
      const payload = userController.updateUserAiProvider.mock.calls[0][1]
      expect(payload.apiKey).toBe('sk-ant-fresh')
    })

    it('sends apiKey as empty string when draft.apiKey is the empty string (explicit clear)', async () => {
      // Wire contract: empty string on the wire signals "clear" to the backend. We do not use
      // JSON-null because Jackson collapses null and field-absent into the same Kotlin null,
      // leaving the backend unable to tell preserve from clear.
      await firstValueFrom(service.update(ITEM_ID, { ...baseDraft, apiKey: '' }, 'userOnNs', userOnNsProvider))
      const payload = userController.updateUserAiProvider.mock.calls[0][1]
      expect('apiKey' in payload).toBe(true)
      expect(payload.apiKey).toBe('')
    })
  })

  describe('delete dispatch', () => {
    it('routes namespace deletes to the namespace controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      expect(nsController.deleteAiProvider).toHaveBeenCalledWith(ITEM_ID)
      expect(userController.deleteUserAiProvider).not.toHaveBeenCalled()
    })

    it('routes userOnNs and userGlobal deletes to the user controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.delete(ITEM_ID, 'userGlobal'))
      expect(userController.deleteUserAiProvider).toHaveBeenCalledTimes(2)
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      const sub = service.vm$.subscribe()
      const initialNsCalls = nsController.listByNamespaceIdAiProvider.mock.calls.length
      const initialUserCalls = userController.listUserAiProvider.mock.calls.length

      await firstValueFrom(
        service.create(
          {
            name: 'x',
            apiType: AiProviderApiTypeEnum.OpenAI,
            description: null,
            baseUrl: null,
            apiKey: 'sk-key',
          },
          'userOnNs',
          NS_ID
        )
      )

      expect(nsController.listByNamespaceIdAiProvider.mock.calls.length).toBeGreaterThan(initialNsCalls)
      expect(userController.listUserAiProvider.mock.calls.length).toBeGreaterThan(initialUserCalls)
      sub.unsubscribe()
    })
  })

  describe('getById dispatch', () => {
    it('routes namespace lookups to the namespace controller', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'namespace'))
      expect(nsController.getByIdAiProvider).toHaveBeenCalledWith(ITEM_ID)
    })

    it('routes user-scope lookups to the user controller for both userOnNs and userGlobal', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.getById(ITEM_ID, 'userGlobal'))
      expect(userController.getByIdUserAiProvider).toHaveBeenCalledTimes(2)
    })
  })
})

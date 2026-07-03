import { TestBed } from '@angular/core/testing'
import { AiProvider, AiProviderApiTypeEnum, AiProviderControllerService } from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of } from 'rxjs'
import { AiProviderConfigStateService } from './ai-provider-config-state.service'
import { UserStateService } from './user-state.service'

describe('AiProviderConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'
  const ME_ID = '33333333-3333-3333-3333-333333333333'

  const nsProvider: AiProvider = {
    id: 'ns-1',
    namespaceId: NS_ID,
    name: 'NS Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }
  const userOnNsProvider: AiProvider = {
    id: 'u-ns-1',
    namespaceId: NS_ID,
    userId: ME_ID,
    name: 'My NS Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
    apiKey: 'sk-ant-•••••',
  }
  const userGlobalProvider: AiProvider = {
    id: 'u-g-1',
    userId: ME_ID,
    name: 'My Global Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
    apiKey: 'sk-ant-•••••',
  }

  let nsController: jest.Mocked<AiProviderControllerService>
  let userStateMock: { currentUser: jest.Mock<{ id: string } | null, []> }
  let service: AiProviderConfigStateService

  beforeEach(() => {
    nsController = {
      // Unified `listAiProvider(namespaceId, userId)` returns a flat array.
      // The mock dispatches by query params : `userId === undefined` → NS-shared layer ;
      // `namespaceId === 'none'` → user-global ; both present → user × ns.
      listAiProvider: jest.fn().mockImplementation((namespaceId?: string, userId?: string) => {
        if (!userId) {
          return of([nsProvider])
        }
        if (namespaceId === 'none') {
          return of([userGlobalProvider])
        }
        return of([userOnNsProvider])
      }),
      createAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
      updateAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
      deleteAiProvider: jest.fn().mockReturnValue(of(undefined)),
      getByIdAiProvider: jest.fn().mockReturnValue(of(nsProvider)),
    } as unknown as jest.Mocked<AiProviderControllerService>

    userStateMock = {
      currentUser: jest.fn().mockReturnValue({ id: ME_ID }),
    }

    TestBed.configureTestingModule({
      providers: [
        AiProviderConfigStateService,
        { provide: AiProviderControllerService, useValue: nsController },
        { provide: UserStateService, useValue: userStateMock },
      ],
    })
    service = TestBed.inject(AiProviderConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model and hides the "none" / "me" sentinels', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsProvider])
      expect(vm.userOnNs).toEqual([userOnNsProvider])
      expect(vm.userGlobal).toEqual([userGlobalProvider])

      // NS-shared layer : namespaceId set, userId omitted.
      expect(nsController.listAiProvider).toHaveBeenCalledWith(NS_ID)
      // user × namespace : both NS and userId='me' sentinel.
      expect(nsController.listAiProvider).toHaveBeenCalledWith(NS_ID, 'me')
      // user-global : namespaceId='none', userId='me'.
      expect(nsController.listAiProvider).toHaveBeenCalledWith('none', 'me')
    })
  })

  describe('loadUserProviders wrapper', () => {
    it('translates "global" into the backend sentinel pair (none, me), never leaking it to callers', async () => {
      const result = await firstValueFrom(service.loadUserProviders('global'))
      expect(result).toEqual([userGlobalProvider])
      expect(nsController.listAiProvider).toHaveBeenCalledWith('none', 'me')
    })

    it('passes a UUID for user × namespace and pins userId to "me"', async () => {
      const result = await firstValueFrom(service.loadUserProviders(NS_ID))
      expect(result).toEqual([userOnNsProvider])
      expect(nsController.listAiProvider).toHaveBeenCalledWith(NS_ID, 'me')
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

    it('routes namespace creates to the unified controller with NS only (no userId)', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      const payload = nsController.createAiProvider.mock.calls[0][0]
      expect(payload).toEqual(
        expect.objectContaining({ name: 'New', apiType: AiProviderApiTypeEnum.Anthropic, namespaceId: NS_ID })
      )
      expect(payload.userId).toBeUndefined()
    })

    it('routes userOnNs creates with both namespaceId and userId=currentUser.id', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      const payload = nsController.createAiProvider.mock.calls[0][0]
      expect(payload).toEqual(expect.objectContaining({ name: 'New', namespaceId: NS_ID, userId: ME_ID }))
    })

    it('routes userGlobal creates with userId=currentUser.id and NO namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'userGlobal', NS_ID))
      const payload = nsController.createAiProvider.mock.calls[0][0]
      expect(payload.userId).toBe(ME_ID)
      expect(payload.namespaceId).toBeUndefined()
    })

    it('routes platform creates to the unified controller with namespaceId omitted (null → platform scope)', async () => {
      await firstValueFrom(service.create(draft, 'namespace', null))
      const payload = nsController.createAiProvider.mock.calls[0][0]
      expect(payload.namespaceId).toBeUndefined()
      expect(payload.userId).toBeUndefined()
    })

    it('throws when creating a namespace-scoped provider with an empty namespaceId string', () => {
      expect(() => service.create(draft, 'namespace', '')).toThrow(/namespaceId/i)
    })

    it('throws when creating a userOnNs provider without a namespaceId', () => {
      expect(() => service.create(draft, 'userOnNs', null)).toThrow(/namespaceId/i)
    })

    it('throws when creating a user-scope provider before UserStateService.loadMe() resolves', () => {
      userStateMock.currentUser.mockReturnValueOnce(null)
      expect(() => service.create(draft, 'userGlobal', NS_ID)).toThrow(/loadMe/i)
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
      const payload = nsController.updateAiProvider.mock.calls[0][1]
      expect('apiKey' in payload).toBe(false)
    })

    it('sends the new apiKey when the user typed a new value', async () => {
      await firstValueFrom(
        service.update(ITEM_ID, { ...baseDraft, apiKey: 'sk-ant-fresh' }, 'userOnNs', userOnNsProvider)
      )
      const payload = nsController.updateAiProvider.mock.calls[0][1]
      expect(payload.apiKey).toBe('sk-ant-fresh')
    })

    it('sends apiKey as empty string when draft.apiKey is "" (explicit clear)', async () => {
      // Wire contract : empty string signals clear ; the backend persists `apiKey = null`.
      // JSON-null cannot be used because Jackson collapses it with field-absent.
      await firstValueFrom(service.update(ITEM_ID, { ...baseDraft, apiKey: '' }, 'userOnNs', userOnNsProvider))
      const payload = nsController.updateAiProvider.mock.calls[0][1]
      expect('apiKey' in payload).toBe(true)
      expect(payload.apiKey).toBe('')
    })

    it('echoes the persisted (namespaceId, userId) tuple so the backend keeps the scope on PUT', async () => {
      await firstValueFrom(service.update(ITEM_ID, { ...baseDraft, apiKey: null }, 'userOnNs', userOnNsProvider))
      const payload = nsController.updateAiProvider.mock.calls[0][1]
      expect(payload.namespaceId).toBe(NS_ID)
      expect(payload.userId).toBe(ME_ID)
    })
  })

  describe('delete dispatch', () => {
    it('routes all scopes to the unified deleteAiProvider', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.delete(ITEM_ID, 'userGlobal'))
      expect(nsController.deleteAiProvider).toHaveBeenCalledTimes(3)
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      const sub = service.vm$.subscribe()
      const initialCalls = nsController.listAiProvider.mock.calls.length

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

      expect(nsController.listAiProvider.mock.calls.length).toBeGreaterThan(initialCalls)
      sub.unsubscribe()
    })
  })

  describe('getById dispatch', () => {
    it('routes all scopes to the unified getByIdAiProvider (single overload)', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'namespace'))
      await firstValueFrom(service.getById(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.getById(ITEM_ID, 'userGlobal'))
      expect(nsController.getByIdAiProvider).toHaveBeenCalledTimes(3)
    })

    it('accepts the no-scope overload (form simplification, G10)', async () => {
      await firstValueFrom(service.getById(ITEM_ID))
      expect(nsController.getByIdAiProvider).toHaveBeenCalledWith(ITEM_ID)
    })
  })
})

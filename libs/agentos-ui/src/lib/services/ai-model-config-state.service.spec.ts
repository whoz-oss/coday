import { TestBed } from '@angular/core/testing'
import {
  AiModel,
  AiModelControllerService,
  AiProvider,
  AiProviderApiTypeEnum,
  UserAiModel,
  UserAiModelControllerService,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, firstValueFrom, of } from 'rxjs'
import { AiProviderConfigStateService, AiProviderConfigViewModel } from './ai-provider-config-state.service'
import { AiModelConfigStateService } from './ai-model-config-state.service'

describe('AiModelConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'

  const nsModel: AiModel = {
    id: 'm-ns-1',
    aiProviderId: 'p-ns-1',
    namespaceId: NS_ID,
    apiModelName: 'claude-opus',
    priority: 1,
  }
  const userOnNsModel: UserAiModel = {
    id: 'm-uns-1',
    aiProviderId: 'p-uns-1',
    namespaceId: NS_ID,
    userId: 'me',
    apiModelName: 'claude-sonnet',
    priority: 2,
  }
  const userGlobalModel: UserAiModel = {
    id: 'm-ug-1',
    aiProviderId: 'p-ug-1',
    userId: 'me',
    apiModelName: 'claude-haiku',
    priority: 3,
  }

  const nsProvider: AiProvider = {
    id: 'p-ns-1',
    namespaceId: NS_ID,
    name: 'NS Anthropic',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }
  const userOnNsProvider: AiProvider = {
    id: 'p-uns-1',
    namespaceId: NS_ID,
    userId: 'me',
    name: 'My NS Provider',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }
  const userGlobalProvider: AiProvider = {
    id: 'p-ug-1',
    userId: 'me',
    name: 'My Global Provider',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }

  let nsController: jest.Mocked<AiModelControllerService>
  let userController: jest.Mocked<UserAiModelControllerService>
  let providerVm$: BehaviorSubject<AiProviderConfigViewModel>
  let providerState: Partial<AiProviderConfigStateService>
  let service: AiModelConfigStateService

  beforeEach(() => {
    nsController = {
      listByNamespaceIdAiModel: jest.fn().mockReturnValue(of([nsModel])),
      createAiModel: jest.fn().mockReturnValue(of(nsModel)),
      updateAiModel: jest.fn().mockReturnValue(of(nsModel)),
      deleteAiModel: jest.fn().mockReturnValue(of(undefined)),
      getByIdAiModel: jest.fn().mockReturnValue(of(nsModel)),
    } as unknown as jest.Mocked<AiModelControllerService>

    userController = {
      listUserAiModel: jest.fn().mockImplementation((namespaceId: string) =>
        of({
          content: namespaceId === 'none' ? [userGlobalModel] : [userOnNsModel],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        })
      ),
      createUserAiModel: jest.fn().mockReturnValue(of(userOnNsModel)),
      updateUserAiModel: jest.fn().mockReturnValue(of(userOnNsModel)),
      deleteUserAiModel: jest.fn().mockReturnValue(of(undefined)),
      getByIdUserAiModel: jest.fn().mockReturnValue(of(userOnNsModel)),
    } as unknown as jest.Mocked<UserAiModelControllerService>

    providerVm$ = new BehaviorSubject<AiProviderConfigViewModel>({
      namespace: [nsProvider],
      userOnNs: [userOnNsProvider],
      userGlobal: [userGlobalProvider],
    })
    providerState = { vm$: providerVm$.asObservable() }

    TestBed.configureTestingModule({
      providers: [
        AiModelConfigStateService,
        { provide: AiModelControllerService, useValue: nsController },
        { provide: UserAiModelControllerService, useValue: userController },
        { provide: AiProviderConfigStateService, useValue: providerState },
      ],
    })
    service = TestBed.inject(AiModelConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model and hides the "none" sentinel', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsModel])
      expect(vm.userOnNs).toEqual([userOnNsModel])
      expect(vm.userGlobal).toEqual([userGlobalModel])

      expect(nsController.listByNamespaceIdAiModel).toHaveBeenCalledWith(NS_ID)
      expect(userController.listUserAiModel).toHaveBeenCalledWith(NS_ID, undefined, 0, expect.any(Number))
      expect(userController.listUserAiModel).toHaveBeenCalledWith('none', undefined, 0, expect.any(Number))
    })
  })

  describe('eligibleProviders$ — FR3 parent-mode constraint', () => {
    it('returns only namespace-shared providers for scope=namespace', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('namespace'))
      expect(eligible.map((p) => p.id)).toEqual(['p-ns-1'])
      expect(eligible[0].scope).toBe('namespace')
    })

    it('returns userOnNs + namespace providers for scope=userOnNs', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('userOnNs'))
      // user-on-ns providers come first so the user picks them by default
      expect(eligible.map((p) => p.id)).toEqual(['p-uns-1', 'p-ns-1'])
      expect(eligible.find((p) => p.id === 'p-uns-1')?.scope).toBe('userOnNs')
      expect(eligible.find((p) => p.id === 'p-ns-1')?.scope).toBe('namespace')
    })

    it('returns only userGlobal providers for scope=userGlobal', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('userGlobal'))
      expect(eligible.map((p) => p.id)).toEqual(['p-ug-1'])
      expect(eligible[0].scope).toBe('userGlobal')
    })

    it('re-emits when the upstream provider VM changes', async () => {
      const observed: string[][] = []
      const sub = service.eligibleProviders$('userOnNs').subscribe((list) => observed.push(list.map((p) => p.id)))
      providerVm$.next({
        namespace: [nsProvider],
        userOnNs: [],
        userGlobal: [userGlobalProvider],
      })
      // first emission: full vm; second emission: userOnNs section is now empty
      expect(observed[0]).toEqual(['p-uns-1', 'p-ns-1'])
      expect(observed[1]).toEqual(['p-ns-1'])
      sub.unsubscribe()
    })
  })

  describe('create dispatch', () => {
    const draft = {
      apiModelName: 'claude-3-5-sonnet',
      alias: null,
      description: null,
      priority: 1,
      temperature: null,
      maxTokens: null,
      aiProviderId: 'p-ns-1',
    }

    it('routes namespace creates to the namespace controller with the namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      expect(nsController.createAiModel).toHaveBeenCalledWith(
        expect.objectContaining({ apiModelName: 'claude-3-5-sonnet', namespaceId: NS_ID })
      )
    })

    it('routes userOnNs creates to the user controller with the namespaceId set', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      expect(userController.createUserAiModel).toHaveBeenCalledWith(
        expect.objectContaining({ apiModelName: 'claude-3-5-sonnet', namespaceId: NS_ID })
      )
    })

    it('routes userGlobal creates to the user controller WITHOUT a namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'userGlobal', NS_ID))
      const arg = userController.createUserAiModel.mock.calls[0][0]
      expect(arg.namespaceId).toBeUndefined()
    })

    it('throws when creating a namespace-scoped model without a namespaceId', () => {
      expect(() => service.create(draft, 'namespace', null)).toThrow(/namespaceId/i)
    })

    it('throws when creating a userOnNs model without a namespaceId', () => {
      expect(() => service.create(draft, 'userOnNs', null)).toThrow(/namespaceId/i)
    })
  })

  describe('delete dispatch', () => {
    it('routes namespace deletes to the namespace controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      expect(nsController.deleteAiModel).toHaveBeenCalledWith(ITEM_ID)
    })

    it('routes userOnNs deletes to the user controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      expect(userController.deleteUserAiModel).toHaveBeenCalledWith(ITEM_ID)
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      const sub = service.vm$.subscribe()
      const initialNsCalls = nsController.listByNamespaceIdAiModel.mock.calls.length

      await firstValueFrom(
        service.create(
          {
            apiModelName: 'x',
            alias: null,
            description: null,
            priority: 1,
            temperature: null,
            maxTokens: null,
            aiProviderId: 'p-uns-1',
          },
          'userOnNs',
          NS_ID
        )
      )

      expect(nsController.listByNamespaceIdAiModel.mock.calls.length).toBeGreaterThan(initialNsCalls)
      sub.unsubscribe()
    })
  })
})

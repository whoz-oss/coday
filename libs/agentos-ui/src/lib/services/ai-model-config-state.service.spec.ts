import { TestBed } from '@angular/core/testing'
import { AiModel, AiModelControllerService, AiProvider, AiProviderApiTypeEnum } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, firstValueFrom, of } from 'rxjs'
import { AiProviderConfigStateService, AiProviderConfigViewModel } from './ai-provider-config-state.service'
import { AiModelConfigStateService } from './ai-model-config-state.service'
import { UserStateService } from './user-state.service'

describe('AiModelConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'
  const MY_ID = 'me-uuid'

  const nsModel: AiModel = {
    id: 'm-ns-1',
    aiProviderId: 'p-ns-1',
    namespaceId: NS_ID,
    apiModelName: 'claude-opus',
    priority: 1,
  }
  const userOnNsModel: AiModel = {
    id: 'm-uns-1',
    aiProviderId: 'p-uns-1',
    namespaceId: NS_ID,
    userId: MY_ID,
    apiModelName: 'claude-sonnet',
    priority: 2,
  }
  const userGlobalModel: AiModel = {
    id: 'm-ug-1',
    aiProviderId: 'p-ug-1',
    userId: MY_ID,
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
    userId: MY_ID,
    name: 'My NS Provider',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }
  const userGlobalProvider: AiProvider = {
    id: 'p-ug-1',
    userId: MY_ID,
    name: 'My Global Provider',
    apiType: AiProviderApiTypeEnum.Anthropic,
  }

  let controller: jest.Mocked<AiModelControllerService>
  let providerVm$: BehaviorSubject<AiProviderConfigViewModel>
  let providerState: Partial<AiProviderConfigStateService>
  let userState: Partial<UserStateService>
  let service: AiModelConfigStateService

  beforeEach(() => {
    controller = {
      listAiModel: jest.fn().mockImplementation((namespaceId?: string, userId?: string) => {
        const isUserScope = userId === 'me'
        const isGlobal = namespaceId === 'none'
        const content = isUserScope ? (isGlobal ? [userGlobalModel] : [userOnNsModel]) : [nsModel]
        return of({ content, page: 0, size: 1000, totalElements: content.length, totalPages: 1 })
      }),
      createAiModel: jest.fn().mockReturnValue(of(nsModel)),
      updateAiModel: jest.fn().mockReturnValue(of(nsModel)),
      deleteAiModel: jest.fn().mockReturnValue(of(undefined)),
      getByIdAiModel: jest.fn().mockReturnValue(of(nsModel)),
    } as unknown as jest.Mocked<AiModelControllerService>

    providerVm$ = new BehaviorSubject<AiProviderConfigViewModel>({
      namespace: [nsProvider],
      userOnNs: [userOnNsProvider],
      userGlobal: [userGlobalProvider],
    })
    providerState = { vm$: providerVm$.asObservable() }
    userState = { currentUser: jest.fn().mockReturnValue({ id: MY_ID }) }

    TestBed.configureTestingModule({
      providers: [
        AiModelConfigStateService,
        { provide: AiModelControllerService, useValue: controller },
        { provide: AiProviderConfigStateService, useValue: providerState },
        { provide: UserStateService, useValue: userState },
      ],
    })
    service = TestBed.inject(AiModelConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model via single controller', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsModel])
      expect(vm.userOnNs).toEqual([userOnNsModel])
      expect(vm.userGlobal).toEqual([userGlobalModel])

      // NS-shared: namespaceId=NS_ID, no userId
      expect(controller.listAiModel).toHaveBeenCalledWith(NS_ID, undefined, undefined, 0, 1000)
      // user × ns: namespaceId=NS_ID, userId='me'
      expect(controller.listAiModel).toHaveBeenCalledWith(NS_ID, 'me', undefined, 0, 1000)
      // user-global: namespaceId='none', userId='me'
      expect(controller.listAiModel).toHaveBeenCalledWith('none', 'me', undefined, 0, 1000)
    })
  })

  describe('eligibleProviders$ — FR3 parent-mode constraint', () => {
    it('returns only namespace-shared providers for scope=namespace', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('namespace'))
      expect(eligible.map((p) => p.id)).toEqual(['p-ns-1'])
      expect(eligible[0].scope).toBe('namespace')
    })

    it('returns only userOnNs providers for scope=userOnNs (strict scope match)', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('userOnNs'))
      expect(eligible.map((p) => p.id)).toEqual(['p-uns-1'])
      expect(eligible[0].scope).toBe('userOnNs')
    })

    it('returns only userGlobal providers for scope=userGlobal', async () => {
      const eligible = await firstValueFrom(service.eligibleProviders$('userGlobal'))
      expect(eligible.map((p) => p.id)).toEqual(['p-ug-1'])
      expect(eligible[0].scope).toBe('userGlobal')
    })

    it('re-emits when the upstream provider VM changes', async () => {
      const observed: string[][] = []
      const sub = service.eligibleProviders$('userOnNs').subscribe((list) => observed.push(list.map((p) => p.id)))
      providerVm$.next({ namespace: [nsProvider], userOnNs: [], userGlobal: [userGlobalProvider] })
      expect(observed[0]).toEqual(['p-uns-1'])
      expect(observed[1]).toEqual([])
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

    it('all scopes route through the single controller — scope is server-inferred from parent', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      expect(controller.createAiModel).toHaveBeenCalledWith(
        expect.objectContaining({ apiModelName: 'claude-3-5-sonnet', aiProviderId: 'p-ns-1' })
      )
    })

    it('does not include namespaceId or userId in the payload (server-side denormalization)', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      const payload = (controller.createAiModel as jest.Mock).mock.calls[0][0]
      expect(payload.namespaceId).toBeUndefined()
      expect(payload.userId).toBeUndefined()
    })

    it('userOnNs create routes through the single controller without namespaceId in payload', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      expect(controller.createAiModel).toHaveBeenCalledWith(
        expect.objectContaining({ apiModelName: 'claude-3-5-sonnet' })
      )
      const payload = (controller.createAiModel as jest.Mock).mock.calls[0][0]
      expect(payload.namespaceId).toBeUndefined()
    })

    it('throws when creating a user-scoped model and UserStateService has not resolved', () => {
      ;(userState.currentUser as jest.Mock).mockReturnValue(null)
      expect(() => service.create(draft, 'userOnNs', NS_ID)).toThrow(/UserStateService/)
      expect(() => service.create(draft, 'userGlobal', null)).toThrow(/UserStateService/)
    })
  })

  describe('delete dispatch', () => {
    it('routes all deletes through the single controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      expect(controller.deleteAiModel).toHaveBeenCalledWith(ITEM_ID)
    })

    it('routes user-scope deletes through the single controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      expect(controller.deleteAiModel).toHaveBeenCalledWith(ITEM_ID)
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      const sub = service.vm$.subscribe()
      const initialCalls = (controller.listAiModel as jest.Mock).mock.calls.length

      await firstValueFrom(
        service.create(
          {
            apiModelName: 'x',
            alias: null,
            description: null,
            priority: 1,
            temperature: null,
            maxTokens: null,
            aiProviderId: 'p-ns-1',
          },
          'namespace',
          NS_ID
        )
      )

      expect((controller.listAiModel as jest.Mock).mock.calls.length).toBeGreaterThan(initialCalls)
      sub.unsubscribe()
    })
  })
})

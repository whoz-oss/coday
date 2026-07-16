import { TestBed } from '@angular/core/testing'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  IntegrationConfigExportService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of } from 'rxjs'
import { IntegrationConfigStateService } from './integration-config-state.service'
import { UserStateService } from './user-state.service'

describe('IntegrationConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'
  const ME_ID = '33333333-3333-3333-3333-333333333333'

  const nsConfig: IntegrationConfig = {
    id: 'ns-1',
    namespaceId: NS_ID,
    name: 'Slack NS',
    integrationType: 'slack',
  }
  const userOnNsConfig: IntegrationConfig = {
    id: 'u-ns-1',
    namespaceId: NS_ID,
    userId: ME_ID,
    name: 'Slack mine on NS',
    integrationType: 'slack',
  }
  const userGlobalConfig: IntegrationConfig = {
    id: 'u-g-1',
    userId: ME_ID,
    name: 'Slack mine global',
    integrationType: 'slack',
  }

  let nsController: jest.Mocked<IntegrationConfigControllerService>
  let userStateMock: { currentUser: jest.Mock<{ id: string } | null, []> }
  let service: IntegrationConfigStateService

  beforeEach(() => {
    nsController = {
      // Unified `listIntegrationConfig(namespaceId, userId)` returns a flat array.
      // The mock dispatches by query params : userId omitted →
      // NS-shared layer ; namespaceId === 'none' → user-global ; both → user × ns.
      listIntegrationConfig: jest.fn().mockImplementation((namespaceId?: string, userId?: string) => {
        if (!userId) {
          return of([nsConfig])
        }
        if (namespaceId === 'none') {
          return of([userGlobalConfig])
        }
        return of([userOnNsConfig])
      }),
      createIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
      updateIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
      deleteIntegrationConfig: jest.fn().mockReturnValue(of(undefined)),
      getByIdIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
    } as unknown as jest.Mocked<IntegrationConfigControllerService>

    userStateMock = {
      currentUser: jest.fn().mockReturnValue({ id: ME_ID }),
    }

    TestBed.configureTestingModule({
      providers: [
        IntegrationConfigStateService,
        { provide: IntegrationConfigControllerService, useValue: nsController },
        { provide: UserStateService, useValue: userStateMock },
        { provide: IntegrationConfigExportService, useValue: { exportAsYaml: jest.fn() } },
      ],
    })
    service = TestBed.inject(IntegrationConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model and hides the "none" / "me" sentinels', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsConfig])
      expect(vm.userOnNs).toEqual([userOnNsConfig])
      expect(vm.userGlobal).toEqual([userGlobalConfig])

      expect(nsController.listIntegrationConfig).toHaveBeenCalledWith(NS_ID)
      expect(nsController.listIntegrationConfig).toHaveBeenCalledWith(NS_ID, 'me')
      expect(nsController.listIntegrationConfig).toHaveBeenCalledWith('none', 'me')
    })
  })

  describe('loadUserConfigs wrapper', () => {
    it('translates "global" into the (none, me) sentinel pair, never leaking it', async () => {
      const result = await firstValueFrom(service.loadUserConfigs('global'))
      expect(result).toEqual([userGlobalConfig])
      expect(nsController.listIntegrationConfig).toHaveBeenCalledWith('none', 'me')
    })

    it('passes a UUID for user × namespace and pins userId to "me"', async () => {
      const result = await firstValueFrom(service.loadUserConfigs(NS_ID))
      expect(result).toEqual([userOnNsConfig])
      expect(nsController.listIntegrationConfig).toHaveBeenCalledWith(NS_ID, 'me')
    })
  })

  describe('create dispatch', () => {
    const draft = { name: 'New', integrationType: 'slack', description: null, parameters: { token: 'x' } }

    it('routes namespace creates with NS only (no userId)', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      const payload = nsController.createIntegrationConfig.mock.calls[0][0]
      expect(payload).toEqual(expect.objectContaining({ name: 'New', integrationType: 'slack', namespaceId: NS_ID }))
      expect(payload.userId).toBeUndefined()
    })

    it('routes userOnNs creates with both namespaceId and userId=currentUser.id', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      const payload = nsController.createIntegrationConfig.mock.calls[0][0]
      expect(payload).toEqual(expect.objectContaining({ name: 'New', namespaceId: NS_ID, userId: ME_ID }))
    })

    it('routes userGlobal creates with userId=currentUser.id and NO namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'userGlobal', NS_ID))
      const payload = nsController.createIntegrationConfig.mock.calls[0][0]
      expect(payload.userId).toBe(ME_ID)
      expect(payload.namespaceId).toBeUndefined()
    })

    it('throws when creating a namespace-scoped config without a namespaceId', () => {
      expect(() => service.create(draft, 'namespace', null)).toThrow(/namespaceId/i)
    })

    it('throws when creating a user-scope config before UserStateService.loadMe() resolves', () => {
      userStateMock.currentUser.mockReturnValueOnce(null)
      expect(() => service.create(draft, 'userGlobal', NS_ID)).toThrow(/loadMe/i)
    })
  })

  describe('delete dispatch', () => {
    it('routes all scopes to the unified deleteIntegrationConfig', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.delete(ITEM_ID, 'userGlobal'))
      expect(nsController.deleteIntegrationConfig).toHaveBeenCalledTimes(3)
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      const sub = service.vm$.subscribe()
      const initialCalls = nsController.listIntegrationConfig.mock.calls.length

      await firstValueFrom(
        service.create({ name: 'x', integrationType: 'slack', description: null }, 'userOnNs', NS_ID)
      )

      expect(nsController.listIntegrationConfig.mock.calls.length).toBeGreaterThan(initialCalls)
      sub.unsubscribe()
    })
  })

  describe('getById dispatch', () => {
    it('routes all scopes to the unified getByIdIntegrationConfig', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'namespace'))
      await firstValueFrom(service.getById(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.getById(ITEM_ID, 'userGlobal'))
      expect(nsController.getByIdIntegrationConfig).toHaveBeenCalledTimes(3)
    })

    it('accepts the no-scope overload (form simplification, G10)', async () => {
      await firstValueFrom(service.getById(ITEM_ID))
      expect(nsController.getByIdIntegrationConfig).toHaveBeenCalledWith(ITEM_ID)
    })
  })
})

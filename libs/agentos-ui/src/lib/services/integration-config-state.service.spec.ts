import { TestBed } from '@angular/core/testing'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  UserIntegrationConfig,
  UserIntegrationConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { firstValueFrom, of } from 'rxjs'
import { IntegrationConfigStateService } from './integration-config-state.service'

describe('IntegrationConfigStateService', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const ITEM_ID = '22222222-2222-2222-2222-222222222222'

  const nsConfig: IntegrationConfig = {
    id: 'ns-1',
    namespaceId: NS_ID,
    name: 'Slack NS',
    integrationType: 'slack',
  }
  const userOnNsConfig: UserIntegrationConfig = {
    id: 'u-ns-1',
    namespaceId: NS_ID,
    userId: 'me',
    name: 'Slack mine on NS',
    integrationType: 'slack',
  }
  const userGlobalConfig: UserIntegrationConfig = {
    id: 'u-g-1',
    userId: 'me',
    name: 'Slack mine global',
    integrationType: 'slack',
  }

  let nsController: jest.Mocked<IntegrationConfigControllerService>
  let userController: jest.Mocked<UserIntegrationConfigControllerService>
  let service: IntegrationConfigStateService

  beforeEach(() => {
    nsController = {
      listByParentIntegrationConfig: jest.fn().mockReturnValue(of([nsConfig])),
      createIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
      updateIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
      deleteIntegrationConfig: jest.fn().mockReturnValue(of(undefined)),
      getByIdIntegrationConfig: jest.fn().mockReturnValue(of(nsConfig)),
    } as unknown as jest.Mocked<IntegrationConfigControllerService>

    userController = {
      listUserIntegrationConfig: jest.fn().mockImplementation((namespaceId: string) =>
        of({
          content: namespaceId === 'none' ? [userGlobalConfig] : [userOnNsConfig],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        })
      ),
      createUserIntegrationConfig: jest.fn().mockReturnValue(of(userOnNsConfig)),
      updateUserIntegrationConfig: jest.fn().mockReturnValue(of(userOnNsConfig)),
      deleteUserIntegrationConfig: jest.fn().mockReturnValue(of(undefined)),
      getByIdUserIntegrationConfig: jest.fn().mockReturnValue(of(userOnNsConfig)),
    } as unknown as jest.Mocked<UserIntegrationConfigControllerService>

    TestBed.configureTestingModule({
      providers: [
        IntegrationConfigStateService,
        { provide: IntegrationConfigControllerService, useValue: nsController },
        { provide: UserIntegrationConfigControllerService, useValue: userController },
      ],
    })
    service = TestBed.inject(IntegrationConfigStateService)
  })

  describe('vm$', () => {
    it('merges the 3 sources into a structured view model and hides the "none" sentinel from callers', async () => {
      service.setNamespace(NS_ID)
      const vm = await firstValueFrom(service.vm$)

      expect(vm.namespace).toEqual([nsConfig])
      expect(vm.userOnNs).toEqual([userOnNsConfig])
      expect(vm.userGlobal).toEqual([userGlobalConfig])

      expect(nsController.listByParentIntegrationConfig).toHaveBeenCalledWith(NS_ID)
      // user-global slice goes through with the "none" sentinel; user×NS uses the UUID.
      expect(userController.listUserIntegrationConfig).toHaveBeenCalledWith(NS_ID)
      expect(userController.listUserIntegrationConfig).toHaveBeenCalledWith('none')
    })
  })

  describe('loadUserConfigs wrapper', () => {
    it('translates "global" into the backend sentinel, never leaking it to callers', async () => {
      const result = await firstValueFrom(service.loadUserConfigs('global'))
      expect(result).toEqual([userGlobalConfig])
      expect(userController.listUserIntegrationConfig).toHaveBeenCalledWith('none')
    })

    it('passes through a UUID as-is for user × namespace lookups', async () => {
      const result = await firstValueFrom(service.loadUserConfigs(NS_ID))
      expect(result).toEqual([userOnNsConfig])
      expect(userController.listUserIntegrationConfig).toHaveBeenCalledWith(NS_ID)
    })
  })

  describe('create dispatch', () => {
    const draft = { name: 'New', integrationType: 'slack', description: null, parameters: { token: 'x' } }

    it('routes namespace creates to the namespace controller with the namespaceId in the payload', async () => {
      await firstValueFrom(service.create(draft, 'namespace', NS_ID))
      expect(nsController.createIntegrationConfig).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'New', integrationType: 'slack', namespaceId: NS_ID })
      )
      expect(userController.createUserIntegrationConfig).not.toHaveBeenCalled()
    })

    it('routes userOnNs creates to the user controller with the namespaceId set', async () => {
      await firstValueFrom(service.create(draft, 'userOnNs', NS_ID))
      expect(userController.createUserIntegrationConfig).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'New', namespaceId: NS_ID })
      )
      expect(nsController.createIntegrationConfig).not.toHaveBeenCalled()
    })

    it('routes userGlobal creates to the user controller WITHOUT a namespaceId', async () => {
      await firstValueFrom(service.create(draft, 'userGlobal', NS_ID))
      const arg = userController.createUserIntegrationConfig.mock.calls[0][0]
      expect(arg.namespaceId).toBeUndefined()
      expect(arg.name).toBe('New')
    })

    it('throws when creating a namespace-scoped config without a namespaceId', () => {
      expect(() => service.create(draft, 'namespace', null)).toThrow(/namespaceId/i)
    })
  })

  describe('delete dispatch', () => {
    it('routes namespace deletes to the namespace controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'namespace'))
      expect(nsController.deleteIntegrationConfig).toHaveBeenCalledWith(ITEM_ID)
      expect(userController.deleteUserIntegrationConfig).not.toHaveBeenCalled()
    })

    it('routes userOnNs and userGlobal deletes to the user controller', async () => {
      await firstValueFrom(service.delete(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.delete(ITEM_ID, 'userGlobal'))
      expect(userController.deleteUserIntegrationConfig).toHaveBeenCalledTimes(2)
      expect(userController.deleteUserIntegrationConfig).toHaveBeenCalledWith(ITEM_ID)
      expect(nsController.deleteIntegrationConfig).not.toHaveBeenCalled()
    })
  })

  describe('refresh after mutation', () => {
    it('refetches the 3 sources after a successful create', async () => {
      service.setNamespace(NS_ID)
      // Subscribe to vm$ first so that emissions can be observed.
      const sub = service.vm$.subscribe()
      const initialNsCalls = nsController.listByParentIntegrationConfig.mock.calls.length
      const initialUserCalls = userController.listUserIntegrationConfig.mock.calls.length

      await firstValueFrom(service.create({ name: 'x', integrationType: 'slack' }, 'userOnNs', NS_ID))

      expect(nsController.listByParentIntegrationConfig.mock.calls.length).toBeGreaterThan(initialNsCalls)
      expect(userController.listUserIntegrationConfig.mock.calls.length).toBeGreaterThan(initialUserCalls)
      sub.unsubscribe()
    })
  })

  describe('getById dispatch', () => {
    it('routes namespace lookups to the namespace controller', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'namespace'))
      expect(nsController.getByIdIntegrationConfig).toHaveBeenCalledWith(ITEM_ID)
    })

    it('routes user-scope lookups to the user controller for both userOnNs and userGlobal', async () => {
      await firstValueFrom(service.getById(ITEM_ID, 'userOnNs'))
      await firstValueFrom(service.getById(ITEM_ID, 'userGlobal'))
      expect(userController.getByIdUserIntegrationConfig).toHaveBeenCalledTimes(2)
    })
  })
})

import { ChangeDetectorRef } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, UserIntegrationConfig } from '@whoz-oss/agentos-api-client'
import { EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, of } from 'rxjs'
import {
  IntegrationConfigStateService,
  IntegrationConfigViewModel,
} from '../../services/integration-config-state.service'
import { IntegrationsAllScopesComponent } from './integrations-all-scopes.component'

describe('IntegrationsAllScopesComponent', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'

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

  const fullVm: IntegrationConfigViewModel = {
    namespace: [nsConfig],
    userOnNs: [userOnNsConfig],
    userGlobal: [userGlobalConfig],
  }

  let vm$: BehaviorSubject<IntegrationConfigViewModel>
  let stateMock: Partial<IntegrationConfigStateService>
  let routerMock: { navigate: jest.Mock }
  let component: IntegrationsAllScopesComponent

  function makeComponent(): IntegrationsAllScopesComponent {
    return TestBed.runInInjectionContext(() => new IntegrationsAllScopesComponent())
  }

  beforeEach(() => {
    vm$ = new BehaviorSubject<IntegrationConfigViewModel>(fullVm)
    stateMock = {
      vm$: vm$.asObservable(),
      setNamespace: jest.fn(),
      delete: jest.fn().mockReturnValue(of(undefined)),
    }
    routerMock = { navigate: jest.fn() }

    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { params: { namespaceId: NS_ID } } } },
        { provide: Router, useValue: routerMock },
        { provide: IntegrationConfigStateService, useValue: stateMock },
        ChangeDetectorRef,
      ],
    })
    component = makeComponent()
  })

  describe('list mapping', () => {
    it('emits a flat list with the 3 sections grouped by scope and the configured French labels', async () => {
      const items = await new Promise<EntityListItem[]>((resolve) =>
        component['listItems$'].subscribe((v) => resolve(v))
      )
      const byGroup = (key: string) => items.filter((i) => i.groupKey === key)

      expect(byGroup('namespace').map((i) => i.name)).toEqual(['Slack NS'])
      expect(byGroup('userOnNs').map((i) => i.name)).toEqual(['Slack mine on NS'])
      expect(byGroup('userGlobal').map((i) => i.name)).toEqual(['Slack mine global'])

      expect(byGroup('namespace')[0].groupLabel).toBe('Configurations du namespace')
      expect(byGroup('userOnNs')[0].groupLabel).toBe('Mes overrides sur ce namespace')
      expect(byGroup('userGlobal')[0].groupLabel).toBe('Mes overrides globaux')
    })

    it('emits a placeholder row in each empty section so all 3 sections remain visible', async () => {
      vm$.next({ namespace: [], userOnNs: [userOnNsConfig], userGlobal: [] })
      const items = await new Promise<EntityListItem[]>((resolve) =>
        component['listItems$'].subscribe((v) => resolve(v))
      )
      const namespacePlaceholder = items.find((i) => i.groupKey === 'namespace')
      const userGlobalPlaceholder = items.find((i) => i.groupKey === 'userGlobal')

      expect(namespacePlaceholder?.id).toMatch(/^__empty__namespace$/)
      expect(namespacePlaceholder?.name).toBe('Aucune configuration')
      expect(userGlobalPlaceholder?.id).toMatch(/^__empty__userGlobal$/)
      // The non-empty section keeps its real data — no placeholder injected.
      expect(items.filter((i) => i.groupKey === 'userOnNs').map((i) => i.id)).toEqual(['u-ns-1'])
    })
  })

  describe('lifecycle', () => {
    it('initialises the state service with the active namespaceId on init', () => {
      component.ngOnInit()
      expect(stateMock.setNamespace).toHaveBeenCalledWith(NS_ID)
    })

    it('builds the resolved index so the item template can route events to the right scope', () => {
      component.ngOnInit()
      expect(component['resolve']('ns-1')?.scope).toBe('namespace')
      expect(component['resolve']('u-ns-1')?.scope).toBe('userOnNs')
      expect(component['resolve']('u-g-1')?.scope).toBe('userGlobal')
      expect(component['resolve']('unknown')).toBeNull()
    })
  })

  describe('navigation events', () => {
    it('navigates to the form with scope=userOnNs and the selected config id as the template param on Override', () => {
      component['onOverride'](nsConfig)
      expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'new'], {
        queryParams: { scope: 'userOnNs', template: 'ns-1' },
      })
    })

    it('navigates to the edit form preserving the scope query param', () => {
      component['onEdit']({ config: userOnNsConfig, scope: 'userOnNs' })
      expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'u-ns-1', 'edit'], {
        queryParams: { scope: 'userOnNs' },
      })
    })

    it('opens create with the default scope=namespace query param', () => {
      component['openCreateForm']()
      expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'new'], {
        queryParams: { scope: 'namespace' },
      })
    })
  })

  describe('delete dispatch', () => {
    it('forwards the delete to the state service with the matching scope (userGlobal)', () => {
      component['onDelete']({ config: userGlobalConfig, scope: 'userGlobal' })
      expect(stateMock.delete).toHaveBeenCalledWith('u-g-1', 'userGlobal')
    })

    it('forwards the delete to the state service with the matching scope (namespace)', () => {
      component['onDelete']({ config: nsConfig, scope: 'namespace' })
      expect(stateMock.delete).toHaveBeenCalledWith('ns-1', 'namespace')
    })
  })
})

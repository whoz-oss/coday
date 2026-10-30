import { ChangeDetectorRef } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router'
import { IntegrationConfig, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, of } from 'rxjs'
import {
  IntegrationConfigStateService,
  IntegrationConfigViewModel,
} from '../../services/integration-config-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { IntegrationsAllScopesComponent } from './integrations-all-scopes.component'

describe('IntegrationsAllScopesComponent', () => {
  const NS_ID = '11111111-1111-1111-1111-111111111111'
  const platformConfig: IntegrationConfig = { id: 'p-1', name: 'Slack Platform', integrationType: 'slack' }
  const yamlConfig = {
    id: 'y-1',
    namespaceId: NS_ID,
    name: 'YAML config',
    integrationType: 'git',
    source: 'FILESYSTEM',
  } as IntegrationConfig
  const nsConfig: IntegrationConfig = { id: 'ns-1', namespaceId: NS_ID, name: 'Slack NS', integrationType: 'slack' }
  const userOnNsConfig: IntegrationConfig = {
    id: 'u-ns-1',
    namespaceId: NS_ID,
    userId: 'me',
    name: 'Slack mine on NS',
    integrationType: 'slack',
  }
  const userGlobalConfig: IntegrationConfig = {
    id: 'u-g-1',
    userId: 'me',
    name: 'Slack mine global',
    integrationType: 'slack',
  }
  const fullVm: IntegrationConfigViewModel = {
    platform: [platformConfig],
    namespace: [nsConfig, yamlConfig],
    userOnNs: [userOnNsConfig],
    userGlobal: [userGlobalConfig],
  }

  let vm$: BehaviorSubject<IntegrationConfigViewModel>
  let stateMock: Partial<IntegrationConfigStateService>
  let routerMock: { navigate: jest.Mock }
  let component: IntegrationsAllScopesComponent

  beforeEach(() => {
    vm$ = new BehaviorSubject(fullVm)
    stateMock = { vm$: vm$, setNamespace: jest.fn(), delete: jest.fn().mockReturnValue(of(undefined)) }
    routerMock = { navigate: jest.fn() }
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { params: { namespaceId: NS_ID } },
            paramMap: of(convertToParamMap({ namespaceId: NS_ID })),
          },
        },
        { provide: Router, useValue: routerMock },
        { provide: IntegrationConfigStateService, useValue: stateMock },
        { provide: NamespaceRoleStateService, useValue: { isAdminOfNamespace$: jest.fn().mockReturnValue(of(false)) } },
        {
          provide: NamespaceControllerService,
          useValue: { getByIdNamespace: jest.fn().mockReturnValue(of({ id: NS_ID, name: 'Acme' })) },
        },
        ChangeDetectorRef,
      ],
    })
    component = TestBed.runInInjectionContext(() => new IntegrationsAllScopesComponent())
  })

  it('builds one consolidated row table with scopes, source and non-admin permissions', () => {
    component.ngOnInit()
    const rows = component['rows']()
    expect(rows.map((row) => [row.scope, row.scopeLabel, row.source])).toEqual([
      ['platform', 'Platform', 'NEO4J'],
      ['namespace', 'Namespace', 'NEO4J'],
      ['namespace', 'Namespace', 'FILESYSTEM'],
      ['userOnNs', 'Personal', 'NEO4J'],
      ['userGlobal', 'Personal', 'NEO4J'],
    ])
    expect(rows.map((row) => [row.config.id, row.canEdit, row.canDelete])).toEqual([
      ['p-1', false, false],
      ['ns-1', false, false],
      ['y-1', false, false],
      ['u-ns-1', true, true],
      ['u-g-1', true, true],
    ])
  })

  it('makes namespace rows editable for admins while platform and YAML remain read-only', () => {
    TestBed.inject(NamespaceRoleStateService).isAdminOfNamespace$ = jest.fn().mockReturnValue(of(true))
    component.ngOnInit()
    const rows = component['rows']()
    expect(rows.find((row) => row.config.id === 'ns-1')).toMatchObject({ canEdit: true, canDelete: true })
    expect(rows.find((row) => row.config.id === 'p-1')).toMatchObject({ canEdit: false, canDelete: false })
    expect(rows.find((row) => row.config.id === 'y-1')).toMatchObject({ canEdit: false, canDelete: false })
  })

  it('initialises the active namespace', () => {
    component.ngOnInit()
    expect(stateMock.setNamespace).toHaveBeenCalledWith(NS_ID)
  })

  it.each([
    ['platform admin', true, platformConfig, 'platform', 'platform'],
    ['platform non-admin', false, platformConfig, 'platform', 'userOnNs'],
    ['namespace admin', true, nsConfig, 'namespace', 'namespace'],
    ['namespace non-admin', false, nsConfig, 'namespace', 'userOnNs'],
    ['personal namespace', false, userOnNsConfig, 'userOnNs', 'userOnNs'],
    ['personal global', false, userGlobalConfig, 'userGlobal', 'userGlobal'],
  ])('duplicates %s with the correct source and destination scope', (_label, admin, config, scope, destination) => {
    component['isAdmin'].set(admin)
    component['duplicate']({ config, scope, source: 'NEO4J' } as never)
    expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'new'], {
      queryParams: { scope: destination, template: config.id, templateScope: scope },
    })
  })

  it('does not duplicate YAML integrations and opens them read-only', () => {
    const row = { config: yamlConfig, scope: 'namespace', source: 'FILESYSTEM' } as never
    component['duplicate'](row)
    expect(routerMock.navigate).not.toHaveBeenCalled()
    component['view'](row)
    expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'y-1', 'view'])
  })

  it('edits mutable rows preserving scope and ignores read-only rows', () => {
    component['edit']({ config: userOnNsConfig, scope: 'userOnNs', canEdit: true } as never)
    expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'u-ns-1', 'edit'], {
      queryParams: { scope: 'userOnNs' },
    })
    routerMock.navigate.mockClear()
    component['edit']({ config: nsConfig, scope: 'namespace', canEdit: false } as never)
    expect(routerMock.navigate).not.toHaveBeenCalled()
  })

  it.each([
    [true, 'namespace'],
    [false, 'userOnNs'],
  ])('opens create using the permission-aware default scope', (admin, scope) => {
    component['isAdmin'].set(admin)
    component['openCreateForm']()
    expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos', NS_ID, 'integrations', 'new'], {
      queryParams: { scope },
    })
  })

  it('confirms and dispatches deletion only for mutable rows', () => {
    const confirmSpy = jest.spyOn(globalThis, 'confirm').mockReturnValue(true)
    component['delete']({ config: userGlobalConfig, scope: 'userGlobal', canDelete: true } as never)
    expect(stateMock.delete).toHaveBeenCalledWith('u-g-1', 'userGlobal')
    component['delete']({ config: platformConfig, scope: 'platform', canDelete: false } as never)
    expect(stateMock.delete).toHaveBeenCalledTimes(1)
    confirmSpy.mockRestore()
  })
})

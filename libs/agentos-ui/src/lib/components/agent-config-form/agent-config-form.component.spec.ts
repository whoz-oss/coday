import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  AgentConfigExportService,
  IntegrationTypeControllerService,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { AgentConfigFormComponent } from './agent-config-form.component'

/**
 * Focused on the built-in file-exchange integrations in the agent-config form: they are
 * discovered from GET /api/integration-types (builtIn === true), listed under a "Built-in
 * integrations" separator, and their enablement round-trips through AgentConfig.integrations —
 * there are no dedicated boolean fields any more.
 *
 * The component class is driven directly (ngOnInit / submit) without rendering the template,
 * except the listing tests which render via detectChanges.
 */
describe('AgentConfigFormComponent (built-in exchange integrations)', () => {
  let controller: {
    getByIdAgentConfig: jest.Mock
    createAgentConfig: jest.Mock
    updateAgentConfig: jest.Mock
  }
  let integrationState: { loadNamespaceConfigs: jest.Mock; loadPlatformConfigs: jest.Mock }
  let integrationType: { listTypesIntegrationType: jest.Mock }
  let router: { navigate: jest.Mock }
  let routeAgentConfigId: string | null
  let fixture: ComponentFixture<AgentConfigFormComponent>
  let component: AgentConfigFormComponent

  const CASE = 'CASE_FILE_EXCHANGE'
  const NAMESPACE = 'NAMESPACE_FILE_EXCHANGE'

  const builtInTypes = [
    { type: CASE, displayName: 'Case file exchange', description: 'Case files.', configSchema: null, builtIn: true },
    {
      type: NAMESPACE,
      displayName: 'Namespace file exchange',
      description: 'NS files.',
      configSchema: null,
      builtIn: true,
    },
    // a regular (non-built-in) type must be excluded from the built-in section
    { type: 'JIRA', displayName: 'Jira', description: '', configSchema: {}, builtIn: false },
  ]

  const internals = () =>
    component as unknown as {
      nameControl: { setValue: (v: string) => void }
      builtInRows: () => Array<{ type: string; enabled: { (): boolean; set: (v: boolean) => void } }>
      submit: () => void
    }

  function editConfig(overrides: Partial<AgentConfig>): AgentConfig {
    return {
      id: 'a-1',
      namespaceId: 'ns-1',
      name: 'agent',
      createdOn: '2026-01-01T00:00:00Z',
      updatedOn: '2026-01-01T00:00:00Z',
      ...overrides,
    } as AgentConfig
  }

  beforeEach(() => {
    routeAgentConfigId = null
    controller = {
      getByIdAgentConfig: jest.fn(),
      createAgentConfig: jest.fn().mockReturnValue(of({})),
      updateAgentConfig: jest.fn().mockReturnValue(of({})),
    }
    integrationState = {
      loadNamespaceConfigs: jest.fn().mockReturnValue(of([])),
      loadPlatformConfigs: jest.fn().mockReturnValue(of([])),
    }
    // Default: the backend lists the built-in exchange types (file-plugin loaded).
    integrationType = {
      listTypesIntegrationType: jest.fn().mockReturnValue(of(builtInTypes)),
    }
    router = { navigate: jest.fn() }
    const activatedRoute = {
      snapshot: {
        params: { namespaceId: 'ns-1' },
        paramMap: { get: (key: string) => (key === 'agentConfigId' ? routeAgentConfigId : null) },
      },
    }

    TestBed.configureTestingModule({
      imports: [AgentConfigFormComponent],
      providers: [
        { provide: ActivatedRoute, useValue: activatedRoute },
        { provide: Router, useValue: router },
        { provide: AgentConfigControllerService, useValue: controller },
        { provide: IntegrationConfigStateService, useValue: integrationState },
        { provide: IntegrationTypeControllerService, useValue: integrationType },
        { provide: AgentConfigExportService, useValue: { exportAsYaml: jest.fn() } },
      ],
    })

    fixture = TestBed.createComponent(AgentConfigFormComponent)
    component = fixture.componentInstance
  })

  describe('built-in rows', () => {
    it('lists only builtIn integration types as built-in rows', () => {
      component.ngOnInit()
      expect(
        internals()
          .builtInRows()
          .map((r) => r.type)
      ).toEqual([CASE, NAMESPACE])
    })

    it('hydrates the enabled state from the config integrations map (edit mode)', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: null } })))

      component.ngOnInit()

      const rows = internals().builtInRows()
      expect(rows.find((r) => r.type === CASE)?.enabled()).toBe(true)
      expect(rows.find((r) => r.type === NAMESPACE)?.enabled()).toBe(false)
    })
  })

  describe('submit payload', () => {
    it('adds an enabled built-in to the integrations map of the create payload', () => {
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals()
        .builtInRows()
        .find((r) => r.type === CASE)!
        .enabled.set(true)

      internals().submit()

      const payload = controller.createAgentConfig.mock.calls[0][0]
      expect(payload.integrations).toEqual({ [CASE]: null })
      expect(payload.caseExchange).toBeUndefined()
      expect(payload.namespaceExchange).toBeUndefined()
      expect(router.navigate).toHaveBeenCalled()
    })

    it('carries a hydrated built-in through an update payload', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [NAMESPACE]: null } })))
      component.ngOnInit()

      internals().submit()

      const payload = controller.updateAgentConfig.mock.calls[0][1]
      expect(payload.integrations).toEqual({ [NAMESPACE]: null })
    })

    it('preserves an already-enabled built-in when the integration-types endpoint is unavailable', () => {
      // The types call fails → builtInRows is empty (fail-safe, no toggle rendered), but the agent
      // already had CASE enabled: saving unrelated changes must NOT silently strip it from the map.
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: null } })))
      integrationType.listTypesIntegrationType.mockReturnValue(throwError(() => new Error('boom')))
      component.ngOnInit()

      expect(internals().builtInRows()).toEqual([])

      internals().submit()

      const payload = controller.updateAgentConfig.mock.calls[0][1]
      expect(payload.integrations).toEqual({ [CASE]: null })
    })
  })

  describe('listing / gating', () => {
    it('renders the built-in integrations section when the backend lists them', () => {
      fixture.detectChanges()
      const text = (fixture.nativeElement as HTMLElement).textContent ?? ''
      expect(integrationType.listTypesIntegrationType).toHaveBeenCalled()
      expect(text).toContain('Built-in integrations')
      expect(text).toContain('Case file exchange')
      expect(text).toContain('Namespace file exchange')
    })

    it('shows no built-in rows when the backend lists none (file-plugin absent)', () => {
      integrationType.listTypesIntegrationType.mockReturnValue(of([]))
      fixture.detectChanges()
      const text = (fixture.nativeElement as HTMLElement).textContent ?? ''
      expect(internals().builtInRows()).toEqual([])
      expect(text).not.toContain('Built-in integrations')
    })

    it('stays fail-safe (no built-in rows) when the integration-types call errors', () => {
      integrationType.listTypesIntegrationType.mockReturnValue(throwError(() => new Error('boom')))
      fixture.detectChanges()
      expect(internals().builtInRows()).toEqual([])
    })
  })
})

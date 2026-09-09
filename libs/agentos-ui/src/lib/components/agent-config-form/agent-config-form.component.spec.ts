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
 * discovered from GET /api/integration-types by the absence of a configSchema, listed under a
 * "Built-in integrations" separator, and their enablement round-trips through
 * AgentConfig.integrations — there are no dedicated boolean fields any more.
 *
 * The component class is driven directly (ngOnInit / submit) without rendering the template,
 * except the listing and tri-state select tests which render via detectChanges.
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
    {
      type: CASE,
      displayName: 'Case file exchange',
      description: 'Case files.',
      configSchema: null,
      builtIn: true,
      enabledByDefault: false,
    },
    {
      type: NAMESPACE,
      displayName: 'Namespace file exchange',
      description: 'NS files.',
      configSchema: null,
      builtIn: true,
      enabledByDefault: false,
    },
    // a regular (non-built-in) type must be excluded from the built-in section
    { type: 'JIRA', displayName: 'Jira', description: '', configSchema: {}, builtIn: false, enabledByDefault: false },
  ]

  type BuiltInState = 'default' | 'on' | 'off'

  const internals = () =>
    component as unknown as {
      nameControl: { setValue: (v: string) => void }
      builtInRows: () => Array<{
        type: string
        enabledByDefault: boolean
        state: { (): BuiltInState; set: (v: BuiltInState) => void }
        restrictedTools: string[] | null
      }>
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
    it('lists only types without a configSchema as built-in rows', () => {
      component.ngOnInit()
      expect(
        internals()
          .builtInRows()
          .map((r) => r.type)
      ).toEqual([CASE, NAMESPACE])
    })

    it('hydrates the tri-state from the config integrations map (edit mode)', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: null } })))

      component.ngOnInit()

      const rows = internals().builtInRows()
      expect(rows.find((r) => r.type === CASE)?.state()).toBe('on')
      // No key at all → the agent expressed no choice; the platform default decides, not the form.
      expect(rows.find((r) => r.type === NAMESPACE)?.state()).toBe('default')
    })

    it('reads an empty array as an explicit opt-out, not as an enabled row', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: [] } })))

      component.ngOnInit()

      expect(
        internals()
          .builtInRows()
          .find((r) => r.type === CASE)
          ?.state()
      ).toBe('off')
    })

    it('hydrates a non-empty allowlist as an enabled row carrying the restriction', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: ['readFile'] } })))

      component.ngOnInit()

      const row = internals()
        .builtInRows()
        .find((r) => r.type === CASE)
      expect(row?.state()).toBe('on')
      expect(row?.restrictedTools).toEqual(['readFile'])
    })

    it('carries the platform default of each type onto its row', () => {
      integrationType.listTypesIntegrationType.mockReturnValue(
        of([
          { ...builtInTypes[0], enabledByDefault: true },
          { ...builtInTypes[1], enabledByDefault: false },
        ])
      )

      component.ngOnInit()

      const rows = internals().builtInRows()
      expect(rows.find((r) => r.type === CASE)?.enabledByDefault).toBe(true)
      expect(rows.find((r) => r.type === NAMESPACE)?.enabledByDefault).toBe(false)
    })
  })

  describe('submit payload', () => {
    it('adds an enabled built-in to the integrations map of the create payload', () => {
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals()
        .builtInRows()
        .find((r) => r.type === CASE)!
        .state.set('on')

      internals().submit()

      const payload = controller.createAgentConfig.mock.calls[0][0]
      expect(payload.integrations).toEqual({ [CASE]: null })
      expect(payload.caseExchange).toBeUndefined()
      expect(payload.namespaceExchange).toBeUndefined()
      expect(router.navigate).toHaveBeenCalled()
    })

    it('writes an empty array for a built-in the user explicitly disabled', () => {
      // The opt-out has to be persisted, otherwise a platform default of on would grant it back.
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals()
        .builtInRows()
        .find((r) => r.type === CASE)!
        .state.set('off')

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].integrations).toEqual({ [CASE]: [] })
    })

    it('writes no key for a built-in left on the platform default', () => {
      // The regression this guards: saving an unrelated change must not turn "never chosen" into
      // "chosen off" and silently strip the exchange from an agent on a default-on instance.
      integrationType.listTypesIntegrationType.mockReturnValue(
        of([{ ...builtInTypes[0], enabledByDefault: true }, builtInTypes[1]])
      )
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].integrations).toBeUndefined()
    })

    it('deletes the key when a hydrated built-in is switched back to the platform default', () => {
      // The only path that exercises the key-deletion branch of the preservation loop: the create-mode
      // test above has an empty existingConfig, so it would stay green even if the branch were keyed on
      // the payload rather than on what the form can render, and a user handing an agent back to the
      // platform default would silently keep its previous choice.
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: null } })))
      component.ngOnInit()
      internals()
        .builtInRows()
        .find((r) => r.type === CASE)!
        .state.set('default')

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].integrations).toBeUndefined()
    })

    it('carries a hydrated built-in through an update payload', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [NAMESPACE]: null } })))
      component.ngOnInit()

      internals().submit()

      const payload = controller.updateAgentConfig.mock.calls[0][1]
      expect(payload.integrations).toEqual({ [NAMESPACE]: null })
    })

    it('carries a hydrated opt-out through an update payload', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [NAMESPACE]: [] } })))
      component.ngOnInit()

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].integrations).toEqual({ [NAMESPACE]: [] })
    })

    it('writes a hydrated per-tool allowlist back verbatim instead of widening it to all tools', () => {
      // The form has no editor for a non-empty allowlist (set via API or YAML): a row left on `on`
      // must not turn ['readFile'] into null and silently grant editFiles / remove / moveFile.
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: ['readFile'] } })))
      component.ngOnInit()

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].integrations).toEqual({ [CASE]: ['readFile'] })
    })

    it('lets an explicit opt-out override a hydrated allowlist', () => {
      // Switching the row to `off` is the one edit the form does offer on a restricted grant.
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: ['readFile'] } })))
      component.ngOnInit()
      internals()
        .builtInRows()
        .find((r) => r.type === CASE)!
        .state.set('off')

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].integrations).toEqual({ [CASE]: [] })
    })

    it('preserves an already-enabled built-in when the integration-types endpoint is unavailable', () => {
      // The types call fails → builtInRows is empty (fail-safe, no row rendered), but the agent
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

  describe('tri-state select (template)', () => {
    const selectFor = (type: string): HTMLSelectElement => {
      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(`#built-in-${type}`)
      expect(select).not.toBeNull()
      return select!
    }

    it('offers exactly the three tri-state values as options', () => {
      fixture.detectChanges()
      expect(Array.from(selectFor(CASE).options).map((o) => o.value)).toEqual(['default', 'on', 'off'])
    })

    it('renders the hydrated state on the control', () => {
      // Covers the state to DOM direction, carried solely by [value]="row.state()". Without it an
      // opt-out would display as "Platform default", which on a default-on instance reads as the
      // exact opposite of what the agent is configured to do.
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: [] } })))
      fixture.detectChanges()

      expect(selectFor(CASE).value).toBe('off')
    })

    it('routes a change event on the select to the row state', () => {
      fixture.detectChanges()
      const select = selectFor(CASE)
      select.value = 'off'
      select.dispatchEvent(new Event('change'))

      expect(
        internals()
          .builtInRows()
          .find((r) => r.type === CASE)
          ?.state()
      ).toBe('off')
    })

    it('labels the default option as disabled when the platform default is off', () => {
      fixture.detectChanges()
      expect(selectFor(CASE).options[0].textContent?.trim()).toBe('Platform default (disabled)')
    })

    it('labels the default option as enabled when the platform default is on', () => {
      integrationType.listTypesIntegrationType.mockReturnValue(of([{ ...builtInTypes[0], enabledByDefault: true }]))
      fixture.detectChanges()
      expect(selectFor(CASE).options[0].textContent?.trim()).toBe('Platform default (enabled)')
    })

    it('surfaces a persisted allowlist as read-only text under the row', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ integrations: { [CASE]: ['readFile', 'ls'] } })))
      fixture.detectChanges()

      const restriction = (fixture.nativeElement as HTMLElement).querySelector(
        '.agent-config-form__integration-restriction'
      )
      expect(restriction?.textContent).toContain('readFile, ls')
    })
  })
})

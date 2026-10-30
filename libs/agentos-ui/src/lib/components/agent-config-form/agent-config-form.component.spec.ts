import { provideHttpClient } from '@angular/common/http'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  BASE_PATH,
  AgentConfigControllerService,
  AgentConfigDefaultsControllerService,
  AgentConfigExportService,
  IntegrationTypeControllerService,
  SkillSummary,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { SkillStateService } from '../../services/skill-state.service'
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
  let defaultsController: { getAgentConfigDefaults: jest.Mock }
  let integrationState: { loadNamespaceConfigs: jest.Mock; loadPlatformConfigs: jest.Mock }
  let integrationType: { listTypesIntegrationType: jest.Mock }
  let skillState: { listEffective: jest.Mock; listPlatform: jest.Mock }
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
      delegationTimeoutControl: { setValue: (v: number | null) => void; value: number | null; invalid: boolean }
      useDefaultDelegationTimeout: () => void
      defaultDelegationTimeoutSeconds: () => number | null
      nameControl: { setValue: (v: string) => void }
      builtInRows: () => Array<{
        type: string
        enabledByDefault: boolean
        state: { (): BuiltInState; set: (v: BuiltInState) => void }
        restrictedTools: string[] | null
      }>
      skillRows: () => Array<{
        selector: string
        name: string
        unresolved: boolean
        platform: boolean
        selected: { (): boolean; set: (v: boolean) => void }
      }>
      allSkillsSelected: { (): boolean; set: (v: boolean) => void }
      skillCatalogueUnavailable: () => boolean
      namedSkillSelectionDropped: () => boolean
      submit: () => void
    }

  function skill(name: string, overrides: Partial<SkillSummary> = {}): SkillSummary {
    return {
      id: `skill-${name}`,
      name,
      description: `${name} description`,
      namespaceId: 'ns-1',
      readOnly: false,
      ...overrides,
    }
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
    defaultsController = { getAgentConfigDefaults: jest.fn().mockReturnValue(of({ delegationTimeoutSeconds: 1800 })) }
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
    skillState = {
      listEffective: jest.fn().mockReturnValue(of([])),
      listPlatform: jest.fn().mockReturnValue(of([])),
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
        { provide: AgentConfigDefaultsControllerService, useValue: defaultsController },
        { provide: AgentConfigControllerService, useValue: controller },
        { provide: IntegrationConfigStateService, useValue: integrationState },
        { provide: IntegrationTypeControllerService, useValue: integrationType },
        { provide: SkillStateService, useValue: skillState },
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

  describe('skill selectors', () => {
    it('offers the effective catalogue of the namespace and selects what the agent declares', () => {
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy'), skill('migrate')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy'] })))

      component.ngOnInit()

      expect(skillState.listEffective).toHaveBeenCalledWith('ns-1')
      const rows = internals().skillRows()
      expect(rows.map((r) => r.name)).toEqual(['deploy', 'migrate'])
      expect(rows.find((r) => r.name === 'deploy')?.selected()).toBe(true)
      expect(rows.find((r) => r.name === 'migrate')?.selected()).toBe(false)
    })

    it('matches a selector written as a path or in another case against the catalogue', () => {
      // Mirrors the normalisation findSkills applies, so a YAML-authored selector does not
      // show up as unknown and get offered for removal.
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy'), skill('migrate')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy/SKILL.md', 'MIGRATE'] })))

      component.ngOnInit()

      const rows = internals().skillRows()
      expect(rows.every((r) => r.selected())).toBe(true)
      expect(rows.some((r) => r.unresolved)).toBe(false)
    })

    it('keeps a selector nothing matches, flagged, and writes it back verbatim', () => {
      // The regression this guards: saving an unrelated change must not drop a selector naming
      // a skill that was renamed, deleted, or lives in another namespace.
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy', 'ghost-skill'] })))

      component.ngOnInit()
      const ghost = internals()
        .skillRows()
        .find((r) => r.name === 'ghost-skill')
      expect(ghost?.unresolved).toBe(true)
      expect(ghost?.selected()).toBe(true)

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].skillSelectors).toEqual(['deploy', 'ghost-skill'])
    })

    it('writes the star selector alone, and warns that it replaces the named selection', () => {
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy'] })))

      component.ngOnInit()
      expect(internals().namedSkillSelectionDropped()).toBe(false)
      internals().allSkillsSelected.set(true)
      expect(internals().namedSkillSelectionDropped()).toBe(true)

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].skillSelectors).toEqual(['*'])
    })

    it('hydrates the star selector from the agent', () => {
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['*'] })))

      component.ngOnInit()

      expect(internals().allSkillsSelected()).toBe(true)
      // `*` is not a named row: it must not surface as an unknown selector.
      expect(internals().skillRows().length).toBe(1)
      expect(internals().skillRows()[0].unresolved).toBe(false)
    })

    it('clears the field when the user deselects everything', () => {
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(of([skill('deploy')]))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy'] })))

      component.ngOnInit()
      internals().skillRows()[0].selected.set(false)

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].skillSelectors).toBeUndefined()
    })

    it('writes the selected names on create', () => {
      skillState.listEffective.mockReturnValue(of([skill('deploy'), skill('migrate')]))

      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals()
        .skillRows()
        .find((r) => r.name === 'migrate')!
        .selected.set(true)

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].skillSelectors).toEqual(['migrate'])
    })

    it('stays fail-safe when the catalogue call errors, and preserves the declared selectors', () => {
      routeAgentConfigId = 'a-1'
      skillState.listEffective.mockReturnValue(throwError(() => new Error('boom')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ skillSelectors: ['deploy'] })))

      component.ngOnInit()

      expect(internals().skillCatalogueUnavailable()).toBe(true)
      expect(
        internals()
          .skillRows()
          .map((r) => r.selector)
      ).toEqual(['deploy'])

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].skillSelectors).toEqual(['deploy'])
    })

    it('renders the catalogue in the skills section', () => {
      skillState.listEffective.mockReturnValue(of([skill('deploy')]))

      fixture.detectChanges()

      const text = (fixture.nativeElement as HTMLElement).textContent ?? ''
      expect(text).toContain('Skills')
      expect(text).toContain('deploy')
      expect(text).toContain('Every skill in the catalogue')
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
  it('keeps a new agent inheriting the actual server default', () => {
    component.ngOnInit()
    expect(internals().defaultDelegationTimeoutSeconds()).toBe(1800)
    internals().nameControl.setValue('new-agent')
    internals().submit()
    expect(controller.createAgentConfig.mock.calls[0][0].delegationTimeoutSeconds).toBeNull()
  })

  it('preserves a stored timeout when saving another field', () => {
    routeAgentConfigId = 'a-1'
    controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ delegationTimeoutSeconds: 3600 })))
    component.ngOnInit()
    expect(internals().delegationTimeoutControl.value).toBe(3600)
    internals().nameControl.setValue('renamed')
    internals().submit()
    expect(controller.updateAgentConfig.mock.calls[0][1].delegationTimeoutSeconds).toBe(3600)
  })

  it('clears an existing override explicitly when returning to the server default', () => {
    routeAgentConfigId = 'a-1'
    controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ delegationTimeoutSeconds: 3600 })))
    component.ngOnInit()
    internals().useDefaultDelegationTimeout()
    internals().submit()
    expect(controller.updateAgentConfig.mock.calls[0][1].delegationTimeoutSeconds).toBeNull()
  })

  it.each([0, -1, 1.5, 2147483648])('rejects invalid timeout %s', (value) => {
    component.ngOnInit()
    internals().nameControl.setValue('agent')
    internals().delegationTimeoutControl.setValue(value)
    internals().submit()
    expect(internals().delegationTimeoutControl.invalid).toBe(true)
    expect(controller.createAgentConfig).not.toHaveBeenCalled()
  })

  it('allows a custom timeout even if loading defaults fails', () => {
    defaultsController.getAgentConfigDefaults.mockReturnValue(throwError(() => new Error('unavailable')))
    component.ngOnInit()
    expect(internals().defaultDelegationTimeoutSeconds()).toBeNull()
    internals().nameControl.setValue('agent')
    internals().delegationTimeoutControl.setValue(1200)
    internals().submit()
    expect(controller.createAgentConfig.mock.calls[0][0].delegationTimeoutSeconds).toBe(1200)
  })

  it('renders the server default and lets users clear an override', () => {
    fixture.detectChanges()
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#delegation-timeout')
    expect(fixture.nativeElement.textContent).toContain('1800 s (30 min)')
    input.value = '3600'
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    const reset = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (button) => button.textContent?.includes('Use server default')
    )!
    reset.click()
    fixture.detectChanges()
    expect(input.value).toBe('')
    expect(internals().delegationTimeoutControl.value).toBeNull()
  })
})

describe('AgentConfigFormComponent (generated defaults HTTP client)', () => {
  it('requests JSON through the generated client and displays the server default', () => {
    TestBed.configureTestingModule({
      imports: [AgentConfigFormComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: '' },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { params: { namespaceId: 'ns-1' }, paramMap: { get: () => null } } },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        { provide: AgentConfigControllerService, useValue: {} },
        { provide: AgentConfigExportService, useValue: {} },
        {
          provide: IntegrationConfigStateService,
          useValue: { loadNamespaceConfigs: () => of([]), loadPlatformConfigs: () => of([]) },
        },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        // Stubbed so the only outstanding request this test verifies is the defaults one.
        { provide: SkillStateService, useValue: { listEffective: () => of([]), listPlatform: () => of([]) } },
      ],
    })
    const fixture = TestBed.createComponent(AgentConfigFormComponent)
    const http = TestBed.inject(HttpTestingController)
    fixture.detectChanges()

    const request = http.expectOne('/api/agent-configs/defaults')
    expect(request.request.method).toBe('GET')
    expect(request.request.responseType).toBe('json')
    expect(request.request.headers.get('Accept')).toBe('application/json')
    request.flush({ delegationTimeoutSeconds: 1800 }, { headers: { 'Content-Type': 'application/json' } })
    fixture.detectChanges()

    expect(fixture.nativeElement.querySelector('#delegation-timeout-hint').textContent).toContain('1800 s (30 min)')
    expect(fixture.nativeElement.querySelector('#delegation-timeout').value).toBe('')
    http.verify()
  })
})

/**
 * Platform scope has no namespace to resolve an effective catalogue against, so the form
 * falls back to the platform skills. A selector naming a namespace skill cannot be verified
 * here and must survive the round-trip untouched.
 */
describe('AgentConfigFormComponent (platform-scope skills)', () => {
  const platformSkill = {
    id: 'skill-shared',
    name: 'shared',
    description: 'shared description',
    namespaceId: null,
    readOnly: false,
    body: '# shared',
  }

  function setUp(config: Partial<AgentConfig> | null) {
    const controller = {
      getByIdAgentConfig: jest.fn().mockReturnValue(of({ id: 'a-1', name: 'agent', ...config })),
      createAgentConfig: jest.fn().mockReturnValue(of({})),
      updateAgentConfig: jest.fn().mockReturnValue(of({})),
    }
    const skillState = {
      listEffective: jest.fn().mockReturnValue(of([])),
      listPlatform: jest.fn().mockReturnValue(of([platformSkill])),
    }
    TestBed.configureTestingModule({
      imports: [AgentConfigFormComponent],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: {
            // No namespaceId segment: this is /admin/agent-configs/...
            snapshot: { params: {}, paramMap: { get: (k: string) => (k === 'agentConfigId' ? 'a-1' : null) } },
          },
        },
        { provide: Router, useValue: { navigate: jest.fn() } },
        {
          provide: AgentConfigDefaultsControllerService,
          useValue: { getAgentConfigDefaults: jest.fn().mockReturnValue(of(null)) },
        },
        { provide: AgentConfigControllerService, useValue: controller },
        { provide: IntegrationConfigStateService, useValue: { loadPlatformConfigs: () => of([]) } },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        { provide: SkillStateService, useValue: skillState },
        { provide: AgentConfigExportService, useValue: {} },
      ],
    })
    const fixture = TestBed.createComponent(AgentConfigFormComponent)
    return { fixture, controller, skillState }
  }

  it('offers the platform skills instead of an effective catalogue', () => {
    const { fixture, skillState } = setUp({ skillSelectors: ['shared'] })

    fixture.componentInstance.ngOnInit()

    expect(skillState.listPlatform).toHaveBeenCalled()
    expect(skillState.listEffective).not.toHaveBeenCalled()
    const rows = (
      fixture.componentInstance as unknown as {
        skillRows: () => Array<{ name: string; platform: boolean; selected: () => boolean }>
      }
    ).skillRows()
    expect(rows.map((r) => r.name)).toEqual(['shared'])
    expect(rows[0].platform).toBe(true)
    expect(rows[0].selected()).toBe(true)
  })

  it('preserves a namespace-scoped selector it cannot verify at platform level', () => {
    const { fixture, controller } = setUp({ skillSelectors: ['shared', 'namespace-only'] })
    const component = fixture.componentInstance as unknown as { ngOnInit: () => void; submit: () => void }

    component.ngOnInit()
    component.submit()

    expect(controller.updateAgentConfig.mock.calls[0][1].skillSelectors).toEqual(['shared', 'namespace-only'])
  })
})

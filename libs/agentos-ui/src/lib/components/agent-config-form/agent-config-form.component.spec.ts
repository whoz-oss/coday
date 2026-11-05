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
  AiModel,
  IntegrationTypeControllerService,
  SkillSummary,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { AiModelStateService } from '../../services/ai-model-state.service'
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
    listByParentAgentConfig: jest.Mock
    listPlatformAgentsAgentConfig: jest.Mock
    createAgentConfig: jest.Mock
    updateAgentConfig: jest.Mock
  }
  let defaultsController: { getAgentConfigDefaults: jest.Mock }
  let integrationState: { loadNamespaceConfigs: jest.Mock; loadPlatformConfigs: jest.Mock }
  let integrationType: { listTypesIntegrationType: jest.Mock }
  let skillState: { listEffective: jest.Mock; listPlatform: jest.Mock }
  let aiModelState: { listAvailable: jest.Mock }
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
      modelNameControl: { setValue: (v: string | null) => void; invalid: boolean; hasError: (e: string) => boolean }
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
      subAgentRows: () => Array<{
        selector: string
        name: string
        unresolved: boolean
        pattern: boolean
        grantedByPattern: boolean
        selected: { (): boolean; set: (v: boolean) => void }
      }>
      allSubAgentsSelected: { (): boolean; set: (v: boolean) => void }
      subAgentCandidatesUnavailable: () => boolean
      namedSubAgentSelectionDropped: () => boolean
      newSubAgentControl: { setValue: (v: string) => void; value: string }
      addSubAgent: () => void
      removeSubAgent: (row: { selector: string }) => void
      toggleAllSubAgents: () => void
      droppedUnresolvedSubAgents: () => number
      toggleSubAgent: (row: { pattern: boolean; selected: { (): boolean; set: (v: boolean) => void } }) => void
      submitError: () => string | null
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
      modelName: 'BIG',
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
      // The delegation candidates: the agents this one could be allowed to delegate to.
      listByParentAgentConfig: jest.fn().mockReturnValue(of([])),
      listPlatformAgentsAgentConfig: jest.fn().mockReturnValue(of([])),
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
    // Default: no model defined, so the form falls back to free text like it did before the select.
    aiModelState = { listAvailable: jest.fn().mockReturnValue(of([])) }
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
        { provide: AiModelStateService, useValue: aiModelState },
        { provide: AgentConfigExportService, useValue: { exportAsYaml: jest.fn() } },
      ],
    })

    fixture = TestBed.createComponent(AgentConfigFormComponent)
    component = fixture.componentInstance
    // The model name is required: a new agent starts with one so the tests below only vary what they are about.
    // Edit mode overwrites it from the loaded config (editConfig carries one).
    internals().modelNameControl.setValue('BIG')
  })

  describe('model name', () => {
    it('is required: a new agent without one is not submitted', () => {
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals().modelNameControl.setValue(null)

      internals().submit()

      expect(internals().modelNameControl.invalid).toBe(true)
      expect(controller.createAgentConfig).not.toHaveBeenCalled()
    })

    it.each(['', '   '])('refuses %j as a model name, since the server refuses it too', (value) => {
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals().modelNameControl.setValue(value)

      internals().submit()

      expect(internals().modelNameControl.invalid).toBe(true)
      expect(controller.createAgentConfig).not.toHaveBeenCalled()
    })

    it('is sent trimmed on create', () => {
      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals().modelNameControl.setValue('  SMALL  ')

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].modelName).toBe('SMALL')
    })

    it('blocks the update of an agent stored without one until it gets one', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ modelName: undefined })))
      component.ngOnInit()

      internals().submit()
      expect(controller.updateAgentConfig).not.toHaveBeenCalled()

      internals().modelNameControl.setValue('BIG')
      internals().submit()
      expect(controller.updateAgentConfig.mock.calls[0][1].modelName).toBe('BIG')
    })

    it('is flagged as required in the template', () => {
      fixture.detectChanges()
      const label = (fixture.nativeElement as HTMLElement).querySelector('label[for="agent-config-model-name"]')
      expect(label?.textContent).toContain('*')
    })

    describe('with models available', () => {
      const model = (overrides: Partial<AiModel>): AiModel => ({
        aiProviderId: 'p-1',
        apiModelName: 'x',
        priority: 0,
        ...overrides,
      })
      const modelSelect = () =>
        (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select#agent-config-model-name')

      beforeEach(() => {
        aiModelState.listAvailable.mockReturnValue(
          of([model({ alias: 'BIG', apiModelName: 'gpt-4o' }), model({ apiModelName: 'claude-sonnet' })])
        )
      })

      it('asks for the models of the route namespace', () => {
        component.ngOnInit()
        expect(aiModelState.listAvailable).toHaveBeenCalledWith('ns-1')
      })

      it('offers a select of aliases and API model names instead of a text field', () => {
        fixture.detectChanges()

        const select = modelSelect()
        expect(select).not.toBeNull()
        expect(select!.querySelector('input')).toBeNull()
        expect(Array.from(select!.querySelectorAll('optgroup')).map((g) => g.label)).toEqual([
          'Aliases',
          'API model names',
        ])
        expect(select!.textContent).toContain('BIG (gpt-4o)')
        expect(select!.textContent).toContain('claude-sonnet')
      })

      it('writes the chosen alias, not its label', () => {
        fixture.detectChanges()
        internals().nameControl.setValue('My Agent')
        const select = modelSelect()!
        const option = Array.from(select.options).find((o) => o.textContent?.includes('BIG (gpt-4o)'))!
        select.value = option.value
        select.dispatchEvent(new Event('change'))

        internals().submit()

        expect(controller.createAgentConfig.mock.calls[0][0].modelName).toBe('BIG')
      })

      it('keeps a stored model nothing lists, flagged, and writes it back on an unrelated save', () => {
        routeAgentConfigId = 'a-1'
        controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ modelName: 'RETIRED' })))
        fixture.detectChanges()

        expect(modelSelect()!.textContent).toContain('RETIRED (not in the list)')
        expect(modelSelect()!.value).not.toBe('')

        internals().submit()

        expect(controller.updateAgentConfig.mock.calls[0][1].modelName).toBe('RETIRED')
      })

      it('hydrates the stored alias on the select', () => {
        routeAgentConfigId = 'a-1'
        controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ modelName: 'BIG' })))
        fixture.detectChanges()

        const select = modelSelect()!
        expect(select.options[select.selectedIndex].textContent).toContain('BIG (gpt-4o)')
      })
    })

    it('falls back to a text field when no model can be listed', () => {
      aiModelState.listAvailable.mockReturnValue(of([]))
      fixture.detectChanges()

      const root = fixture.nativeElement as HTMLElement
      expect(root.querySelector('select#agent-config-model-name')).toBeNull()
      expect(root.querySelector('input#agent-config-model-name')).not.toBeNull()
    })
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

  /**
   * The allowlist is matched against agent names, so the section offers the names it can reach and
   * keeps every selector it cannot resolve — a renamed agent, a glob, or an id written before the
   * server refused it.
   */
  describe('sub-agent selectors', () => {
    const named = (...names: string[]) => names.map((name) => ({ name }) as AgentConfig)

    it('offers the agents the server resolves: platform plus namespace, enabled only', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer', 'BugFixer')))
      controller.listPlatformAgentsAgentConfig.mockReturnValue(of(named('PlatformHelper')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['BugFixer'] })))

      component.ngOnInit()

      // findDeployedByNamespaceIdAndUserId keeps `a.namespaceId IS NULL OR = $ns` and
      // `$withDisabled OR a.enabled`, the flag defaulting to false.
      expect(controller.listByParentAgentConfig).toHaveBeenCalledWith('ns-1', false)
      expect(controller.listPlatformAgentsAgentConfig).toHaveBeenCalledWith(false)
      const rows = internals().subAgentRows()
      expect(rows.map((r) => r.name)).toEqual(['PlatformHelper', 'Reviewer', 'BugFixer'])
      expect(rows.find((r) => r.name === 'BugFixer')?.selected()).toBe(true)
      expect(rows.find((r) => r.name === 'Reviewer')?.selected()).toBe(false)
    })

    it('says when a glob already reaches an agent whose own box is not ticked', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer', 'Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['*Fixer'] })))

      component.ngOnInit()

      const rows = internals().subAgentRows()
      // The run grants BugFixer through the glob; reporting it as outside the grant is a misreport.
      expect(rows.find((r) => r.name === 'BugFixer')?.grantedByPattern).toBe(true)
      expect(rows.find((r) => r.name === 'BugFixer')?.selected()).toBe(false)
      expect(rows.find((r) => r.name === 'Reviewer')?.grantedByPattern).toBe(false)
    })

    it('marks the rows a newly typed glob reaches', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer', 'Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*Fixer')
      internals().addSubAgent()

      const rows = internals().subAgentRows()
      expect(rows.find((r) => r.name === 'BugFixer')?.grantedByPattern).toBe(true)
      expect(rows.find((r) => r.name === 'Reviewer')?.grantedByPattern).toBe(false)
    })

    it('stops saying "via pattern" once the glob is removed', () => {
      // Found in the browser: the row went away and the badge stayed. The badge is a claim about
      // the config that will be saved, and a stale one invites unticking an agent's own box in the
      // belief a glob still covers it, which is the silent "cannot delegate" this section prevents.
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer', 'Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*Fixer')
      internals().addSubAgent()
      expect(
        internals()
          .subAgentRows()
          .find((r) => r.name === 'BugFixer')?.grantedByPattern
      ).toBe(true)

      internals().removeSubAgent({ selector: '*Fixer' })

      const rows = internals().subAgentRows()
      expect(rows.some((r) => r.selector === '*Fixer')).toBe(false)
      expect(rows.find((r) => r.name === 'BugFixer')?.grantedByPattern).toBe(false)
    })

    it('stops saying "via pattern" once the glob is unticked, since it would not be saved', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*Fixer')
      internals().addSubAgent()

      const patternRow = internals()
        .subAgentRows()
        .find((r) => r.selector === '*Fixer')!
      internals().toggleSubAgent(patternRow)

      expect(
        internals()
          .subAgentRows()
          .find((r) => r.name === 'BugFixer')?.grantedByPattern
      ).toBe(false)
    })

    it('stops saying "via pattern" when * takes over, since the glob is not saved either', () => {
      // buildSubAgentsPayload writes ['*'] alone, so the globs are dropped. The third path that
      // changes what gets saved, and the one the earlier fix left out.
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer', 'Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*Fixer')
      internals().addSubAgent()
      expect(
        internals()
          .subAgentRows()
          .find((r) => r.name === 'BugFixer')?.grantedByPattern
      ).toBe(true)

      internals().toggleAllSubAgents()

      expect(
        internals()
          .subAgentRows()
          .find((r) => r.name === 'BugFixer')?.grantedByPattern
      ).toBe(false)
    })

    it('counts the flagged selectors * would delete, which the form cannot restore', () => {
      // The section promises that what the agent already declares is kept. With * on, the payload
      // is ['*'] alone, so a stored id or a retired agent's name goes with it, and "replaces the
      // selection below" reads as the ticked names rather than as that deletion.
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(
        of(editConfig({ subAgents: ['*', '3f2504e0-4f89-41d3-9a0c-0305e82c3301', 'RetiredAgent'] }))
      )

      component.ngOnInit()

      expect(internals().droppedUnresolvedSubAgents()).toBe(2)
    })

    it('counts nothing to delete while * is off', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['RetiredAgent'] })))

      component.ngOnInit()

      expect(internals().droppedUnresolvedSubAgents()).toBe(0)
    })

    it('keeps the agent itself as a real row when its config already delegates to itself', () => {
      // buildDelegationTools resolves against every deployed agent, the agent included, so a
      // selector naming itself does build a delegation tool. Flagging it "no matching agent" had
      // the form contradict the run and invite deleting a selector that works.
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Orchestrator', 'BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(
        of(editConfig({ name: 'Orchestrator', subAgents: ['Orchestrator'] }))
      )

      component.ngOnInit()

      const self = internals()
        .subAgentRows()
        .find((r) => r.name === 'Orchestrator')
      expect(self?.unresolved).toBe(false)
      expect(self?.selected()).toBe(true)
    })

    it('does not offer the agent itself when its config does not name it', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Orchestrator', 'BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ name: 'Orchestrator', subAgents: [] })))

      component.ngOnInit()

      expect(
        internals()
          .subAgentRows()
          .some((r) => r.name === 'Orchestrator')
      ).toBe(false)
    })

    it('collapses names that differ only by case, which one selector grants together', () => {
      // Agent names are unique per scope and case-insensitively, but the platform and the
      // namespace are two scopes. The server grants both from one selector, so two rows would let
      // the user tick one, see the other unticked, and ship a grant covering both.
      routeAgentConfigId = 'a-1'
      controller.listPlatformAgentsAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.listByParentAgentConfig.mockReturnValue(of(named('reviewer', 'BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['Reviewer'] })))

      component.ngOnInit()

      const rows = internals().subAgentRows()
      expect(rows.filter((r) => r.name.toLowerCase() === 'reviewer').length).toBe(1)
      expect(rows.find((r) => r.name.toLowerCase() === 'reviewer')?.selected()).toBe(true)
    })

    it('keeps the platform agents when only the namespace list is refused', () => {
      // One catchError around both calls threw away the list that did load and flagged every
      // selector unverifiable, including platform ones that resolve.
      routeAgentConfigId = 'a-1'
      controller.listPlatformAgentsAgentConfig.mockReturnValue(of(named('PlatformHelper')))
      controller.listByParentAgentConfig.mockReturnValue(throwError(() => ({ status: 403 })))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['PlatformHelper'] })))

      component.ngOnInit()

      const row = internals()
        .subAgentRows()
        .find((r) => r.name === 'PlatformHelper')
      expect(row?.unresolved).toBe(false)
      expect(row?.selected()).toBe(true)
    })

    it('matches a selector written in another case, like the server glob does', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['reviewer'] })))

      component.ngOnInit()

      const rows = internals().subAgentRows()
      expect(rows.length).toBe(1)
      expect(rows[0].selected()).toBe(true)
      expect(rows[0].unresolved).toBe(false)
    })

    it('keeps a selector nothing matches, flagged, so an unrelated save preserves it', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['Retired'] })))

      component.ngOnInit()

      const ghost = internals()
        .subAgentRows()
        .find((r) => r.name === 'Retired')!
      expect(ghost.unresolved).toBe(true)
      expect(ghost.pattern).toBe(false)
      expect(ghost.selected()).toBe(true)
    })

    it('keeps an id as a flagged row, which is where the broken delegation becomes visible', () => {
      routeAgentConfigId = 'a-1'
      const id = '3f2504e0-4f89-41d3-9a0c-0305e82c3301'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: [id] })))

      component.ngOnInit()

      const row = internals()
        .subAgentRows()
        .find((r) => r.selector === id)!
      expect(row.unresolved).toBe(true)
      expect(row.selected()).toBe(true)
    })

    it('labels a glob as a pattern rather than as an agent nobody can find', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('BugFixer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['*Fixer'] })))

      component.ngOnInit()

      const row = internals()
        .subAgentRows()
        .find((r) => r.selector === '*Fixer')!
      expect(row.pattern).toBe(true)
      expect(row.selected()).toBe(true)
    })

    it('hydrates the star selector without listing it as a row', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['*'] })))

      component.ngOnInit()

      expect(internals().allSubAgentsSelected()).toBe(true)
      expect(internals().subAgentRows().length).toBe(1)
      expect(internals().subAgentRows()[0].unresolved).toBe(false)
    })

    it('never offers the edited agent itself, so the form cannot propose a delegation loop', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('agent', 'Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ name: 'agent' })))

      component.ngOnInit()

      expect(
        internals()
          .subAgentRows()
          .map((r) => r.name)
      ).toEqual(['Reviewer'])
    })

    it('writes the star alone, and warns while the trade is still reversible', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['Reviewer'] })))

      component.ngOnInit()
      internals().allSubAgentsSelected.set(true)
      expect(internals().namedSubAgentSelectionDropped()).toBe(true)

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].subAgents).toEqual(['*'])
    })

    it('clears the field when the user deselects everything', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['Reviewer'] })))

      component.ngOnInit()
      internals().subAgentRows()[0].selected.set(false)

      internals().submit()

      expect(controller.updateAgentConfig.mock.calls[0][1].subAgents).toBeUndefined()
    })

    it('writes the selected names on create', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer', 'BugFixer')))

      component.ngOnInit()
      internals().nameControl.setValue('My Agent')
      internals()
        .subAgentRows()
        .find((r) => r.name === 'BugFixer')!
        .selected.set(true)

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].subAgents).toEqual(['BugFixer'])
    })

    it('adds a typed glob as a selected row', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*Fixer')
      internals().addSubAgent()

      const row = internals()
        .subAgentRows()
        .find((r) => r.selector === '*Fixer')!
      expect(row.pattern).toBe(true)
      expect(row.selected()).toBe(true)
      expect(internals().newSubAgentControl.value).toBe('')
    })

    it('ticks the existing row when a name is typed instead of listing it twice', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('reviewer')
      internals().addSubAgent()

      expect(internals().subAgentRows().length).toBe(1)
      expect(internals().subAgentRows()[0].selected()).toBe(true)
    })

    it('reads a typed star as the all-agents grant, not as a row', () => {
      controller.listByParentAgentConfig.mockReturnValue(of(named('Reviewer')))

      component.ngOnInit()
      internals().newSubAgentControl.setValue('*')
      internals().addSubAgent()

      expect(internals().allSubAgentsSelected()).toBe(true)
      expect(internals().subAgentRows().length).toBe(1)
    })

    it('stays fail-safe when the agent list errors, and preserves the declared selectors', () => {
      routeAgentConfigId = 'a-1'
      controller.listByParentAgentConfig.mockReturnValue(throwError(() => new Error('boom')))
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ subAgents: ['Reviewer'] })))

      component.ngOnInit()

      expect(internals().subAgentCandidatesUnavailable()).toBe(true)
      expect(
        internals()
          .subAgentRows()
          .map((r) => r.selector)
      ).toEqual(['Reviewer'])
      expect(internals().subAgentRows()[0].selected()).toBe(true)
    })
  })

  /**
   * A refused save used to clear the submitting flag and say nothing, which made a validation
   * refusal indistinguishable from a dead button.
   */
  describe('server refusals', () => {
    it('shows the reason the server gave', () => {
      controller.createAgentConfig.mockReturnValue(
        throwError(() => ({
          status: 400,
          error: { message: 'subAgents takes glob patterns matched against the name of each agent, never its id' },
        }))
      )

      component.ngOnInit()
      internals().nameControl.setValue('fresh')
      internals().submit()

      expect(internals().submitError()).toBe(
        'subAgents takes glob patterns matched against the name of each agent, never its id'
      )
    })

    it('falls back to a generic line when a refusal carries no message', () => {
      // A real refusal with an empty body. status 0 is not one: it has its own line below.
      controller.createAgentConfig.mockReturnValue(throwError(() => ({ status: 400 })))

      component.ngOnInit()
      internals().nameControl.setValue('fresh')
      internals().submit()

      expect(internals().submitError()).toContain('gave no reason')
    })

    it('says the server could not be reached, instead of blaming the values', () => {
      // status 0 means the request never arrived: nothing was refused, so telling the user to
      // check their values sends them hunting a validation problem in a form that is valid.
      controller.createAgentConfig.mockReturnValue(throwError(() => ({ status: 0 })))

      component.ngOnInit()
      internals().nameControl.setValue('fresh')
      internals().submit()

      expect(internals().submitError()).toBe('The server could not be reached. The agent was not saved.')
    })

    it('names a server failure as one', () => {
      controller.createAgentConfig.mockReturnValue(throwError(() => ({ status: 502 })))

      component.ngOnInit()
      internals().nameControl.setValue('fresh')
      internals().submit()

      expect(internals().submitError()).toBe('The server failed to save the agent (HTTP 502). Nothing was changed.')
    })

    it('clears a previous refusal when the next save starts', () => {
      controller.createAgentConfig.mockReturnValueOnce(throwError(() => ({ error: { message: 'nope' } })))

      component.ngOnInit()
      internals().nameControl.setValue('fresh')
      internals().submit()
      expect(internals().submitError()).toBe('nope')

      controller.createAgentConfig.mockReturnValue(of({}))
      internals().submit()

      expect(internals().submitError()).toBeNull()
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

      const restriction = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('p')).find((p) =>
        p.textContent?.includes('Restricted to')
      )
      expect(restriction?.textContent).toContain('readFile, ls')
    })
  })
  describe('execution mode', () => {
    const modeControl = () =>
      (component as unknown as { executionModeControl: { value: string; setValue: (v: string) => void } })
        .executionModeControl

    it('defaults a new agent to SIMPLE and sends it explicitly', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-agent')

      internals().submit()

      expect(modeControl().value).toBe('SIMPLE')
      expect(controller.createAgentConfig.mock.calls[0][0].executionMode).toBe('SIMPLE')
    })

    it('sends the selected mode', () => {
      component.ngOnInit()
      internals().nameControl.setValue('new-agent')
      modeControl().setValue('ADVANCED')

      internals().submit()

      expect(controller.createAgentConfig.mock.calls[0][0].executionMode).toBe('ADVANCED')
      expect(controller.createAgentConfig.mock.calls[0][0].advancedExecution).toBe(true)
    })

    it('hydrates a config without executionMode as SIMPLE', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({})))

      component.ngOnInit()

      expect(modeControl().value).toBe('SIMPLE')
    })

    it('hydrates the legacy advancedExecution flag as ADVANCED', () => {
      routeAgentConfigId = 'a-1'
      controller.getByIdAgentConfig.mockReturnValue(of(editConfig({ advancedExecution: true })))

      component.ngOnInit()

      expect(modeControl().value).toBe('ADVANCED')
    })

    it('offers SIMPLE, ADVANCED and LOOP, without any "model default" option', () => {
      fixture.detectChanges()
      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
        '#agent-config-execution-mode'
      )!

      expect(Array.from(select.options).map((o) => o.value)).toEqual(['SIMPLE', 'ADVANCED', 'LOOP'])
      expect(select.textContent).not.toContain('Model default')
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
        {
          provide: AgentConfigControllerService,
          useValue: { listByParentAgentConfig: () => of([]), listPlatformAgentsAgentConfig: () => of([]) },
        },
        { provide: AgentConfigExportService, useValue: {} },
        {
          provide: IntegrationConfigStateService,
          useValue: { loadNamespaceConfigs: () => of([]), loadPlatformConfigs: () => of([]) },
        },
        { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType: () => of([]) } },
        // Stubbed so the only outstanding request this test verifies is the defaults one.
        { provide: SkillStateService, useValue: { listEffective: () => of([]), listPlatform: () => of([]) } },
        // Stubbed too: it would otherwise add model requests to the ones this test verifies.
        { provide: AiModelStateService, useValue: { listAvailable: () => of([]) } },
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
      getByIdAgentConfig: jest.fn().mockReturnValue(of({ id: 'a-1', name: 'agent', modelName: 'BIG', ...config })),
      createAgentConfig: jest.fn().mockReturnValue(of({})),
      updateAgentConfig: jest.fn().mockReturnValue(of({})),
      listByParentAgentConfig: jest.fn().mockReturnValue(of([])),
      listPlatformAgentsAgentConfig: jest.fn().mockReturnValue(of([])),
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
        { provide: AiModelStateService, useValue: { listAvailable: () => of([]) } },
        { provide: AgentConfigExportService, useValue: {} },
      ],
    })
    const fixture = TestBed.createComponent(AgentConfigFormComponent)
    return { fixture, controller, skillState }
  }

  it('asks only for the platform agents, and only the enabled ones', () => {
    // The platform branch of loadDelegationCandidates had no assertion, unlike its namespace twin.
    const { fixture, controller } = setUp({})

    fixture.componentInstance.ngOnInit()

    expect(controller.listPlatformAgentsAgentConfig).toHaveBeenCalledWith(false)
    expect(controller.listByParentAgentConfig).not.toHaveBeenCalled()
  })

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

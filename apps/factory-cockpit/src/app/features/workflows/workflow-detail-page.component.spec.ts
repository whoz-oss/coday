import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { MatDialog } from '@angular/material/dialog'
import { provideRouter } from '@angular/router'
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router'
import { of, Subject, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { WorkflowDetailPageComponent, formatJson } from './workflow-detail-page.component'

interface ApiStub {
  getWorkflowDefinition: jest.Mock
  createWorkflowDefinition: jest.Mock
  getNamespaces: jest.Mock
  getAgents: jest.Mock
  deleteWorkflowDefinition: jest.Mock
}

const FULL_DEF = {
  schemaVersion: '1',
  workflowType: 'adw_simple_sdlc',
  version: 'v1',
  title: 'Simple SDLC',
  steps: [{ id: 'plan', name: 'Plan' }],
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    deleteWorkflowDefinition: jest.fn().mockReturnValue(of({ deleted: true })),
    getNamespaces: jest.fn().mockReturnValue(of([{ id: 'ns', name: 'Team' }])),
    getAgents: jest.fn().mockReturnValue(of([{ name: 'Dev', enabled: true }])),
    getWorkflowDefinition: jest.fn().mockReturnValue(of(FULL_DEF)),
    createWorkflowDefinition: jest
      .fn()
      .mockReturnValue(of({ workflowType: 'adw_simple_sdlc', version: '2.0.0', definitionHash: 'server-hash' })),
    ...overrides,
  }
}

function createRoute(type = 'adw_simple_sdlc', version = 'v1'): Partial<ActivatedRoute> {
  return {
    snapshot: {
      paramMap: {
        get: (key: string) => (key === 'type' ? type : key === 'version' ? version : null),
      },
    } as unknown as ActivatedRoute['snapshot'],
  }
}

describe('WorkflowDetailPageComponent', () => {
  async function setup(
    api: ApiStub,
    route: Partial<ActivatedRoute> = createRoute(),
    confirmation = of(true)
  ): Promise<{ host: HTMLElement; fixture: ComponentFixture<WorkflowDetailPageComponent> }> {
    await TestBed.configureTestingModule({
      imports: [WorkflowDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: MatDialog, useValue: { open: jest.fn().mockReturnValue({ afterClosed: () => confirmation }) } },
        { provide: FactoryApiService, useValue: api },
        { provide: ActivatedRoute, useValue: route },
      ],
    }).compileComponents()
    jest.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true)
    const fixture = TestBed.createComponent(WorkflowDetailPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture }
  }

  function query<T extends HTMLElement>(host: HTMLElement, selector: string): T {
    const element = host.querySelector(selector)
    if (!element) throw new Error(`Expected element ${selector}`)
    return element as T
  }

  it('creates a workflow using visible block, discovery and predecessor controls without auto-running', async () => {
    const api = createApi({ createWorkflowDefinition: jest.fn().mockReturnValue(of({
      workflowType: 'analysis', version: '1.0.0', definitionHash: 'server-hash',
    })) })
    const route = createRoute('', '')
    const newRoute = { ...route, snapshot: { ...route.snapshot, data: { create: true } } } as Partial<ActivatedRoute>
    const { host, fixture } = await setup(api, newRoute)
    expect(api.getWorkflowDefinition).not.toHaveBeenCalled()
    expect(fixture.componentInstance.canLeave()).toBe(true)
    edit(host, '[data-workflow-type]', 'analysis')
    edit(host, '[data-workflow-name]', 'Analysis')
    fixture.componentInstance.state.loadDiscovery()
    fixture.detectChanges()
    const namespace = query<HTMLSelectElement>(host, '[data-agent-namespace]')
    namespace.value = 'ns'
    namespace.dispatchEvent(new Event('change'))
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-add-block]').click()
    fixture.detectChanges()
    const agent = query<HTMLSelectElement>(host, '[data-block-agent]')
    agent.value = 'Dev'
    agent.dispatchEvent(new Event('change'))
    edit(host, '[data-step-prompt]', 'Analyze request')
    fixture.detectChanges()
    expect(api.getAgents).toHaveBeenCalledWith('ns')
    expect(fixture.componentInstance.state.selected()?.responsibility?.name).toBe('Dev')
    query<HTMLButtonElement>(host, '[data-add-block]').click()
    fixture.detectChanges()
    const dependency = query<HTMLSelectElement>(host, '[data-dependency-select]')
    dependency.value = 'step-1'
    dependency.dispatchEvent(new Event('change'))
    fixture.detectChanges()
    expect(host.querySelector('[data-dependency="step-1:step-2"]')).not.toBeNull()
    query<HTMLButtonElement>(host, '[data-remove-block]').click()
    fixture.detectChanges()
    expect(host.querySelectorAll('[data-step-node]')).toHaveLength(1)
    query<HTMLButtonElement>(host, '[data-save-contract]').click()
    fixture.detectChanges()
    expect(api.createWorkflowDefinition).toHaveBeenCalledWith(expect.objectContaining({
      workflowType: 'analysis', title: 'Analysis', version: '1.0.0',
      steps: [expect.objectContaining({ responsibility: { kind: 'agent', name: 'Dev' }, mission: 'Analyze request', entry: [], exit: [], dependsOn: [] })],
    }))
    expect(fixture.componentInstance.state.dirty()).toBe(false)
    expect(fixture.componentInstance.canLeave()).toBe(true)
    expect(host.textContent).toContain('server-hash')
    expect(fixture.componentInstance.state.editing()).toBe(false)
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    expect(host.querySelector('[data-add-block]')).toBeNull()
    expect(host.querySelector('[data-save-contract]')).toBeNull()
    expect(host.querySelector('[data-workflow-diagram]')).not.toBeNull()
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(
      ['/workflows', 'analysis', '1.0.0'], { replaceUrl: true }
    )
  })

  it('composes human steps without discovery using styled controls and a multi-dependency dropdown', async () => {
    const api = createApi({ createWorkflowDefinition: jest.fn().mockReturnValue(of({
      workflowType: 'approval', version: '1.0.0', definitionHash: 'human-hash',
    })) })
    const route = createRoute('', '')
    const { host, fixture } = await setup(api, {
      ...route, snapshot: { ...route.snapshot, data: { create: true } },
    } as Partial<ActivatedRoute>)
    edit(host, '[data-workflow-type]', 'approval')
    edit(host, '[data-workflow-name]', 'Approval')
    for (const selector of ['[data-add-block]', '[data-add-human-step]', '[data-refresh-namespaces]']) {
      expect(query(host, selector).classList.contains('sf-secondary')).toBe(true)
      expect(query(host, selector).hasAttribute('mat-stroked-button')).toBe(true)
      expect(query(host, selector).querySelector('mat-icon')).not.toBeNull()
    }
    for (let i = 0; i < 3; i++) {
      query<HTMLButtonElement>(host, '[data-add-human-step]').click()
      fixture.detectChanges()
      edit(host, '[data-human-role]', i === 0 ? 'Product owner' : 'Reviewer')
      fixture.detectChanges()
    }
    expect(host.querySelector('[data-block-agent]')).toBeNull()
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    let dropdown = query<HTMLSelectElement>(host, '[data-dependency-select]')
    expect(Array.from(dropdown.options).map((option) => option.value)).toEqual(['', 'step-1', 'step-2'])
    expect(dropdown.options[1].textContent).toContain('Human step 1 (step-1)')
    for (const id of ['step-1', 'step-2']) {
      dropdown.value = id
      dropdown.dispatchEvent(new Event('change'))
      fixture.detectChanges()
    }
    expect(host.querySelectorAll('[data-remove-dependency]')).toHaveLength(2)
    expect(host.querySelectorAll('[data-dependency]')).toHaveLength(2)
    expect(Array.from(dropdown.options).map((option) => option.value)).toEqual([''])
    query<HTMLButtonElement>(host, '[data-remove-dependency="step-1"]').click()
    fixture.detectChanges()
    expect(host.querySelector('[data-dependency="step-1:step-3"]')).toBeNull()
    query<HTMLButtonElement>(host, '[data-step-node="step-2"]').click()
    fixture.detectChanges()
    dropdown = query<HTMLSelectElement>(host, '[data-dependency-select]')
    dropdown.value = 'step-3'
    dropdown.dispatchEvent(new Event('change'))
    fixture.detectChanges()
    expect(host.textContent).toContain('Dependency cycle')
    expect(query<HTMLButtonElement>(host, '[data-save-contract]').disabled).toBe(true)
    query<HTMLButtonElement>(host, '[data-remove-dependency="step-3"]').click()
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-save-contract]').click()
    fixture.detectChanges()
    expect(api.getNamespaces).not.toHaveBeenCalled()
    expect(api.getAgents).not.toHaveBeenCalled()
    expect(api.createWorkflowDefinition).toHaveBeenCalledWith(expect.objectContaining({
      steps: expect.arrayContaining([expect.objectContaining({ responsibility: { kind: 'human', name: 'Product owner' } })]),
    }))
    expect(host.querySelector('[data-add-human-step]')).toBeNull()
    expect(host.querySelector('[data-human-role]')).toBeNull()
    expect(host.querySelector('[data-edit-workflow]')).not.toBeNull()
    expect(host.textContent).toContain('approval@1.0.0')
  })

  it.each([false, true])('groups save actions and explains saved-version launch in the editor (creating: %s)', async (creating) => {
    const route = createRoute()
    const { host, fixture } = await setup(createApi(), creating ? {
      ...route, snapshot: { ...route.snapshot, data: { create: true } },
    } as Partial<ActivatedRoute> : route)
    if (!creating) {
      query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
      fixture.detectChanges()
    }
    const card = query(host, 'section[aria-label="Save step changes"]')
    const actions = query(card, '.save-actions')
    expect(query(card, '[data-save-contract]').parentElement).toBe(actions)
    expect(query(card, '[data-discard-contract]').parentElement).toBe(actions)
    expect(actions.querySelectorAll('button')).toHaveLength(2)
    expect(card.querySelector('a, [routerLink]')).toBeNull()
    expect(card.textContent).toContain('Save this workflow first. You can launch a saved version from Launch a run.')
    expect(card.textContent).toContain('Saving never starts a run.')
  })

  it('places styled Delete next to Edit in read-only mode, not in the editor', async () => {
    const { host, fixture } = await setup(createApi())
    const editButton = query<HTMLButtonElement>(host, '[data-edit-workflow]')
    const deleteButton = query<HTMLButtonElement>(host, '[data-delete-workflow]')
    expect(editButton.nextElementSibling).toBe(deleteButton)
    expect(deleteButton.classList.contains('sf-danger')).toBe(true)
    expect(deleteButton.getAttribute('aria-label')).toContain('Simple SDLC adw_simple_sdlc@v1')
    editButton.click()
    fixture.detectChanges()
    expect(host.querySelector('[data-delete-workflow]')).toBeNull()
  })

  it('never offers deletion on an unsaved new workflow', async () => {
    const route = createRoute('', '')
    const { host } = await setup(createApi(), {
      ...route, snapshot: { ...route.snapshot, data: { create: true } },
    } as Partial<ActivatedRoute>)
    expect(host.querySelector('[data-delete-workflow]')).toBeNull()
  })

  it('confirms name, type, version and impact; cancel is nonmutating', async () => {
    const api = createApi()
    const { host, fixture } = await setup(api, createRoute(), of(false))
    const before = fixture.componentInstance.state.definition()
    query<HTMLButtonElement>(host, '[data-delete-workflow]').click()
    fixture.detectChanges()
    const config = (TestBed.inject(MatDialog).open as jest.Mock).mock.calls[0][1]
    expect(config.data.detail).toBe('Simple SDLC · adw_simple_sdlc@v1')
    expect(config.data.destructive).toBe(true)
    expect(config.data.message).toContain('archived or purged')
    expect(config.data.message).toContain('Runs are not deleted or changed')
    expect(api.deleteWorkflowDefinition).not.toHaveBeenCalled()
    expect(fixture.componentInstance.state.definition()).toBe(before)
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled()
    expect(query<HTMLButtonElement>(host, '[data-delete-workflow]').disabled).toBe(false)
  })

  it('deletes the displayed exact identity once and navigates to the fresh list on success', async () => {
    const response = new Subject<unknown>()
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(of({ ...FULL_DEF, workflowType: 'exact', version: '3.0.0' })),
      deleteWorkflowDefinition: jest.fn().mockReturnValue(response),
    })
    const { host, fixture } = await setup(api)
    const button = query<HTMLButtonElement>(host, '[data-delete-workflow]')
    button.click()
    fixture.detectChanges()
    button.click()
    expect(api.deleteWorkflowDefinition).toHaveBeenCalledTimes(1)
    expect(api.deleteWorkflowDefinition).toHaveBeenCalledWith('exact', '3.0.0')
    expect(button.disabled).toBe(true)
    expect(query<HTMLButtonElement>(host, '[data-edit-workflow]').disabled).toBe(true)
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled()
    response.next({ deleted: true })
    response.complete()
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/workflows'])
  })

  it('does not delete an old identity if the route changes while confirmation is open', async () => {
    const confirmation = new Subject<boolean>()
    const api = createApi()
    const { host, fixture } = await setup(api, createRoute(), confirmation)
    query<HTMLButtonElement>(host, '[data-delete-workflow]').click()
    fixture.componentInstance.state.definition.set({ ...FULL_DEF, workflowType: 'other', version: '2.0.0' })
    confirmation.next(true)
    confirmation.complete()
    fixture.detectChanges()
    expect(api.deleteWorkflowDefinition).not.toHaveBeenCalled()
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled()
  })

  it.each([409, 403, 500])('keeps definition and route on delete failure (%s)', async (status) => {
    const response = new Subject<unknown>()
    const api = createApi({ deleteWorkflowDefinition: jest.fn().mockReturnValue(response) })
    const { host, fixture } = await setup(api)
    const before = fixture.componentInstance.state.definition()
    query<HTMLButtonElement>(host, '[data-delete-workflow]').click()
    response.error({ status, code: 'WORKFLOW_DEFINITION_IN_USE', message: 'A historical run pins this version.' })
    fixture.detectChanges()
    expect(query(host, '[data-delete-error]').textContent).toContain('A historical run pins this version.')
    expect(query(host, '[data-delete-error]').textContent).toContain('WORKFLOW_DEFINITION_IN_USE')
    expect(fixture.componentInstance.state.definition()).toBe(before)
    expect(fixture.componentInstance.state.editing()).toBe(false)
    expect(query<HTMLButtonElement>(host, '[data-delete-workflow]').disabled).toBe(false)
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled()
  })

  it('sets breadcrumbs with link to list and calls getWorkflowDefinition', async () => {
    const api = createApi()
    await setup(api)

    const crumbs = TestBed.inject(ShellState).crumbs()
    expect(crumbs[0]).toEqual({ label: 'Workflows', link: '/workflows' })
    expect(crumbs[1]).toMatchObject({ label: 'adw_simple_sdlc@v1', mono: true })
    expect(api.getWorkflowDefinition).toHaveBeenCalledWith('adw_simple_sdlc', 'v1')
  })

  it('renders the formatted JSON of the full definition', async () => {
    const api = createApi()
    const { host } = await setup(api)

    const block = query<HTMLElement>(host, '[data-workflow-detail-json]')
    const text = block.textContent ?? ''
    // Must contain key fields from the definition.
    expect(text).toContain('adw_simple_sdlc')
    expect(text).toContain('Simple SDLC')
    expect(text).toContain('plan')
    // JSON must be indented (pretty-printed).
    expect(text).toContain('  ')
  })

  it('renders configured Entry Mission Exit as readable escaped prose outside JSON', async () => {
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(
        of({
          ...FULL_DEF,
          steps: [
            {
              id: 'implement',
              name: 'Implement',
              entry: ['Sync branch'],
              mission: '<script>not executable</script>',
              exit: ['Commit', 'Push'],
            },
          ],
        })
      ),
    })
    const { host } = await setup(api)
    const contract = query<HTMLElement>(host, '[data-step-contract]')
    expect(contract.textContent).toContain('Sync branch')
    expect(contract.textContent).toContain('<script>not executable</script>')
    expect(contract.textContent).toContain('Commit')
    expect(contract.textContent).toContain('Push')
    expect(contract.querySelector('textarea, input, select')).toBeNull()
    expect(host.querySelector('[data-add-block]')).toBeNull()
    expect(host.querySelector('[data-save-contract]')).toBeNull()
    const diagram = query<HTMLElement>(host, '[data-workflow-diagram]')
    expect(diagram.compareDocumentPosition(contract) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(contract.querySelector('script')).toBeNull()
    expect(Array.from(contract.querySelectorAll('h3')).map((heading) => heading.textContent)).toEqual([
      'Pre-actions',
      'Prompt',
      'Post-actions',
    ])
  })

  function edit(host: HTMLElement, selector: string, value: string): void {
    const input = query<HTMLInputElement | HTMLTextAreaElement>(host, selector)
    input.value = value
    input.dispatchEvent(new Event('input'))
  }

  it('keeps long multiline preview prose intact and editable alongside the diagram scroll region', async () => {
    const mission = `Long prompt\n${'unbroken-token'.repeat(200)}\nLast paragraph`
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(of({
        ...FULL_DEF,
        steps: [{
          id: 'plan', name: 'Plan', responsibility: { kind: 'agent', name: 'Architect' },
          dependsOn: [], entry: [], mission, exit: [],
        }],
      })),
    })
    const { host, fixture } = await setup(api)
    const preview = query(host, '[data-step-preview] .prose')
    expect(preview.textContent).toBe(mission)
    const region = query(host, 'sf-workflow-definition-diagram [role="region"]')
    expect(region.classList.contains('scroll')).toBe(true)
    expect(region.getAttribute('tabindex')).toBe('0')
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').value).toBe(mission)
    expect(api.createWorkflowDefinition).not.toHaveBeenCalled()
  })

  it('selects graph nodes, keeps drafts between steps and saves only contracts with a new version', async () => {
    const steps = [
      {
        id: 'plan',
        name: 'Plan',
        responsibility: { kind: 'agent', name: 'Architect' },
        dependsOn: [],
        entry: ['Read'],
        mission: 'Analyze',
        exit: ['Brief'],
      },
      {
        id: 'build',
        name: 'Build',
        responsibility: { kind: 'agent', name: 'Dev' },
        dependsOn: ['plan'],
        entry: [],
        mission: 'Implement',
        exit: [],
      },
    ]
    const original = { ...FULL_DEF, version: '1.0.0', trustedExecution: true, steps }
    const api = createApi({ getWorkflowDefinition: jest.fn().mockReturnValue(of(original)) })
    const { host, fixture } = await setup(api)
    expect(host.querySelectorAll('[data-step-node]')).toHaveLength(2)
    expect(host.querySelector('[data-dependency="plan:build"]')).not.toBeNull()
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    edit(host, '[data-step-prompt]', 'Analyze carefully')
    edit(host, '[data-entry="0"]', 'Read input')
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-step-node="build"]').click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').value).toBe('Implement')
    query<HTMLButtonElement>(host, '[data-step-node="plan"]').click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').value).toBe('Analyze carefully')
    edit(host, '[data-target-version]', '2.0.0')
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-save-contract]').click()
    fixture.detectChanges()
    expect(api.createWorkflowDefinition).toHaveBeenCalledWith({
      ...original,
      version: '2.0.0',
      steps: [{ ...steps[0], mission: 'Analyze carefully', entry: ['Read input'] }, steps[1]],
    })
    expect(original.steps[0].mission).toBe('Analyze')
    expect(host.querySelector('[data-save-success]')?.textContent).toContain('server-hash')
    expect(host.querySelector('[data-save-contract]')).toBeNull()
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    expect(fixture.componentInstance.state.editing()).toBe(false)
    expect(fixture.componentInstance.state.definition()?.version).toBe('2.0.0')
    expect(TestBed.inject(ShellState).crumbs()[1].label).toBe('adw_simple_sdlc@2.0.0')
    query<HTMLButtonElement>(host, '[data-step-node="build"]').click()
    fixture.detectChanges()
    expect(host.querySelector('[data-step-preview]')?.textContent).toContain('Implement')
    expect(host.querySelector('[data-block-id]')).toBeNull()
  })

  it('honestly describes legacy defaults and explicitly adopts generic obligations', async () => {
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(
        of({
          ...FULL_DEF,
          steps: [{ id: 'plan', name: 'Analyze request', responsibility: { kind: 'agent', name: 'Architect' } }],
        })
      ),
    })
    const { host, fixture } = await setup(api)
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    expect(host.querySelector('[data-legacy-contract]')?.textContent).toContain('not an empty prompt')
    expect(host.querySelector('[data-adopt-contract]')).toBeNull()
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-adopt-contract]').click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').value).toBe('Analyze request')
    const actions = Array.from(host.querySelectorAll<HTMLTextAreaElement>('[data-exit]')).map((input) => input.value)
    expect(actions.join('\n')).toContain("kind 'run-brief'")
    expect(actions.join('\n')).toContain('do not claim checks that were not run')
    expect(host.textContent).toContain('Unsaved step changes')
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(true)
    query<HTMLButtonElement>(host, '[data-discard-contract]').click()
    confirm.mockRestore()
    fixture.detectChanges()
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    expect(api.createWorkflowDefinition).not.toHaveBeenCalled()
  })

  it.each(['Gitay', 'A11yReviewer'])('blocks unsafe static adoption for %s', async (name) => {
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(
        of({
          ...FULL_DEF,
          steps: [{ id: 'special', name, responsibility: { kind: 'agent', name } }],
        })
      ),
    })
    const { host } = await setup(api)
    expect(host.querySelector('[data-adoption-blocked]')).not.toBeNull()
    expect(host.querySelector('[data-adopt-contract]')).toBeNull()
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
  })

  it('validates edits, shows save progress and preserves dirty values on conflict', async () => {
    const response = new Subject<never>()
    const api = createApi({
      getWorkflowDefinition: jest
        .fn()
        .mockReturnValue(
          of({ ...FULL_DEF, steps: [{ id: 'plan', name: 'Plan', entry: [], mission: 'Plan', exit: [] }] })
        ),
      createWorkflowDefinition: jest.fn().mockReturnValue(response),
    })
    const { host, fixture } = await setup(api)
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    edit(host, '[data-step-prompt]', '   ')
    fixture.detectChanges()
    expect(query<HTMLButtonElement>(host, '[data-save-contract]').disabled).toBe(true)
    expect(host.textContent).toContain('Prompt must contain')
    edit(host, '[data-step-prompt]', 'Changed')
    edit(host, '[data-target-version]', '2.0.0')
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-save-contract]').click()
    fixture.detectChanges()
    expect(host.textContent).toContain('Saving…')
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').disabled).toBe(false) // fieldset enforces disabled state
    expect(host.querySelector('fieldset')?.disabled).toBe(true)
    response.error({ code: 'CONFLICT', message: 'Already exists', status: 409 })
    fixture.detectChanges()
    expect(host.querySelector('[data-workflow-detail-error]')?.textContent).toContain('Already exists')
    expect(query<HTMLTextAreaElement>(host, '[data-step-prompt]').value).toBe('Changed')
    expect(host.textContent).toContain('Unsaved step changes')
    expect(host.querySelector('[data-save-success]')).toBeNull()
    expect(fixture.componentInstance.state.editing()).toBe(true)
    expect(fixture.componentInstance.state.dirty()).toBe(true)
  })

  it('protects unsaved changes when leaving and on browser unload', async () => {
    const api = createApi({
      getWorkflowDefinition: jest.fn().mockReturnValue(
        of({
          ...FULL_DEF,
          steps: [{ id: 'plan', name: 'Plan', responsibility: { kind: 'agent', name: 'Architect' } }],
        })
      ),
    })
    const { host, fixture } = await setup(api)
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    query<HTMLButtonElement>(host, '[data-adopt-contract]').click()
    fixture.detectChanges()
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
    expect(fixture.componentInstance.canLeave()).toBe(false)
    confirm.mockReturnValue(true)
    expect(fixture.componentInstance.canLeave()).toBe(true)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    confirm.mockRestore()
  })

  it('loads another definition when Angular reuses the version route', async () => {
    const params = new Subject<ReturnType<typeof convertToParamMap>>()
    const route = { ...createRoute(), paramMap: params }
    const api = createApi()
    const { fixture } = await setup(api, route)
    params.next(convertToParamMap({ type: 'adw_simple_sdlc', version: 'v1' }))
    api.getWorkflowDefinition.mockReturnValue(of({ ...FULL_DEF, workflowType: 'other', version: '3.0.0' }))
    params.next(convertToParamMap({ type: 'other', version: '3.0.0' }))
    fixture.detectChanges()
    expect(api.getWorkflowDefinition).toHaveBeenLastCalledWith('other', '3.0.0')
    expect(fixture.componentInstance.state.definition()?.workflowType).toBe('other')
    expect(fixture.componentInstance.state.editing()).toBe(false)
    expect(TestBed.inject(ShellState).crumbs()[1].label).toBe('other@3.0.0')
  })

  it('cancels edits only after confirmation and restores the saved definition without mutation', async () => {
    const original = {
      ...FULL_DEF,
      steps: [{ id: 'plan', name: 'Plan', responsibility: { kind: 'agent', name: 'Dev' }, entry: [], mission: 'Saved prompt', exit: [] }],
    }
    const api = createApi({ getWorkflowDefinition: jest.fn().mockReturnValue(of(original)) })
    const { host, fixture } = await setup(api)
    // State mutations are rejected in view mode too, not merely hidden by the template.
    fixture.componentInstance.state.addBlock()
    fixture.componentInstance.state.update('mission', 'Not allowed')
    fixture.componentInstance.state.metadata('title', 'Not allowed')
    expect(fixture.componentInstance.state.dirty()).toBe(false)
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    edit(host, '[data-step-prompt]', 'Draft prompt')
    edit(host, '[data-workflow-name]', 'Draft title')
    query<HTMLButtonElement>(host, '[data-add-block]').click()
    fixture.detectChanges()
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
    query<HTMLButtonElement>(host, '[data-discard-contract]').click()
    fixture.detectChanges()
    expect(fixture.componentInstance.state.editing()).toBe(true)
    expect(fixture.componentInstance.state.dirty()).toBe(true)
    confirm.mockReturnValue(true)
    query<HTMLButtonElement>(host, '[data-discard-contract]').click()
    fixture.detectChanges()
    expect(fixture.componentInstance.state.editing()).toBe(false)
    expect(fixture.componentInstance.state.dirty()).toBe(false)
    expect(fixture.componentInstance.state.definition()).toEqual(original)
    expect(host.querySelectorAll('[data-step-node]')).toHaveLength(1)
    expect(host.querySelector('[data-step-preview]')?.textContent).toContain('Saved prompt')
    expect(host.querySelector('[data-step-prompt]')).toBeNull()
    expect(original.title).toBe('Simple SDLC')
    expect(original.steps[0].mission).toBe('Saved prompt')
    expect(api.createWorkflowDefinition).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it.each(['human', 'code'])('preserves read-only %s execution information', async (kind) => {
    const api = createApi({ getWorkflowDefinition: jest.fn().mockReturnValue(of({
      ...FULL_DEF, steps: [{ id: 'check', name: 'Check', responsibility: { kind, name: 'Verifier' } }],
    })) })
    const { host, fixture } = await setup(api)
    expect(host.querySelector('[data-step-contract]')?.textContent).toContain('Verifier')
    expect(host.querySelector(kind === 'human' ? '[data-human-checkpoint]' : '[data-legacy-contract]')?.textContent).toContain('existing approval or verification')
    query<HTMLButtonElement>(host, '[data-edit-workflow]').click()
    fixture.detectChanges()
    expect(!!host.querySelector('[data-remove-block]')).toBe(kind === 'human')
    expect(!!host.querySelector('[data-human-role]')).toBe(kind === 'human')
    expect(host.querySelector('[data-block-agent]')).toBeNull()
    expect(host.querySelector('[data-adopt-contract]')).toBeNull()
  })

  it('displays the title in the page header', async () => {
    const api = createApi()
    const { host } = await setup(api)

    const titleEl = host.querySelector('[data-workflow-detail-title]')
    expect(titleEl?.textContent?.trim()).toBe('Simple SDLC')
  })

  it('renders a back link pointing to the workflows list', async () => {
    const api = createApi()
    const { host } = await setup(api)

    const back = host.querySelector('[data-workflow-back]')
    expect(back).not.toBeNull()
    // Must be an anchor so it works on direct access and keyboard navigation.
    expect(back?.tagName.toLowerCase()).toBe('a')
    expect(back?.getAttribute('href')).toContain('workflows')
  })

  it('shows an error banner when the API call fails', async () => {
    const error: FactoryApiError = { code: 'NOT_FOUND', message: 'définition introuvable', status: 404, raw: null }
    const api = createApi({ getWorkflowDefinition: jest.fn().mockReturnValue(throwError(() => error)) })
    const { host } = await setup(api)

    const banner = query<HTMLElement>(host, '[data-workflow-detail-error]')
    expect(banner.textContent).toContain('définition introuvable')
    expect(host.querySelector('[data-workflow-detail-json]')).toBeNull()
  })

  it('shows an error when route params are missing', async () => {
    const api = createApi()
    const route = createRoute('', '')
    const { host } = await setup(api, route)

    expect(host.querySelector('[data-workflow-detail-error]')).not.toBeNull()
    expect(api.getWorkflowDefinition).not.toHaveBeenCalled()
  })
})

describe('formatJson', () => {
  it('returns indented JSON for objects', () => {
    const result = formatJson({ a: 1, b: 'x' })
    expect(result).toContain('\n')
    expect(result).toContain('  "a"')
    expect(JSON.parse(result)).toEqual({ a: 1, b: 'x' })
  })

  it('formats arrays', () => {
    const result = formatJson([1, 2, 3])
    expect(JSON.parse(result)).toEqual([1, 2, 3])
  })
})

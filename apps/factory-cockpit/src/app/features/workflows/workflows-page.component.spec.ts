import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, WorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { WorkflowsPageComponent } from './workflows-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
  getWorkflowDefinition: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of([])),
    getWorkflowDefinition: jest.fn().mockReturnValue(of({})),
    ...overrides,
  }
}

const definitions: WorkflowDefinition[] = [
  {
    workflowType: 'adw_simple_sdlc',
    version: 'v1',
    title: 'Simple SDLC',
    definitionHash: 'abcdef0123456789abcdef0123456789',
  },
  {
    workflowType: 'adw_full',
    version: 'v2',
    definitionHash: 'ffffffffffffffff',
  },
]

const fullDefinition = {
  schemaVersion: '1',
  workflowType: 'adw_simple_sdlc',
  version: 'v1',
  title: 'Simple SDLC',
  steps: [
    {
      id: 'plan',
      name: 'Plan',
      responsibility: { kind: 'agent', name: 'planner' },
    },
    {
      id: 'build',
      name: 'Build',
      responsibility: { kind: 'agent', name: 'builder' },
      dependsOn: ['plan'],
    },
  ],
}

describe('WorkflowsPageComponent', () => {
  async function setup(
    api: ApiStub
  ): Promise<{ host: HTMLElement; fixture: ComponentFixture<WorkflowsPageComponent> }> {
    await TestBed.configureTestingModule({
      imports: [WorkflowsPageComponent],
      providers: [provideNoopAnimations(), { provide: FactoryApiService, useValue: api }],
    }).compileComponents()
    const fixture = TestBed.createComponent(WorkflowsPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture }
  }

  function query<T extends HTMLElement>(host: HTMLElement, selector: string): T {
    const element = host.querySelector(selector)
    if (!element) throw new Error(`Expected element ${selector}`)
    return element as T
  }

  it('sets the "Workflows" breadcrumb and renders one card per definition', async () => {
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of({ items: definitions })) })
    const { host } = await setup(api)

    expect(TestBed.inject(ShellState).crumbs()).toEqual([{ label: 'Workflows' }])
    expect(api.getWorkflowDefinitions).toHaveBeenCalledTimes(1)

    const cards = host.querySelectorAll('[data-workflow-card]')
    expect(cards).toHaveLength(2)
    expect(host.textContent).toContain('Simple SDLC')
    expect(host.textContent).toContain('adw_simple_sdlc@v1')
    expect(host.textContent).toContain('adw_full@v2')
    // No title -> fall back to the workflowType.
    expect(query<HTMLElement>(host, '[data-workflow-card="adw_full@v2"]').textContent).toContain('adw_full')
    // Shortened hash is rendered.
    expect(host.querySelector('[data-workflow-hash]')?.textContent).toContain('abcdef012345…')
  })

  it('renders a raw array payload as definition cards', async () => {
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host } = await setup(api)
    expect(host.querySelectorAll('[data-workflow-card]')).toHaveLength(2)
  })

  it('renders the empty state when the list is empty', async () => {
    const api = createApi()
    const { host } = await setup(api)
    expect(host.querySelector('[data-workflow-empty]')).not.toBeNull()
    expect(host.textContent).toContain('Aucune définition de workflow enregistrée.')
  })

  it('loads the full definition on card click and renders step details', async () => {
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)),
      getWorkflowDefinition: jest.fn().mockReturnValue(of(fullDefinition)),
    })
    const { host, fixture } = await setup(api)

    query<HTMLButtonElement>(host, '[data-workflow-card-toggle="adw_simple_sdlc@v1"]').click()
    fixture.detectChanges()

    expect(api.getWorkflowDefinition).toHaveBeenCalledTimes(1)
    expect(api.getWorkflowDefinition).toHaveBeenCalledWith('adw_simple_sdlc', 'v1')

    const steps = host.querySelectorAll('[data-workflow-step]')
    expect(steps).toHaveLength(2)
    expect(host.querySelector('[data-step-id]')?.textContent).toContain('plan')
    expect(host.querySelector('[data-step-name]')?.textContent).toContain('Plan')

    const responsibilities = host.querySelectorAll('[data-step-responsibility]')
    expect(responsibilities).toHaveLength(2)
    expect(host.querySelector('[data-step-responsibility-kind]')?.textContent).toContain('agent')
    expect(host.querySelector('[data-step-responsibility-name]')?.textContent).toContain('planner')

    const deps = host.querySelectorAll('[data-step-depend]')
    expect(deps).toHaveLength(1)
    expect(deps[0].textContent).toContain('plan')
  })

  it('caches the loaded definition and does not refetch it on re-open', async () => {
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)),
      getWorkflowDefinition: jest.fn().mockReturnValue(of(fullDefinition)),
    })
    const { host, fixture } = await setup(api)

    const toggle = query<HTMLButtonElement>(host, '[data-workflow-card-toggle="adw_full@v2"]')
    toggle.click()
    fixture.detectChanges()
    toggle.click()
    fixture.detectChanges()
    toggle.click()
    fixture.detectChanges()

    expect(api.getWorkflowDefinition).toHaveBeenCalledTimes(1)
  })

  it('shows a friendly error banner when the definitions list fails', async () => {
    const error: FactoryApiError = {
      code: 'SERVICE_UNAVAILABLE',
      message: 'registre indisponible',
      status: 503,
      raw: null,
    }
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(throwError(() => error)) })
    const { host } = await setup(api)

    const banner = query<HTMLElement>(host, '[data-workflow-error]')
    expect(banner.textContent).toContain('registre indisponible')
    expect(host.querySelector('[data-workflow-card]')).toBeNull()
  })

  it('shows a per-card error banner when the full definition fails', async () => {
    const error: FactoryApiError = { code: 'NOT_FOUND', message: 'définition introuvable', status: 404, raw: null }
    const api = createApi({
      getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)),
      getWorkflowDefinition: jest.fn().mockReturnValue(throwError(() => error)),
    })
    const { host, fixture } = await setup(api)

    query<HTMLButtonElement>(host, '[data-workflow-card-toggle="adw_simple_sdlc@v1"]').click()
    fixture.detectChanges()

    const banner = query<HTMLElement>(host, '[data-workflow-detail-error]')
    expect(banner.textContent).toContain('définition introuvable')
    expect(host.querySelector('[data-workflow-steps]')).toBeNull()
  })
})

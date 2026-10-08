import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { provideRouter } from '@angular/router'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, WorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { WorkflowsPageComponent, extractWorkflowDefinitions } from './workflows-page.component'

interface ApiStub {
  getWorkflowDefinitions: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getWorkflowDefinitions: jest.fn().mockReturnValue(of([])),
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

describe('WorkflowsPageComponent', () => {
  async function setup(
    api: ApiStub
  ): Promise<{ host: HTMLElement; fixture: ComponentFixture<WorkflowsPageComponent> }> {
    await TestBed.configureTestingModule({
      imports: [WorkflowsPageComponent],
      providers: [provideNoopAnimations(), provideRouter([]), { provide: FactoryApiService, useValue: api }],
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
    expect(host.textContent).toContain('No workflow definitions registered.')
  })

  it('renders each card as a router link pointing to the detail route', async () => {
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host } = await setup(api)

    const link = query<HTMLAnchorElement>(host, '[data-workflow-card-link="adw_simple_sdlc@v1"]')
    // The element must be an anchor (navigable via keyboard and assistive tech).
    expect(link.tagName.toLowerCase()).toBe('a')
    // href must include the encoded type and version segments.
    expect(link.getAttribute('href')).toContain('adw_simple_sdlc')
    expect(link.getAttribute('href')).toContain('v1')
  })

  it('does not render any expansion panel or detail section in the list', async () => {
    const api = createApi({ getWorkflowDefinitions: jest.fn().mockReturnValue(of(definitions)) })
    const { host } = await setup(api)

    expect(host.querySelector('[data-workflow-detail]')).toBeNull()
    expect(host.querySelector('[data-workflow-steps]')).toBeNull()
    expect(host.querySelector('[data-workflow-card-toggle]')).toBeNull()
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
})

describe('extractWorkflowDefinitions', () => {
  it('handles a raw array', () => {
    const input = [{ workflowType: 'a', version: 'v1' }]
    expect(extractWorkflowDefinitions(input)).toEqual(input)
  })

  it('handles an { items } envelope', () => {
    const input = [{ workflowType: 'b', version: 'v2' }]
    expect(extractWorkflowDefinitions({ items: input })).toEqual(input)
  })

  it('handles a nested { data: { items } } envelope', () => {
    const input = [{ workflowType: 'c', version: 'v3' }]
    expect(extractWorkflowDefinitions({ data: { items: input } })).toEqual(input)
  })

  it('degrades to [] on unexpected input', () => {
    expect(extractWorkflowDefinitions(null)).toEqual([])
    expect(extractWorkflowDefinitions(undefined)).toEqual([])
    expect(extractWorkflowDefinitions('string')).toEqual([])
    expect(extractWorkflowDefinitions({})).toEqual([])
  })
})

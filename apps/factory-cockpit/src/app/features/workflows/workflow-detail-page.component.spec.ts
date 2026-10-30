import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { provideRouter } from '@angular/router'
import { ActivatedRoute } from '@angular/router'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { WorkflowDetailPageComponent, formatJson } from './workflow-detail-page.component'

interface ApiStub {
  getWorkflowDefinition: jest.Mock
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
    getWorkflowDefinition: jest.fn().mockReturnValue(of(FULL_DEF)),
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
    route: Partial<ActivatedRoute> = createRoute()
  ): Promise<{ host: HTMLElement; fixture: ComponentFixture<WorkflowDetailPageComponent> }> {
    await TestBed.configureTestingModule({
      imports: [WorkflowDetailPageComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: FactoryApiService, useValue: api },
        { provide: ActivatedRoute, useValue: route },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(WorkflowDetailPageComponent)
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture }
  }

  function query<T extends HTMLElement>(host: HTMLElement, selector: string): T {
    const element = host.querySelector(selector)
    if (!element) throw new Error(`Expected element ${selector}`)
    return element as T
  }

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

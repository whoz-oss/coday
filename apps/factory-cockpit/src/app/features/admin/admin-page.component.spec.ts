import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, RunConcurrencySettings } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { AdminPageComponent } from './admin-page.component'

const settings: RunConcurrencySettings = {
  maxParallelRuns: 5,
  defaultMaxParallelRuns: 5,
  min: 1,
  max: 20,
  updatedAt: null,
  updatedBy: null,
}

interface ApiStub {
  getRunConcurrency: jest.Mock
  updateRunConcurrency: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getRunConcurrency: jest.fn().mockReturnValue(of(settings)),
    updateRunConcurrency: jest.fn().mockReturnValue(of({ ...settings, maxParallelRuns: 7, updatedBy: 'alice' })),
    ...overrides,
  }
}

describe('AdminPageComponent', () => {
  async function setup(api: ApiStub): Promise<{ host: HTMLElement; fixture: ComponentFixture<AdminPageComponent> }> {
    await TestBed.configureTestingModule({
      imports: [AdminPageComponent],
      providers: [provideNoopAnimations(), { provide: FactoryApiService, useValue: api }],
    }).compileComponents()
    const fixture = TestBed.createComponent(AdminPageComponent)
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    return { host: fixture.nativeElement as HTMLElement, fixture }
  }

  function query<T extends HTMLElement>(host: HTMLElement, selector: string): T {
    const element = host.querySelector(selector)
    if (!element) throw new Error(`Expected element ${selector}`)
    return element as T
  }

  async function type(fixture: ComponentFixture<AdminPageComponent>, value: string): Promise<void> {
    const input = query<HTMLInputElement>(fixture.nativeElement, '[data-max-parallel-runs]')
    input.value = value
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
  }

  const save = (host: HTMLElement) => query<HTMLButtonElement>(host, '[data-admin-save]')

  it('sets the Admin breadcrumb and renders the current value and the default hint', async () => {
    const { host } = await setup(createApi())

    expect(TestBed.inject(ShellState).crumbs()).toEqual([{ label: 'Admin' }])
    expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').value).toBe('5')
    expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').min).toBe('1')
    expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').max).toBe('20')
    expect(query(host, '[data-admin-hint]').textContent).toContain('Default: 5')
    expect(query(host, '[data-admin-hint]').textContent).toContain('1–20')
    expect(host.textContent).toContain('Maximum parallel workflow runs')
  })

  it('shows who changed the value last when the server provides it', async () => {
    const api = createApi({
      getRunConcurrency: jest
        .fn()
        .mockReturnValue(of({ ...settings, updatedBy: 'alice', updatedAt: '2026-01-02T03:04:05Z' })),
    })
    const { host } = await setup(api)

    expect(query(host, '[data-admin-updated]').textContent).toContain('alice')
  })

  it('disables Save while the value is unchanged', async () => {
    const { host } = await setup(createApi())

    expect(save(host).disabled).toBe(true)
  })

  it.each(['0', '21', '2.5', ''])('rejects %p inline and disables Save', async (value) => {
    const { host, fixture } = await setup(createApi())

    await type(fixture, value)

    expect(save(host).disabled).toBe(true)
    expect(query(host, '[data-admin-validation]').textContent?.trim()).not.toBe('')
    expect(query(host, '[data-max-parallel-runs]').getAttribute('aria-invalid')).toBe('true')
  })

  it('saves a valid change and confirms it', async () => {
    const api = createApi()
    const { host, fixture } = await setup(api)

    await type(fixture, '7')
    expect(save(host).disabled).toBe(false)
    save(host).click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()

    expect(api.updateRunConcurrency).toHaveBeenCalledWith(7)
    expect(query(host, '[data-admin-saved]').textContent).toContain('7')
    expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').value).toBe('7')
    expect(save(host).disabled).toBe(true)
  })

  it('shows the API error message with its code when saving fails', async () => {
    const error: FactoryApiError = {
      code: 'INVALID_MAX_PARALLEL_RUNS',
      message: 'maxParallelRuns must be an integer between 1 and 20',
      status: 400,
      raw: null,
    }
    const api = createApi({ updateRunConcurrency: jest.fn().mockReturnValue(throwError(() => error)) })
    const { host, fixture } = await setup(api)

    await type(fixture, '7')
    save(host).click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()

    const banner = query(host, '[data-admin-save-error]')
    expect(banner.textContent).toContain('maxParallelRuns must be an integer between 1 and 20')
    expect(banner.textContent).toContain('INVALID_MAX_PARALLEL_RUNS')
    expect(host.querySelector('[data-admin-saved]')).toBeNull()
  })

  it('shows a load error with a Retry button that reloads', async () => {
    const error: FactoryApiError = { code: 'HTTP_503', message: 'Factory unavailable', status: 503, raw: null }
    const getRunConcurrency = jest.fn().mockReturnValueOnce(throwError(() => error)).mockReturnValue(of(settings))
    const { host, fixture } = await setup(createApi({ getRunConcurrency }))

    expect(query(host, '[data-admin-load-error]').textContent).toContain('Factory unavailable')
    expect(host.querySelector('[data-max-parallel-runs]')).toBeNull()

    query<HTMLButtonElement>(host, '[data-admin-retry]').click()
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()

    expect(getRunConcurrency).toHaveBeenCalledTimes(2)
    expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').value).toBe('5')
  })
})

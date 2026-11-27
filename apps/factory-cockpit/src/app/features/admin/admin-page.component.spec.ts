import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { of, throwError } from 'rxjs'
import {
  FactoryApiError,
  FactoryApiService,
  FactoryGitHubSettings,
  RunConcurrencySettings,
} from '../../core/factory-api.service'
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

const connected: FactoryGitHubSettings = {
  configured: true,
  connected: true,
  status: 'connected',
  source: 'admin',
  login: 'octocat',
  maskedToken: 'ghp_…6789',
  checkedAt: '2026-01-01T00:00:00Z',
}

const unconfigured: FactoryGitHubSettings = {
  configured: false,
  connected: false,
  status: 'unconfigured',
  source: 'none',
  message: 'No GitHub token is configured.',
}

interface ApiStub {
  getRunConcurrency: jest.Mock
  updateRunConcurrency: jest.Mock
  getGitHubToken: jest.Mock
  updateGitHubToken: jest.Mock
  clearGitHubToken: jest.Mock
  testGitHubToken: jest.Mock
}

function createApi(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    getRunConcurrency: jest.fn().mockReturnValue(of(settings)),
    updateRunConcurrency: jest.fn().mockReturnValue(of({ ...settings, maxParallelRuns: 7, updatedBy: 'alice' })),
    getGitHubToken: jest.fn().mockReturnValue(of(unconfigured)),
    updateGitHubToken: jest.fn().mockReturnValue(of(connected)),
    clearGitHubToken: jest.fn().mockReturnValue(of(unconfigured)),
    testGitHubToken: jest.fn().mockReturnValue(of(connected)),
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
    const getRunConcurrency = jest
      .fn()
      .mockReturnValueOnce(throwError(() => error))
      .mockReturnValue(of(settings))
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

  describe('GitHub token card', () => {
    async function settle(fixture: ComponentFixture<AdminPageComponent>): Promise<void> {
      fixture.detectChanges()
      await fixture.whenStable()
      fixture.detectChanges()
    }

    async function enterToken(fixture: ComponentFixture<AdminPageComponent>, value: string): Promise<void> {
      const input = query<HTMLInputElement>(fixture.nativeElement, '[data-github-token-input]')
      input.value = value
      input.dispatchEvent(new Event('input'))
      await settle(fixture)
    }

    const pill = (host: HTMLElement) => query(host, '[data-github-pill]')
    const saveToken = (host: HTMLElement) => query<HTMLButtonElement>(host, '[data-github-save]')

    it('loads the status next to the run limit, with the eyebrow and title', async () => {
      const api = createApi()
      const { host } = await setup(api)

      expect(api.getGitHubToken).toHaveBeenCalledTimes(1)
      expect(host.querySelector('[data-github-token] .sf-overline')?.textContent).toContain('CI integration')
      expect(query(host, '#github-token-title').textContent).toContain('GitHub PR checks token')
      expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').value).toBe('5')
    })

    it('shows a neutral Not connected pill and no Remove button when nothing is configured', async () => {
      const { host } = await setup(createApi())

      expect(pill(host).textContent).toContain('Not connected')
      expect(pill(host).classList.contains('pill--unconfigured')).toBe(true)
      expect(query(host, '[data-github-source]').textContent).toContain('None')
      expect(host.querySelector('[data-github-remove]')).toBeNull()
      expect(host.querySelector('[data-github-test]')).toBeNull()
      expect(saveToken(host).disabled).toBe(true)
    })

    it('shows the green Connected pill with the login, the source and the masked token', async () => {
      const { host } = await setup(createApi({ getGitHubToken: jest.fn().mockReturnValue(of(connected)) }))

      expect(pill(host).textContent).toContain('Connected')
      expect(pill(host).textContent).toContain('@octocat')
      expect(pill(host).classList.contains('pill--connected')).toBe(true)
      expect(query(host, '[data-github-source]').textContent).toContain('Admin token')
      expect(query(host, '[data-github-masked]').textContent).toContain('ghp_…6789')
      expect(host.querySelector('[data-github-remove]')).not.toBeNull()
    })

    it.each([
      ['invalid', 'Invalid token', 'GitHub rejected this token (HTTP 401).'],
      ['unreachable', 'GitHub unreachable', 'Could not reach GitHub (HTTP 503).'],
    ] as const)('shows the %s pill and its message', async (status, label, message) => {
      const result: FactoryGitHubSettings = {
        configured: true,
        connected: false,
        status,
        source: 'environment',
        maskedToken: '****',
        message,
      }
      const { host } = await setup(createApi({ getGitHubToken: jest.fn().mockReturnValue(of(result)) }))

      expect(pill(host).textContent).toContain(label)
      expect(pill(host).classList.contains(`pill--${status}`)).toBe(true)
      expect(query(host, '[data-github-message]').textContent).toContain(message)
      expect(query(host, '[data-github-source]').textContent).toContain('Environment')
      // An environment token cannot be removed from this page.
      expect(host.querySelector('[data-github-remove]')).toBeNull()
    })

    it('hides the token by default and reveals it on demand', async () => {
      const { host, fixture } = await setup(createApi())
      const input = query<HTMLInputElement>(host, '[data-github-token-input]')
      expect(input.type).toBe('password')
      expect(input.placeholder).toContain('ghp_')

      query<HTMLButtonElement>(host, '[data-github-toggle]').click()
      await settle(fixture)

      expect(query<HTMLInputElement>(host, '[data-github-token-input]').type).toBe('text')
    })

    it('saves a token, confirms the connection in green and clears the field', async () => {
      const api = createApi()
      const { host, fixture } = await setup(api)

      await enterToken(fixture, '  ghp_secret_value  ')
      expect(saveToken(host).disabled).toBe(false)
      saveToken(host).click()
      await settle(fixture)

      expect(api.updateGitHubToken).toHaveBeenCalledWith('ghp_secret_value')
      const feedback = query(host, '[data-github-feedback]')
      expect(feedback.textContent).toContain('Connected to GitHub as @octocat')
      expect(feedback.textContent).toContain('deterministic PR checks are ready')
      expect(feedback.classList.contains('banner--success')).toBe(true)
      expect(query<HTMLInputElement>(host, '[data-github-token-input]').value).toBe('')
      expect(pill(host).textContent).toContain('Connected')
      expect(host.textContent).not.toContain('ghp_secret_value')
    })

    it('shows a red alert and keeps what was typed when GitHub rejects the token', async () => {
      const rejected: FactoryGitHubSettings = {
        configured: true,
        connected: false,
        status: 'invalid',
        source: 'admin',
        message: 'GitHub rejected this token (HTTP 401).',
      }
      const api = createApi({ updateGitHubToken: jest.fn().mockReturnValue(of(rejected)) })
      const { host, fixture } = await setup(api)

      await enterToken(fixture, 'ghp_bad')
      saveToken(host).click()
      await settle(fixture)

      const feedback = query(host, '[data-github-feedback]')
      expect(feedback.textContent).toContain('GitHub rejected this token')
      expect(feedback.classList.contains('banner--error')).toBe(true)
      expect(pill(host).textContent).toContain('Invalid token')
      expect(query<HTMLInputElement>(host, '[data-github-token-input]').value).toBe('ghp_bad')
    })

    it('warns, without claiming a connection, when the token is saved while GitHub is unreachable', async () => {
      const offline: FactoryGitHubSettings = {
        configured: true,
        connected: false,
        status: 'unreachable',
        source: 'admin',
        message: 'Could not reach GitHub (HTTP 503).',
      }
      const api = createApi({ updateGitHubToken: jest.fn().mockReturnValue(of(offline)) })
      const { host, fixture } = await setup(api)

      await enterToken(fixture, 'ghp_offline')
      saveToken(host).click()
      await settle(fixture)

      const feedback = query(host, '[data-github-feedback]')
      expect(feedback.classList.contains('banner--warn')).toBe(true)
      expect(feedback.textContent).toContain('could not be reached')
      expect(feedback.textContent).not.toContain('Connected to GitHub')
    })

    it('shows the API error with its code when the save call fails', async () => {
      const error: FactoryApiError = { code: 'INVALID_VALUE', message: 'token must not be blank', status: 400, raw: null }
      const api = createApi({ updateGitHubToken: jest.fn().mockReturnValue(throwError(() => error)) })
      const { host, fixture } = await setup(api)

      await enterToken(fixture, 'x')
      saveToken(host).click()
      await settle(fixture)

      const feedback = query(host, '[data-github-feedback]')
      expect(feedback.classList.contains('banner--error')).toBe(true)
      expect(feedback.textContent).toContain('token must not be blank')
      expect(feedback.textContent).toContain('INVALID_VALUE')
    })

    it('removes the Admin token and tells what takes over', async () => {
      const fallback: FactoryGitHubSettings = {
        configured: true,
        connected: true,
        status: 'connected',
        source: 'environment',
        login: 'ci-bot',
        maskedToken: '****',
      }
      const api = createApi({
        getGitHubToken: jest.fn().mockReturnValue(of(connected)),
        clearGitHubToken: jest.fn().mockReturnValue(of(fallback)),
      })
      const { host, fixture } = await setup(api)

      query<HTMLButtonElement>(host, '[data-github-remove]').click()
      await settle(fixture)

      expect(api.clearGitHubToken).toHaveBeenCalledTimes(1)
      expect(query(host, '[data-github-feedback]').textContent).toContain('Falling back to the environment token')
      expect(query(host, '[data-github-source]').textContent).toContain('Environment')
      expect(host.querySelector('[data-github-remove]')).toBeNull()
    })

    it('says no token is configured after removing the only one', async () => {
      const api = createApi({ getGitHubToken: jest.fn().mockReturnValue(of(connected)) })
      const { host, fixture } = await setup(api)

      query<HTMLButtonElement>(host, '[data-github-remove]').click()
      await settle(fixture)

      expect(query(host, '[data-github-feedback]').textContent).toContain('No GitHub token is configured')
      expect(pill(host).textContent).toContain('Not connected')
    })

    it('re-tests the connection on demand', async () => {
      const invalid: FactoryGitHubSettings = { ...connected, connected: false, status: 'invalid', message: 'Revoked.' }
      const api = createApi({
        getGitHubToken: jest.fn().mockReturnValue(of(connected)),
        testGitHubToken: jest.fn().mockReturnValue(of(invalid)),
      })
      const { host, fixture } = await setup(api)

      query<HTMLButtonElement>(host, '[data-github-test]').click()
      await settle(fixture)

      expect(api.testGitHubToken).toHaveBeenCalledTimes(1)
      expect(pill(host).textContent).toContain('Invalid token')
    })

    it('shows a load error with a Retry button that reloads the status', async () => {
      const error: FactoryApiError = { code: 'HTTP_503', message: 'GitHub status unavailable', status: 503, raw: null }
      const getGitHubToken = jest
        .fn()
        .mockReturnValueOnce(throwError(() => error))
        .mockReturnValue(of(connected))
      const { host, fixture } = await setup(createApi({ getGitHubToken }))

      expect(query(host, '[data-github-load-error]').textContent).toContain('GitHub status unavailable')
      // The run limit card is unaffected by the GitHub failure.
      expect(query<HTMLInputElement>(host, '[data-max-parallel-runs]').value).toBe('5')

      query<HTMLButtonElement>(host, '[data-github-retry]').click()
      await settle(fixture)

      expect(getGitHubToken).toHaveBeenCalledTimes(2)
      expect(pill(host).textContent).toContain('Connected')
    })
  })
})

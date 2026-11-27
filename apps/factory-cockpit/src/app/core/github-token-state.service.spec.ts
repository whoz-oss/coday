import { TestBed } from '@angular/core/testing'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, FactoryGitHubSettings } from './factory-api.service'
import { GitHubTokenStateService } from './github-token-state.service'

const connected: FactoryGitHubSettings = {
  configured: true,
  connected: true,
  status: 'connected',
  source: 'admin',
  login: 'octocat',
  maskedToken: 'ghp_…6789',
}
const unconfigured: FactoryGitHubSettings = {
  configured: false,
  connected: false,
  status: 'unconfigured',
  source: 'none',
}

describe('GitHubTokenStateService', () => {
  function setup(api: Partial<Record<keyof FactoryApiService, jest.Mock>>): GitHubTokenStateService {
    TestBed.configureTestingModule({
      providers: [GitHubTokenStateService, { provide: FactoryApiService, useValue: api }],
    })
    return TestBed.inject(GitHubTokenStateService)
  }

  it('loads the status', () => {
    const state = setup({ getGitHubToken: jest.fn().mockReturnValue(of(connected)) })

    state.load()

    expect(state.settings()).toEqual(connected)
    expect(state.loading()).toBe(false)
    expect(state.error()).toBeNull()
  })

  it('exposes a load failure and keeps no status', () => {
    const error: FactoryApiError = { code: 'HTTP_503', message: 'down', status: 503, raw: null }
    const state = setup({ getGitHubToken: jest.fn().mockReturnValue(throwError(() => error)) })

    state.load()

    expect(state.settings()).toBeNull()
    expect(state.error()).toEqual(error)
    expect(state.loading()).toBe(false)
  })

  it('save, clear and test replace the status with the response', () => {
    const api = {
      updateGitHubToken: jest.fn().mockReturnValue(of(connected)),
      clearGitHubToken: jest.fn().mockReturnValue(of(unconfigured)),
      testGitHubToken: jest.fn().mockReturnValue(of(connected)),
    }
    const state = setup(api)

    state.save('ghp_secret').subscribe()
    expect(api.updateGitHubToken).toHaveBeenCalledWith('ghp_secret')
    expect(state.settings()).toEqual(connected)
    expect(state.saving()).toBe(false)

    state.clear().subscribe()
    expect(state.settings()).toEqual(unconfigured)

    state.test().subscribe()
    expect(state.settings()).toEqual(connected)
  })

  it('a failing command sets the error, rethrows it and keeps the previous status', () => {
    const error: FactoryApiError = { code: 'INVALID_VALUE', message: 'blank', status: 400, raw: null }
    const state = setup({
      getGitHubToken: jest.fn().mockReturnValue(of(connected)),
      updateGitHubToken: jest.fn().mockReturnValue(throwError(() => error)),
    })
    state.load()
    let thrown: FactoryApiError | undefined

    state.save('').subscribe({ error: (e: FactoryApiError) => (thrown = e) })

    expect(thrown).toEqual(error)
    expect(state.error()).toEqual(error)
    expect(state.settings()).toEqual(connected)
    expect(state.saving()).toBe(false)
  })
})

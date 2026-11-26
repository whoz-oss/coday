import { TestBed } from '@angular/core/testing'
import { of, throwError } from 'rxjs'
import { FactoryApiError, FactoryApiService, RunConcurrencySettings } from './factory-api.service'
import { RunConcurrencyStateService } from './run-concurrency-state.service'

const settings: RunConcurrencySettings = {
  maxParallelRuns: 5,
  defaultMaxParallelRuns: 5,
  min: 1,
  max: 20,
  updatedAt: null,
  updatedBy: null,
}

describe('RunConcurrencyStateService', () => {
  function setup(api: Partial<Record<keyof FactoryApiService, jest.Mock>>): RunConcurrencyStateService {
    TestBed.configureTestingModule({
      providers: [RunConcurrencyStateService, { provide: FactoryApiService, useValue: api }],
    })
    return TestBed.inject(RunConcurrencyStateService)
  }

  it('loads the settings', () => {
    const state = setup({ getRunConcurrency: jest.fn().mockReturnValue(of(settings)) })

    state.load()

    expect(state.settings()).toEqual(settings)
    expect(state.loading()).toBe(false)
    expect(state.error()).toBeNull()
  })

  it('exposes a load failure and keeps no settings', () => {
    const error: FactoryApiError = { code: 'HTTP_503', message: 'down', status: 503, raw: null }
    const state = setup({ getRunConcurrency: jest.fn().mockReturnValue(throwError(() => error)) })

    state.load()

    expect(state.settings()).toBeNull()
    expect(state.error()).toEqual(error)
    expect(state.loading()).toBe(false)
  })

  it('save updates the settings from the response', () => {
    const updated = { ...settings, maxParallelRuns: 7, updatedBy: 'alice' }
    const api = { updateRunConcurrency: jest.fn().mockReturnValue(of(updated)) }
    const state = setup(api)
    const done = jest.fn()

    state.save(7).subscribe(done)

    expect(api.updateRunConcurrency).toHaveBeenCalledWith(7)
    expect(state.settings()).toEqual(updated)
    expect(state.saving()).toBe(false)
    expect(state.error()).toBeNull()
    expect(done).toHaveBeenCalled()
  })

  it('a save failure sets the error, rethrows it and keeps the previous settings', () => {
    const error: FactoryApiError = {
      code: 'INVALID_MAX_PARALLEL_RUNS',
      message: 'out of range',
      status: 400,
      raw: null,
    }
    const state = setup({
      getRunConcurrency: jest.fn().mockReturnValue(of(settings)),
      updateRunConcurrency: jest.fn().mockReturnValue(throwError(() => error)),
    })
    state.load()
    let thrown: FactoryApiError | undefined

    state.save(0).subscribe({ error: (e: FactoryApiError) => (thrown = e) })

    expect(thrown).toEqual(error)
    expect(state.error()).toEqual(error)
    expect(state.settings()).toEqual(settings)
    expect(state.saving()).toBe(false)
  })
})

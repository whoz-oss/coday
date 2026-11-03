import { NEVER, of, throwError } from 'rxjs'
import { loadCurrentUser } from './load-current-user'

describe('loadCurrentUser', () => {
  beforeEach(() => jest.spyOn(console, 'warn').mockImplementation(() => undefined))
  afterEach(() => {
    jest.useRealTimers()
    jest.restoreAllMocks()
  })

  it('resolves once the user is loaded', async () => {
    await expect(loadCurrentUser({ loadMe: () => of({ id: 'u1' }) })).resolves.toBeUndefined()
  })

  it('resolves (does not reject) when the request fails', async () => {
    await expect(loadCurrentUser({ loadMe: () => throwError(() => new Error('502')) })).resolves.toBeUndefined()
    expect(console.warn).toHaveBeenCalled()
  })

  it('gives up on a request that never answers, so the bootstrap is not blocked', async () => {
    jest.useFakeTimers()
    const done = loadCurrentUser({ loadMe: () => NEVER }, 100)
    jest.advanceTimersByTime(100)
    await expect(done).resolves.toBeUndefined()
    expect(console.warn).toHaveBeenCalled()
  })
})

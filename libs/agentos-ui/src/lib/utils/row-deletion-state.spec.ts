import { Subject, of, throwError } from 'rxjs'
import { createRowDeletionState } from './row-deletion-state'

describe('createRowDeletionState', () => {
  it('refuses execution without confirmation and does not subscribe', () => {
    const state = createRowDeletionState()
    const request$ = new Subject<string>()
    const subscribe = jest.spyOn(request$, 'subscribe')

    state.execute('row-1', { request$, errorMessage: 'Delete failed' })

    expect(subscribe).not.toHaveBeenCalled()
    expect(state.deletingId()).toBeNull()
  })

  it('starts deleting while retaining confirmation and clears a previous error', () => {
    const state = createRowDeletionState()
    const request$ = new Subject<string>()
    state.request('row-1')
    state.execute('row-1', { request$: throwError(() => new Error()), errorMessage: 'Delete failed' })

    state.execute('row-1', { request$, errorMessage: 'Delete failed' })

    expect(state.confirmingId()).toBe('row-1')
    expect(state.deletingId()).toBe('row-1')
    expect(state.error()).toBeNull()
  })

  it('blocks duplicate execution while a request is pending', () => {
    const state = createRowDeletionState()
    const first$ = new Subject<string>()
    const duplicate$ = new Subject<string>()
    const subscribe = jest.spyOn(duplicate$, 'subscribe')
    state.request('row-1')

    state.execute('row-1', { request$: first$, errorMessage: 'Delete failed' })
    state.execute('row-1', { request$: duplicate$, errorMessage: 'Delete failed' })

    expect(subscribe).not.toHaveBeenCalled()
  })

  it('calls onSuccess with the emitted value and clears all deletion state', () => {
    const state = createRowDeletionState()
    const onSuccess = jest.fn()
    state.request('row-1')

    state.execute('row-1', { request$: of('deleted'), errorMessage: 'Delete failed', onSuccess })

    expect(onSuccess).toHaveBeenCalledWith('deleted')
    expect(state.confirmingId()).toBeNull()
    expect(state.deletingId()).toBeNull()
    expect(state.error()).toBeNull()
  })

  it('retains confirmation for retry and exposes a static error without calling onSuccess', () => {
    const state = createRowDeletionState()
    const onSuccess = jest.fn()
    state.request('row-1')

    state.execute('row-1', {
      request$: throwError(() => new Error('server detail')),
      errorMessage: 'Delete failed',
      onSuccess,
    })

    expect(state.confirmingId()).toBe('row-1')
    expect(state.deletingId()).toBeNull()
    expect(state.error()).toBe('Delete failed')
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('passes the original error to a functional error message', () => {
    const state = createRowDeletionState()
    const cause = new Error('original')
    const errorMessage = jest.fn(() => 'Mapped failure')
    state.request('row-1')

    state.execute('row-1', { request$: throwError(() => cause), errorMessage })

    expect(errorMessage).toHaveBeenCalledWith(cause)
    expect(state.error()).toBe('Mapped failure')
  })

  it('allows a retry after failure to succeed', () => {
    const state = createRowDeletionState()
    const onSuccess = jest.fn()
    state.request('row-1')
    state.execute('row-1', { request$: throwError(() => new Error()), errorMessage: 'Delete failed' })

    state.execute('row-1', { request$: of('deleted'), errorMessage: 'Delete failed', onSuccess })

    expect(onSuccess).toHaveBeenCalledWith('deleted')
    expect(state.confirmingId()).toBeNull()
    expect(state.deletingId()).toBeNull()
    expect(state.error()).toBeNull()
  })

  it('ignores cancel and another row request while deleting', () => {
    const state = createRowDeletionState()
    state.request('row-1')
    state.execute('row-1', { request$: new Subject<void>(), errorMessage: 'Delete failed' })

    state.cancel()
    state.request('row-2')

    expect(state.confirmingId()).toBe('row-1')
    expect(state.deletingId()).toBe('row-1')
  })

  it('clears confirmation and error when canceling before execution', () => {
    const state = createRowDeletionState()
    state.request('row-1')
    state.execute('row-1', { request$: throwError(() => new Error()), errorMessage: 'Delete failed' })

    state.cancel()

    expect(state.confirmingId()).toBeNull()
    expect(state.deletingId()).toBeNull()
    expect(state.error()).toBeNull()
  })
})

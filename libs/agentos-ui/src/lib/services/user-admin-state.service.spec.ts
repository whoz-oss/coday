import { TestBed } from '@angular/core/testing'
import { UserControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { UserAdminStateService } from './user-admin-state.service'

const user = (id: string) => ({ id, isAdmin: false })
const page = (ids: string[], next: string | null, totalCount: number) => ({
  data: ids.map(user),
  metadata: { next, totalCount },
})

describe('UserAdminStateService', () => {
  let api: { listUser: jest.Mock; getByIdUser: jest.Mock; updateUser: jest.Mock; deleteUser: jest.Mock }
  let state: UserAdminStateService

  beforeEach(() => {
    api = { listUser: jest.fn(), getByIdUser: jest.fn(), updateUser: jest.fn(), deleteUser: jest.fn() }
    TestBed.configureTestingModule({ providers: [{ provide: UserControllerService, useValue: api }] })
    state = TestBed.inject(UserAdminStateService)
  })

  it('loads the first page, then appends with the cursor until it is null', () => {
    api.listUser.mockReturnValueOnce(of(page(['u1', 'u2'], 'c1', 3))).mockReturnValueOnce(of(page(['u3'], null, 3)))

    state.reset(null, '')
    expect(api.listUser).toHaveBeenLastCalledWith({ filters: { namespaceId: undefined, query: undefined } })
    expect(state.users().map((u) => u.id)).toEqual(['u1', 'u2'])
    expect(state.totalCount()).toBe(3)

    state.loadMore()
    expect(api.listUser).toHaveBeenLastCalledWith(expect.objectContaining({ next: 'c1' }))
    expect(state.users().map((u) => u.id)).toEqual(['u1', 'u2', 'u3'])

    state.loadMore()
    expect(api.listUser).toHaveBeenCalledTimes(2)
  })

  it('resets on a filter change and ignores the response of a superseded request', () => {
    const stale = new Subject<ReturnType<typeof page>>()
    api.listUser.mockReturnValueOnce(stale).mockReturnValueOnce(of(page(['fresh'], null, 1)))

    state.reset(null, '')
    state.loadMore() // no-op while the first load is in flight
    state.reset('ns-1', ' ali ')
    expect(api.listUser).toHaveBeenCalledTimes(2)
    expect(api.listUser).toHaveBeenLastCalledWith({ filters: { namespaceId: 'ns-1', query: 'ali' } })

    stale.next(page(['stale'], 'c9', 9))
    expect(state.users().map((u) => u.id)).toEqual(['fresh'])
    expect(state.nextCursor()).toBeNull()
  })

  it('keeps the rows and retries the same cursor after a load-more failure', () => {
    const failing = new Subject<never>()
    api.listUser
      .mockReturnValueOnce(of(page(['u1'], 'c1', 2)))
      .mockReturnValueOnce(failing)
      .mockReturnValueOnce(of(page(['u2'], null, 2)))
    state.reset(null, '')
    jest.spyOn(console, 'error').mockImplementation()

    state.loadMore()
    failing.error(new Error('boom'))
    expect(state.loadError()).toBe(true)
    expect(state.users()).toHaveLength(1)

    state.loadMore()
    expect(api.listUser).toHaveBeenLastCalledWith(expect.objectContaining({ next: 'c1' }))
    expect(state.users().map((u) => u.id)).toEqual(['u1', 'u2'])
    expect(state.loadError()).toBe(false)
  })

  it('retries a failed first page through loadMore and clears the error on success', () => {
    jest.spyOn(console, 'error').mockImplementation()
    api.listUser.mockReturnValueOnce(throwError(() => new Error('boom'))).mockReturnValueOnce(of(page(['u1'], null, 1)))
    state.reset(null, '')
    expect(state.loadError()).toBe(true)

    state.loadMore()

    expect(api.listUser).toHaveBeenLastCalledWith({ filters: { namespaceId: undefined, query: undefined } })
    expect(state.users().map((u) => u.id)).toEqual(['u1'])
    expect(state.loadError()).toBe(false)
  })

  it('removes a deleted user from the list and decrements the total', () => {
    api.listUser.mockReturnValue(of(page(['u1', 'u2'], null, 2)))
    api.deleteUser.mockReturnValue(of(undefined))
    state.reset(null, '')

    state.deleteUser('u1').subscribe()

    expect(state.users().map((u) => u.id)).toEqual(['u2'])
    expect(state.totalCount()).toBe(1)
  })

  it('keeps the rows while a new filtered page loads, then replaces them', () => {
    const next = new Subject<ReturnType<typeof page>>()
    api.listUser.mockReturnValueOnce(of(page(['u1', 'u2'], 'c1', 2))).mockReturnValueOnce(next)
    state.reset(null, '')

    state.reset(null, 'ali')

    expect(state.isFiltered()).toBe(true)
    expect(state.isLoading()).toBe(true)
    expect(state.users().map((u) => u.id)).toEqual(['u1', 'u2'])
    expect(state.nextCursor()).toBeNull()

    next.next(page(['ali'], null, 1))
    expect(state.users().map((u) => u.id)).toEqual(['ali'])
    expect(state.totalCount()).toBe(1)
  })

  it('empties the rows when the first page of a new filter fails', () => {
    jest.spyOn(console, 'error').mockImplementation()
    api.listUser.mockReturnValueOnce(of(page(['u1'], null, 1))).mockReturnValueOnce(throwError(() => new Error('boom')))
    state.reset(null, '')

    state.reset(null, 'ali')

    expect(state.users()).toEqual([])
    expect(state.loadError()).toBe(true)
  })
})

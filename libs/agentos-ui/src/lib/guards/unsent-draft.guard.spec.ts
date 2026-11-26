import { DestroyRef, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { AGENTOS_ROUTES } from '../agentos.routes'
import { UnsentDraftService } from '../services/unsent-draft.service'
import { unsentDraftGuard } from './unsent-draft.guard'

describe('unsentDraftGuard', () => {
  const leave = (): unknown =>
    TestBed.runInInjectionContext(() => unsentDraftGuard(null as never, null as never, null as never, null as never))

  afterEach(() => TestBed.resetTestingModule())

  it('returns what the confirmation says', () => {
    const confirmDiscard = jest.spyOn(TestBed.inject(UnsentDraftService), 'confirmDiscard')

    confirmDiscard.mockReturnValueOnce(false)
    expect(leave()).toBe(false)
    confirmDiscard.mockReturnValueOnce(true)
    expect(leave()).toBe(true)
  })

  it('lets the user leave without asking when there is no draft', () => {
    const confirm = jest.spyOn(window, 'confirm')

    expect(leave()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('asks when a composer holds a draft, and keeps the user here when they cancel', () => {
    const confirm = jest.spyOn(window, 'confirm').mockReturnValue(false)
    TestBed.inject(UnsentDraftService).register(signal(true), TestBed.inject(DestroyRef))

    expect(leave()).toBe(false)
    expect(confirm).toHaveBeenCalledTimes(1)
    confirm.mockRestore()
  })
})

/**
 * Regression (#1788): every switch of case or namespace is a query param navigation on the `home` route, which Angular
 * does not guard unless the route says so. Before the change nothing guarded this route at all.
 */
describe('the home route of the case shell', () => {
  const home = AGENTOS_ROUTES.flatMap((route) => route.children ?? []).find((route) => route.path === 'home')

  it('asks before leaving with an unsent prompt', () => {
    expect(home?.canDeactivate).toContain(unsentDraftGuard)
  })

  it('runs its guards on a change of query params, which is how the case and the namespace change', () => {
    expect(home?.runGuardsAndResolvers).toBe('paramsOrQueryParamsChange')
  })
})

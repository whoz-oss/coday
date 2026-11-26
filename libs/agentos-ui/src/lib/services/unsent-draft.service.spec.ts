import { DestroyRef, EnvironmentInjector, createEnvironmentInjector, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { UNSENT_DRAFT_MESSAGE, UnsentDraftService } from './unsent-draft.service'

describe('UnsentDraftService', () => {
  let service: UnsentDraftService
  let scope: EnvironmentInjector
  let scopeDestroyed: boolean
  let confirm: jest.SpyInstance

  /** The composers end: what a registration was tied to is destroyed, once. */
  const endComposers = (): void => {
    if (scopeDestroyed) return
    scopeDestroyed = true
    scope.destroy()
  }

  /** What the browser does on a tab close or a reload: a cancelable event, which a draft makes prevented. */
  const unload = (): Event => {
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    return event
  }

  beforeEach(() => {
    service = TestBed.inject(UnsentDraftService)
    // The lifetime of a composer, which the test ends by destroying it.
    scope = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector))
    scopeDestroyed = false
    confirm = jest.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    endComposers()
    confirm.mockRestore()
    TestBed.resetTestingModule()
  })

  it('has no draft, and lets the user leave without asking, when no composer registered', () => {
    expect(service.hasUnsentDraft()).toBe(false)
    expect(service.confirmDiscard()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()
    expect(unload().defaultPrevented).toBe(false)
  })

  it('asks before leaving with a draft: cancelling keeps the user here, confirming lets them go', () => {
    service.register(signal(true), scope.get(DestroyRef))

    confirm.mockReturnValueOnce(false)
    expect(service.confirmDiscard()).toBe(false)
    confirm.mockReturnValueOnce(true)
    expect(service.confirmDiscard()).toBe(true)

    expect(confirm).toHaveBeenCalledTimes(2)
    expect(confirm).toHaveBeenCalledWith(UNSENT_DRAFT_MESSAGE)
  })

  it('does not ask for a registered composer that holds no draft', () => {
    service.register(signal(false), scope.get(DestroyRef))

    expect(service.hasUnsentDraft()).toBe(false)
    expect(service.confirmDiscard()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()
  })

  it('follows the draft as it is typed and cleared', () => {
    const hasDraft = signal(false)
    service.register(hasDraft, scope.get(DestroyRef))

    hasDraft.set(true)
    expect(service.hasUnsentDraft()).toBe(true)
    hasDraft.set(false)
    expect(service.hasUnsentDraft()).toBe(false)
  })

  it('counts a draft as soon as one of several composers holds one', () => {
    service.register(signal(false), scope.get(DestroyRef))
    service.register(signal(true), scope.get(DestroyRef))

    expect(service.hasUnsentDraft()).toBe(true)
  })

  it('makes the browser ask on a tab close or a reload while there is a draft, and not otherwise', () => {
    const hasDraft = signal(false)
    service.register(hasDraft, scope.get(DestroyRef))
    expect(unload().defaultPrevented).toBe(false)

    hasDraft.set(true)
    expect(unload().defaultPrevented).toBe(true)

    hasDraft.set(false)
    expect(unload().defaultPrevented).toBe(false)
  })

  it('forgets the draft of a composer once it is destroyed', () => {
    service.register(signal(true), scope.get(DestroyRef))
    expect(service.hasUnsentDraft()).toBe(true)

    endComposers()

    expect(service.hasUnsentDraft()).toBe(false)
    expect(unload().defaultPrevented).toBe(false)
  })
})

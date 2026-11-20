import { signal, WritableSignal } from '@angular/core'
import { ComponentFixture, TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { UserListComponent } from './user-list.component'

describe('UserListComponent server-side filtering', () => {
  let fixture: ComponentFixture<UserListComponent>
  let state: Record<string, unknown> & { reset: jest.Mock }
  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? ''

  beforeEach(() => {
    jest.useFakeTimers()
    state = {
      reset: jest.fn(),
      loadMore: jest.fn(),
      users: signal([]),
      totalCount: signal(0),
      nextCursor: signal(null),
      isLoading: signal(false),
      isLoadingMore: signal(false),
      loadError: signal(false),
      isFiltered: signal(false),
    }
    TestBed.configureTestingModule({
      imports: [UserListComponent],
      providers: [
        { provide: UserAdminStateService, useValue: state },
        { provide: Router, useValue: { navigate: jest.fn() } },
      ],
    })
    fixture = TestBed.createComponent(UserListComponent)
    fixture.detectChanges()
  })

  afterEach(() => jest.useRealTimers())

  it('loads once on init, reloads at once on namespace change, and once per debounced search', () => {
    expect(state.reset).toHaveBeenCalledTimes(1)
    expect(state.reset).toHaveBeenLastCalledWith(null, '')

    fixture.componentRef.setInput('selectedNamespaceId', 'ns-1')
    fixture.detectChanges()
    expect(state.reset).toHaveBeenCalledTimes(2)
    expect(state.reset).toHaveBeenLastCalledWith('ns-1', '')

    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.entity-list__search-input')!
    for (const text of ['a', 'al', 'ali']) {
      input.value = text
      input.dispatchEvent(new Event('input'))
      jest.advanceTimersByTime(100)
    }
    expect(state.reset).toHaveBeenCalledTimes(2)

    jest.advanceTimersByTime(300)
    expect(state.reset).toHaveBeenCalledTimes(3)
    expect(state.reset).toHaveBeenLastCalledWith('ns-1', 'ali')
  })

  describe('states', () => {
    const set = (name: string, value: unknown) => (state[name] as WritableSignal<unknown>).set(value)
    const skeleton = () => (fixture.nativeElement as HTMLElement).querySelector('.entity-list__skeleton-card')

    it('tells a filter without result from an empty list', () => {
      expect(text()).toContain('No users found.')

      set('isFiltered', true)
      fixture.detectChanges()

      expect(text()).toContain('No users match the current filter.')
      expect(text()).not.toContain('No users found.')
    })

    it('leaves the load error to the footer, with a retry, instead of an empty-state message', () => {
      set('isFiltered', true)
      set('loadError', true)
      fixture.detectChanges()

      const root = fixture.nativeElement as HTMLElement
      expect(text()).not.toContain('Could not load users.')
      expect(text()).not.toContain('No users found.')
      expect(text()).not.toContain('No users match the current filter.')
      expect(root.querySelector('.entity-list__empty')?.textContent?.trim()).toBe('')
      expect(root.querySelectorAll('.agentos-list-footer__error')).toHaveLength(1)
      expect(text().match(/Loading failed\./g)).toHaveLength(1)

      const retry = root.querySelector<HTMLButtonElement>('.agentos-list-footer__button')!
      expect(retry.textContent).toContain('Retry')
      retry.click()
      expect(state.loadMore).toHaveBeenCalledTimes(1)
    })

    it('shows the loading state only while there is nothing to show', () => {
      set('isLoading', true)
      fixture.detectChanges()
      expect(skeleton()).not.toBeNull()

      set('users', [{ id: 'u1', email: 'a@x.io', isAdmin: false }])
      fixture.detectChanges()

      expect(skeleton()).toBeNull()
      expect(text()).toContain('a@x.io')
    })
  })
})

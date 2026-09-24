import { ElementRef, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { EMPTY, of, Subject, throwError } from 'rxjs'
import { CaseShellComponent, ShellView } from './case-shell.component'
import { CaseStateService } from '../../services/case-state.service'
import { UserStateService } from '../../services/user-state.service'
import { THEME_PORT } from '../../services/theme.service'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'

describe('CaseShellComponent', () => {
  const NS_ID = 'ns-1'

  const caseWith = (id: string, title?: string): Case => ({ id, namespaceId: NS_ID, title }) as unknown as Case

  let routerMock: { navigate: jest.Mock; events: typeof EMPTY }
  let queryParams$: Subject<Record<string, string>>
  let casesMock: ReturnType<typeof signal<Case[]>>
  let caseStateMock: {
    cases: ReturnType<typeof signal<Case[]>>
    loadCases: jest.Mock
    deleteCase: jest.Mock
    setStarred: jest.Mock
    renameCase: jest.Mock
  }
  let userStateMock: { currentUser: jest.Mock; loadMe: jest.Mock }
  let namespaceControllerMock: { listAllNamespace: jest.Mock }
  let themeMock: { theme: jest.Mock; setTheme: jest.Mock }
  let namespaceStateMock: { selectNamespace: jest.Mock; namespaces$: any; initialized$: any }

  function makeComponent(queryParams: Record<string, string> = {}, cases: Case[] = []): CaseShellComponent {
    queryParams$ = new Subject()
    routerMock = { navigate: jest.fn(), events: EMPTY }
    casesMock = signal(cases)
    caseStateMock = {
      cases: casesMock,
      loadCases: jest.fn(),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      setStarred: jest.fn().mockReturnValue(of(undefined)),
      renameCase: jest.fn().mockReturnValue(of(undefined)),
    }
    userStateMock = {
      currentUser: jest.fn().mockReturnValue(null),
      loadMe: jest.fn().mockReturnValue(EMPTY),
    }
    namespaceControllerMock = {
      listAllNamespace: jest.fn().mockReturnValue(of([])),
    }
    themeMock = {
      theme: jest.fn().mockReturnValue('light'),
      setTheme: jest.fn(),
    }
    namespaceStateMock = {
      selectNamespace: jest.fn(),
      namespaces$: of([]),
      initialized$: of(false),
    }

    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            queryParams: queryParams$.asObservable(),
            snapshot: { params: {} },
          },
        },
        { provide: CaseStateService, useValue: caseStateMock },
        { provide: UserStateService, useValue: userStateMock },
        { provide: NamespaceControllerService, useValue: namespaceControllerMock },
        { provide: THEME_PORT, useValue: themeMock },
        { provide: ElementRef, useValue: { nativeElement: document.createElement('div') } },
        { provide: NamespaceStateService, useValue: namespaceStateMock },
      ],
    })

    const component = TestBed.runInInjectionContext(() => new CaseShellComponent())
    // Emit initial query params to drive activeCaseId / namespaceId signals
    queryParams$.next(queryParams)
    TestBed.flushEffects()
    return component
  }

  afterEach(() => {
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  describe('soft-delete', () => {
    it('calls deleteCase on the state service', () => {
      const component = makeComponent({ ns: NS_ID })

      component['onDeleteRequested']('case-1')

      expect(caseStateMock.deleteCase).toHaveBeenCalledWith('case-1')
    })

    it('navigates away when the deleted case is the active one', () => {
      const component = makeComponent({ ns: NS_ID, case: 'active-1' })

      component['onDeleteRequested']('active-1')

      expect(routerMock.navigate).toHaveBeenCalled()
    })

    it('does not navigate when the deleted case is not the active one', () => {
      const component = makeComponent({ ns: NS_ID, case: 'active-1' })

      component['onDeleteRequested']('other-2')

      expect(routerMock.navigate).not.toHaveBeenCalled()
    })

    it('logs an error when the delete request fails', () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const component = makeComponent({ ns: NS_ID, case: 'active-1' })
      caseStateMock.deleteCase.mockReturnValue(throwError(() => new Error('boom')))

      component['onDeleteRequested']('active-1')

      expect(routerMock.navigate).not.toHaveBeenCalled()
      expect(errorSpy).toHaveBeenCalled()
    })
  })

  describe('star', () => {
    it('calls setStarred(id, true) when starring a case', () => {
      const component = makeComponent({ ns: NS_ID })

      component['onStarToggled']({ id: 'case-1', starred: true })

      expect(caseStateMock.setStarred).toHaveBeenCalledWith('case-1', true)
    })

    it('calls setStarred(id, false) when unstarring a case', () => {
      const component = makeComponent({ ns: NS_ID })

      component['onStarToggled']({ id: 'case-1', starred: false })

      expect(caseStateMock.setStarred).toHaveBeenCalledWith('case-1', false)
    })

    it('alerts the user when the star request fails', () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const alertSpy = jest.spyOn(window, 'alert').mockImplementation(() => undefined)
      const component = makeComponent({ ns: NS_ID })
      caseStateMock.setStarred.mockReturnValue(throwError(() => new Error('boom')))

      component['onStarToggled']({ id: 'case-1', starred: true })

      expect(errorSpy).toHaveBeenCalled()
      expect(alertSpy).toHaveBeenCalled()
    })
  })

  describe('rename', () => {
    it('delegates the rename to the state service', () => {
      const confirmSpy = jest.spyOn(window, 'confirm')
      const component = makeComponent({ ns: NS_ID })

      component['onRenameRequested']({ id: 'case-1', title: 'New name' })

      expect(caseStateMock.renameCase).toHaveBeenCalledWith('case-1', 'New name')
      // The drawer already validated the title, so nothing is confirmed here.
      expect(confirmSpy).not.toHaveBeenCalled()
    })

    it('stays on the active case', () => {
      const component = makeComponent({ ns: NS_ID, case: 'case-1' })

      component['onRenameRequested']({ id: 'case-1', title: 'New name' })

      expect(routerMock.navigate).not.toHaveBeenCalled()
    })

    it('alerts the user when the rename request fails', () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const alertSpy = jest.spyOn(window, 'alert').mockImplementation(() => undefined)
      const component = makeComponent({ ns: NS_ID })
      caseStateMock.renameCase.mockReturnValue(throwError(() => new Error('boom')))

      component['onRenameRequested']({ id: 'case-1', title: 'New name' })

      expect(errorSpy).toHaveBeenCalled()
      expect(alertSpy).toHaveBeenCalled()
    })
  })

  // ── View / tab navigation ──────────────────────────────────────────────────

  describe('activeView', () => {
    it('defaults to cases when ?view is absent', () => {
      const component = makeComponent({ ns: NS_ID })
      expect(component['activeView']()).toBe<ShellView>('cases')
    })

    it('returns factory when ?view=factory', () => {
      const component = makeComponent({ ns: NS_ID, view: 'factory' })
      expect(component['activeView']()).toBe<ShellView>('factory')
    })

    it('returns cases for unknown view values (backward compat)', () => {
      const component = makeComponent({ ns: NS_ID, view: 'unknown-garbage' })
      expect(component['activeView']()).toBe<ShellView>('cases')
    })

    it('is reactive: emitting ?view=factory updates the signal', () => {
      const component = makeComponent({ ns: NS_ID })
      expect(component['activeView']()).toBe('cases')

      queryParams$.next({ ns: NS_ID, view: 'factory' })
      expect(component['activeView']()).toBe('factory')

      queryParams$.next({ ns: NS_ID })
      expect(component['activeView']()).toBe('cases')
    })
  })

  describe('switchView', () => {
    it('navigates to factory with ?view=factory and preserves ?ns', () => {
      const component = makeComponent({ ns: NS_ID })
      routerMock.navigate.mockClear()

      component['switchView']('factory')

      expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos/home'], {
        queryParams: { ns: NS_ID, view: 'factory' },
      })
    })

    it('navigates to cases without a view param (clean URL)', () => {
      const component = makeComponent({ ns: NS_ID, view: 'factory' })
      routerMock.navigate.mockClear()

      component['switchView']('cases')

      expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos/home'], {
        queryParams: expect.not.objectContaining({ view: 'cases' }),
      })
    })

    it('preserves ?ns when switching to factory and ?ns is present', () => {
      const component = makeComponent({ ns: 'special-ns' })
      routerMock.navigate.mockClear()

      component['switchView']('factory')

      const call = routerMock.navigate.mock.calls[0]
      expect(call[1].queryParams.ns).toBe('special-ns')
    })
  })

  describe('onNamespaceSelected', () => {
    it('preserves ?view=factory when switching namespace while in factory view', () => {
      const component = makeComponent({ ns: NS_ID, view: 'factory' })
      routerMock.navigate.mockClear()

      component['onNamespaceSelected']({ id: 'ns-2', name: 'NS2' } as any)

      const call = routerMock.navigate.mock.calls[0]
      expect(call[1].queryParams.view).toBe('factory')
    })

    it('does NOT include ?view when switching namespace from cases view', () => {
      const component = makeComponent({ ns: NS_ID })
      routerMock.navigate.mockClear()

      component['onNamespaceSelected']({ id: 'ns-2', name: 'NS2' } as any)

      const call = routerMock.navigate.mock.calls[0]
      expect(call[1].queryParams['view']).toBeUndefined()
    })
  })
})

import { ElementRef, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { EMPTY, of, Subject, throwError } from 'rxjs'
import { CaseShellComponent } from './case-shell.component'
import { CaseStateService } from '../../services/case-state.service'
import { UserStateService } from '../../services/user-state.service'
import { THEME_PORT } from '../../services/theme.service'

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
  }
  let userStateMock: { currentUser: jest.Mock; loadMe: jest.Mock }
  let namespaceControllerMock: { listAllNamespace: jest.Mock }
  let themeMock: { theme: jest.Mock; setTheme: jest.Mock }

  function makeComponent(queryParams: Record<string, string> = {}, cases: Case[] = []): CaseShellComponent {
    queryParams$ = new Subject()
    routerMock = { navigate: jest.fn(), events: EMPTY }
    casesMock = signal(cases)
    caseStateMock = {
      cases: casesMock,
      loadCases: jest.fn(),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      setStarred: jest.fn().mockReturnValue(of(undefined)),
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
})

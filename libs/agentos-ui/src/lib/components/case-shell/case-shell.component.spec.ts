import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ElementRef, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, CaseRoleEnum, NamespaceControllerService, NamespaceListItemRoleEnum } from '@whoz-oss/agentos-api-client'
import { EMPTY, of, Subject, throwError } from 'rxjs'
import { CaseShellComponent } from './case-shell.component'
import { CaseStateService } from '../../services/case-state.service'
import { GlobalStreamService } from '../../services/global-stream.service'
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
    refreshCases: jest.Mock
    deleteCase: jest.Mock
    setStarred: jest.Mock
    renameCase: jest.Mock
    listMode: ReturnType<typeof signal<'mine' | 'all'>>
    setListMode: jest.Mock
  }
  let userStateMock: { currentUser: jest.Mock; loadMe: jest.Mock }
  let namespaceControllerMock: { listAllNamespace: jest.Mock }
  let caseListUpdates$: Subject<string>
  let connected$: Subject<void>
  let globalStreamMock: { caseListInvalidations$: Subject<string>; connected$: Subject<void> }
  let themeMock: { theme: jest.Mock; setTheme: jest.Mock }

  function makeComponent(
    queryParams: Record<string, string> = {},
    cases: Case[] = [],
    access: {
      isAdmin?: boolean
      namespaceRole?: NamespaceListItemRoleEnum
      namespacesError?: boolean
      adminCanListAllCasesEffective?: boolean
      listMode?: 'mine' | 'all'
    } = {}
  ): CaseShellComponent {
    queryParams$ = new Subject()
    routerMock = { navigate: jest.fn(), events: EMPTY }
    casesMock = signal(cases)
    caseStateMock = {
      cases: casesMock,
      loadCases: jest.fn(),
      refreshCases: jest.fn(),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      setStarred: jest.fn().mockReturnValue(of(undefined)),
      renameCase: jest.fn().mockReturnValue(of(undefined)),
      listMode: signal<'mine' | 'all'>(access.listMode ?? 'mine'),
      setListMode: jest.fn(),
    }
    userStateMock = {
      currentUser: jest.fn().mockReturnValue(access.isAdmin ? { isAdmin: true } : null),
      loadMe: jest.fn().mockReturnValue(EMPTY),
    }
    caseListUpdates$ = new Subject()
    connected$ = new Subject<void>()
    globalStreamMock = { caseListInvalidations$: caseListUpdates$, connected$ }
    namespaceControllerMock = {
      listAllNamespace: jest.fn().mockReturnValue(
        access.namespacesError
          ? throwError(() => new Error('502'))
          : of(
              access.namespaceRole
                ? [
                    {
                      id: NS_ID,
                      name: 'Namespace',
                      role: access.namespaceRole,
                      adminCanListAllCasesEffective: access.adminCanListAllCasesEffective,
                    },
                  ]
                : []
            )
      ),
    }
    themeMock = {
      theme: jest.fn().mockReturnValue('light'),
      setTheme: jest.fn(),
    }

    TestBed.configureTestingModule({
      providers: [
        {
          provide: CaseWorkspaceService,
          useValue: { byRoot: signal({}), watchNamespace: () => EMPTY },
        },
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
        { provide: GlobalStreamService, useValue: globalStreamMock },
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

  it('refreshes the active namespace on its invalidations, and ignores those of the others', () => {
    makeComponent({ ns: NS_ID })

    caseListUpdates$.next(NS_ID)
    caseListUpdates$.next(NS_ID)
    // The aggregated stream carries every namespace the user can read: only ours concerns us.
    caseListUpdates$.next('ns-other')

    expect(caseStateMock.refreshCases).toHaveBeenCalledTimes(2)
    expect(caseStateMock.refreshCases).toHaveBeenNthCalledWith(1, NS_ID)
    expect(caseStateMock.refreshCases).toHaveBeenNthCalledWith(2, NS_ID)
  })

  it('reloads the list on every (re)connection of the stream, which replays nothing', () => {
    makeComponent({ ns: NS_ID })

    connected$.next()
    connected$.next()

    // Whatever was published while the stream was down is gone: reloading is the only recovery.
    expect(caseStateMock.refreshCases).toHaveBeenCalledTimes(2)
    expect(caseStateMock.refreshCases).toHaveBeenCalledWith(NS_ID)
  })

  it('follows the namespace it is switched to, and stops following the previous one', () => {
    makeComponent({ ns: NS_ID })

    queryParams$.next({ ns: 'ns-3' })
    TestBed.flushEffects()
    caseListUpdates$.next(NS_ID)
    caseListUpdates$.next('ns-3')

    expect(caseStateMock.refreshCases).toHaveBeenCalledTimes(1)
    expect(caseStateMock.refreshCases).toHaveBeenCalledWith('ns-3')
  })

  it('opens the sub-case composer for the chosen parent and closes the mobile drawer', () => {
    const component = makeComponent({ ns: NS_ID, case: 'root' })
    component['mobileDrawerOpen'].set(true)

    component['onSubCaseCreateRequested']('root')

    expect(routerMock.navigate).toHaveBeenCalledWith(['/agentos/home'], {
      queryParams: { ns: NS_ID, parentCase: 'root' },
    })
    expect(component['mobileDrawerOpen']()).toBe(false)
  })

  it.each([
    { caseRole: CaseRoleEnum.MEMBER, namespaceRole: NamespaceListItemRoleEnum.MEMBER, expected: false },
    { caseRole: CaseRoleEnum.ADMIN, namespaceRole: NamespaceListItemRoleEnum.MEMBER, expected: true },
    { caseRole: CaseRoleEnum.MEMBER, namespaceRole: NamespaceListItemRoleEnum.ADMIN, expected: true },
    { caseRole: undefined, namespaceRole: NamespaceListItemRoleEnum.SUPER_ADMIN, expected: true },
  ])('derives workspace WRITE access from case and namespace roles: %j', ({ caseRole, namespaceRole, expected }) => {
    const component = makeComponent({ ns: NS_ID, case: 'root' }, [{ ...caseWith('root'), role: caseRole }], {
      namespaceRole,
    })
    expect(component['canWriteActiveCase']()).toBe(expected)
  })

  it('does not throw on read when the namespace list fails to load (would render a blank page)', () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const component = makeComponent({ ns: NS_ID }, [], { namespacesError: true })
    expect(component['namespaces']()).toEqual([])
    expect(component['showNsPicker']()).toBe(false)
  })

  it('keeps workspace actions unavailable without a known permission', () => {
    const component = makeComponent({ ns: NS_ID, case: 'root' })
    expect(component['canWriteActiveCase']()).toBe(false)
  })

  it('allows platform admins to manage workspaces', () => {
    const component = makeComponent({ ns: NS_ID, case: 'root' }, [], { isAdmin: true })
    expect(component['canWriteActiveCase']()).toBe(true)
  })

  describe('Mine / All cases switch', () => {
    const R = NamespaceListItemRoleEnum

    it.each([
      { role: R.ADMIN, enabled: true, expected: true },
      { role: R.SUPER_ADMIN, enabled: true, expected: true },
      { role: R.MEMBER, enabled: true, expected: false },
      { role: R.ADMIN, enabled: false, expected: false },
      { role: R.SUPER_ADMIN, enabled: false, expected: false },
      { role: R.ADMIN, enabled: undefined, expected: false },
    ])('is offered only to an admin of a namespace that enables it: %j', ({ role, enabled, expected }) => {
      const component = makeComponent({ ns: NS_ID }, [], {
        namespaceRole: role,
        adminCanListAllCasesEffective: enabled,
      })

      expect(component['canListAllCases']()).toBe(expected)
    })

    it('is not offered to a platform admin who has no role in the namespace list', () => {
      const component = makeComponent({ ns: NS_ID }, [], { isAdmin: true })

      expect(component['canListAllCases']()).toBe(false)
    })

    it('is not offered before the namespaces have loaded', () => {
      const component = makeComponent({ ns: NS_ID }, [], { namespacesError: true })
      jest.spyOn(console, 'error').mockImplementation(() => undefined)

      expect(component['canListAllCases']()).toBe(false)
    })

    it('forwards a pick to the state service when the switch is offered', () => {
      const component = makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.ADMIN,
        adminCanListAllCasesEffective: true,
      })

      component['onListModeChanged']('all')

      expect(caseStateMock.setListMode).toHaveBeenCalledWith('all')
    })

    it('refuses to switch to all when the switch is not offered', () => {
      const component = makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.MEMBER,
        adminCanListAllCasesEffective: true,
      })

      component['onListModeChanged']('all')

      expect(caseStateMock.setListMode).not.toHaveBeenCalled()
    })

    it('shows mine when the switch is not offered, whatever the state holds', () => {
      const component = makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.MEMBER,
        adminCanListAllCasesEffective: true,
        listMode: 'all',
      })

      expect(component['effectiveListMode']()).toBe('mine')
    })

    it('falls back to mine when the toggle is turned off while all is selected', () => {
      makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.ADMIN,
        adminCanListAllCasesEffective: false,
        listMode: 'all',
      })

      expect(caseStateMock.setListMode).toHaveBeenCalledWith('mine')
    })

    it('keeps all selected while the switch is offered', () => {
      const component = makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.ADMIN,
        adminCanListAllCasesEffective: true,
        listMode: 'all',
      })

      expect(caseStateMock.setListMode).not.toHaveBeenCalled()
      expect(component['effectiveListMode']()).toBe('all')
    })

    it('still refreshes through the state service on a live update (which follows the mode)', () => {
      makeComponent({ ns: NS_ID }, [], {
        namespaceRole: R.ADMIN,
        adminCanListAllCasesEffective: true,
        listMode: 'all',
      })

      caseListUpdates$.next('updated')

      expect(caseStateMock.refreshCases).toHaveBeenCalledWith(NS_ID)
    })
  })

  describe('theme', () => {
    // The mocked port is not a signal: the computed values below are read once, after the mock is set.
    it('is dark for a dark industry theme', () => {
      const component = makeComponent({ ns: NS_ID })
      themeMock.theme.mockReturnValue({ variant: 'industry', mode: 'dark' })

      expect(component['isDark']()).toBe(true)
      expect(component['canToggleMode']()).toBe(true)
    })

    it('is dark for a dark whoz-oss theme and offers the light/dark switch', () => {
      const component = makeComponent({ ns: NS_ID })
      themeMock.theme.mockReturnValue({ variant: 'whoz-oss', mode: 'dark' })

      expect(component['isDark']()).toBe(true)
      expect(component['canToggleMode']()).toBe(true)
    })

    it('switches the mode from the menu under a variant that supports both', () => {
      const component = makeComponent({ ns: NS_ID })
      themeMock.theme.mockReturnValue({ variant: 'industry', mode: 'light' })

      component['onMenuToggleTheme']()

      expect(themeMock.setTheme).toHaveBeenCalledWith({ variant: 'industry', mode: 'dark' })
    })

    it('switches the mode from the menu under whoz-oss, which now has both', () => {
      const component = makeComponent({ ns: NS_ID })
      themeMock.theme.mockReturnValue({ variant: 'whoz-oss', mode: 'light' })

      component['onMenuToggleTheme']()

      expect(themeMock.setTheme).toHaveBeenCalledWith({ variant: 'whoz-oss', mode: 'dark' })
    })

    it('keeps the stored mode when changing variant, and ignores an unknown variant id', () => {
      const component = makeComponent({ ns: NS_ID })
      themeMock.theme.mockReturnValue({ variant: 'industry', mode: 'dark' })

      component['onMenuThemeVariantChanged']('whoz-oss')
      expect(themeMock.setTheme).toHaveBeenCalledWith({ variant: 'whoz-oss', mode: 'dark' })

      themeMock.setTheme.mockClear()
      component['onMenuThemeVariantChanged']('rainbow')
      expect(themeMock.setTheme).not.toHaveBeenCalled()
    })
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
})

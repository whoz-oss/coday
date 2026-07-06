import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { EMPTY, Observable, of, throwError } from 'rxjs'
import { CaseShellComponent } from './case-shell.component'

describe('CaseShellComponent', () => {
  const NS_ID = 'ns-1'

  const caseWith = (id: string, title?: string): Case => ({ id, namespaceId: NS_ID, title }) as unknown as Case

  let routerMock: { url: string; events: Observable<unknown>; navigate: jest.Mock }
  let routeMock: { snapshot: { params: Record<string, string> } }
  let caseControllerMock: {
    listMineByParentCase: jest.Mock
    deleteCase: jest.Mock
    starCase: jest.Mock
    unstarCase: jest.Mock
  }

  function makeComponent(url: string, cases: Case[] = [], listMineMock?: jest.Mock): CaseShellComponent {
    routerMock = { url, events: EMPTY, navigate: jest.fn() }
    routeMock = { snapshot: { params: { namespaceId: NS_ID } } }
    caseControllerMock = {
      listMineByParentCase: listMineMock ?? jest.fn().mockReturnValue(of(cases)),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      starCase: jest.fn().mockReturnValue(of(undefined)),
      unstarCase: jest.fn().mockReturnValue(of(undefined)),
    }

    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: routerMock },
        { provide: ActivatedRoute, useValue: routeMock },
        { provide: CaseControllerService, useValue: caseControllerMock },
      ],
    })

    return TestBed.runInInjectionContext(() => new CaseShellComponent())
  }

  afterEach(() => {
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  describe('soft-delete', () => {
    it('does not delete the case when the user cancels the confirmation', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(false)
      const component = makeComponent(`/agentos/${NS_ID}/cases`)

      component['onDeleteRequested']('case-1')

      expect(caseControllerMock.deleteCase).not.toHaveBeenCalled()
    })

    it('soft-deletes the case and refreshes the list once confirmed', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(true)
      const component = makeComponent(`/agentos/${NS_ID}/cases`)
      // Initial list load happens once at construction.
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(1)

      component['onDeleteRequested']('case-1')

      expect(caseControllerMock.deleteCase).toHaveBeenCalledWith('case-1')
      // The list is refreshed after a successful delete.
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(2)
    })

    it('navigates back to the case section home when the deleted case is the active one', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(true)
      const component = makeComponent(`/agentos/${NS_ID}/cases/active-1`)
      expect(component['activeCaseId']()).toBe('active-1')

      component['onDeleteRequested']('active-1')

      expect(routerMock.navigate).toHaveBeenCalledWith(['.'], { relativeTo: routeMock })
    })

    it('stays on the current view when the deleted case is not the active one', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(true)
      const component = makeComponent(`/agentos/${NS_ID}/cases/active-1`)

      component['onDeleteRequested']('other-2')

      expect(routerMock.navigate).not.toHaveBeenCalled()
    })

    it('confirms with the same label the drawer row shows (the case title)', () => {
      // The drawer renders CaseItemComponent.toListItem(c).name (title ?? id). The confirm
      // must use that same label so the dialog identifies the row the user clicked.
      const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false)
      const component = makeComponent(`/agentos/${NS_ID}/cases`, [caseWith('case-1', 'My Case')])

      component['onDeleteRequested']('case-1')

      expect(confirmSpy).toHaveBeenCalledWith('Delete "My Case"?')
    })

    it('confirms with the case id when the case is not in the current list', () => {
      const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false)
      const component = makeComponent(`/agentos/${NS_ID}/cases`, [])

      component['onDeleteRequested']('gone-9')

      expect(confirmSpy).toHaveBeenCalledWith('Delete "gone-9"?')
    })

    it('logs and alerts the user, and does not navigate or refresh, when the delete request fails', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(true)
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const alertSpy = jest.spyOn(window, 'alert').mockImplementation(() => undefined)
      const component = makeComponent(`/agentos/${NS_ID}/cases/active-1`)
      caseControllerMock.deleteCase.mockReturnValue(throwError(() => new Error('boom')))

      component['onDeleteRequested']('active-1')

      expect(routerMock.navigate).not.toHaveBeenCalled()
      // No refresh: listMineByParentCase stays at its single construction-time call.
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(1)
      expect(errorSpy).toHaveBeenCalled()
      // The failure is surfaced to the user, not just the console.
      expect(alertSpy).toHaveBeenCalled()
    })
  })

  describe('star', () => {
    it('stars a case then refreshes the list', () => {
      const component = makeComponent(`/agentos/${NS_ID}/cases`)
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(1)

      component['onStarToggled']({ id: 'case-1', starred: true })

      expect(caseControllerMock.starCase).toHaveBeenCalledWith('case-1')
      expect(caseControllerMock.unstarCase).not.toHaveBeenCalled()
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(2)
    })

    it('unstars a case when toggled off', () => {
      const component = makeComponent(`/agentos/${NS_ID}/cases`)

      component['onStarToggled']({ id: 'case-1', starred: false })

      expect(caseControllerMock.unstarCase).toHaveBeenCalledWith('case-1')
      expect(caseControllerMock.starCase).not.toHaveBeenCalled()
    })

    it('reverts (reloads) and alerts the user when the star request fails', () => {
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const alertSpy = jest.spyOn(window, 'alert').mockImplementation(() => undefined)
      const component = makeComponent(`/agentos/${NS_ID}/cases`)
      caseControllerMock.starCase.mockReturnValue(throwError(() => new Error('boom')))

      component['onStarToggled']({ id: 'case-1', starred: true })

      expect(errorSpy).toHaveBeenCalled()
      // On failure we reload to revert the drawer's optimistic flip (2nd list call) and alert.
      expect(caseControllerMock.listMineByParentCase).toHaveBeenCalledTimes(2)
      expect(alertSpy).toHaveBeenCalled()
    })
  })

  describe('list resilience', () => {
    it('survives a transient list-load failure and recovers on the next refresh', () => {
      // A single failed load must NOT wedge the list: a later refresh (after delete/star)
      // must still fetch and repopulate the drawer instead of being a silent no-op.
      jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const cases = [caseWith('case-1')]
      const listMine = jest
        .fn()
        .mockReturnValueOnce(throwError(() => new Error('boom')))
        .mockReturnValue(of(cases))
      const component = makeComponent(`/agentos/${NS_ID}/cases`, [], listMine)

      // Construction-time load errored → empty list, but the stream stays alive.
      expect(component['cases']()).toEqual([])

      component['refreshCases']()

      // Recovered: the second load populated the drawer.
      expect(component['cases']()).toEqual(cases)
    })
  })
})

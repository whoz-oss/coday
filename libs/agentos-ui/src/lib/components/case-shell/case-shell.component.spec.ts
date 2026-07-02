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
  let caseControllerMock: { listByParentCase: jest.Mock; deleteCase: jest.Mock }

  function makeComponent(url: string, cases: Case[] = []): CaseShellComponent {
    routerMock = { url, events: EMPTY, navigate: jest.fn() }
    routeMock = { snapshot: { params: { namespaceId: NS_ID } } }
    caseControllerMock = {
      listByParentCase: jest.fn().mockReturnValue(of(cases)),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
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
      expect(caseControllerMock.listByParentCase).toHaveBeenCalledTimes(1)

      component['onDeleteRequested']('case-1')

      expect(caseControllerMock.deleteCase).toHaveBeenCalledWith('case-1')
      // The list is refreshed after a successful delete.
      expect(caseControllerMock.listByParentCase).toHaveBeenCalledTimes(2)
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

    it('names the case in the confirmation prompt when it has a title', () => {
      const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false)
      const component = makeComponent(`/agentos/${NS_ID}/cases`, [caseWith('case-1', 'My Case')])

      component['onDeleteRequested']('case-1')

      expect(confirmSpy).toHaveBeenCalledWith('Delete case "My Case"?')
    })

    it('falls back to a generic prompt when the case has no title', () => {
      const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false)
      const component = makeComponent(`/agentos/${NS_ID}/cases`, [caseWith('case-1')])

      component['onDeleteRequested']('case-1')

      expect(confirmSpy).toHaveBeenCalledWith('Delete this case?')
    })

    it('does not navigate or refresh, and logs, when the delete request fails', () => {
      jest.spyOn(window, 'confirm').mockReturnValue(true)
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      const component = makeComponent(`/agentos/${NS_ID}/cases/active-1`)
      caseControllerMock.deleteCase.mockReturnValue(throwError(() => new Error('boom')))

      component['onDeleteRequested']('active-1')

      expect(routerMock.navigate).not.toHaveBeenCalled()
      // No refresh: listByParentCase stays at its single construction-time call.
      expect(caseControllerMock.listByParentCase).toHaveBeenCalledTimes(1)
      expect(errorSpy).toHaveBeenCalled()
    })
  })
})

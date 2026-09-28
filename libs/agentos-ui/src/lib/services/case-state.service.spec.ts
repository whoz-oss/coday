import { TestBed } from '@angular/core/testing'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { CaseStateService } from './case-state.service'

describe('CaseStateService', () => {
  const caseWith = (id: string, favorite = false): Case => ({ id, namespaceId: 'ns', favorite }) as unknown as Case
  let controllerMock: {
    listMineByParentCase: jest.Mock
    deleteCase: jest.Mock
    starCase: jest.Mock
    unstarCase: jest.Mock
    updateCase: jest.Mock
  }

  function makeService(listMineMock?: jest.Mock): CaseStateService {
    controllerMock = {
      listMineByParentCase: listMineMock ?? jest.fn().mockReturnValue(of([])),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      starCase: jest.fn().mockReturnValue(of(undefined)),
      unstarCase: jest.fn().mockReturnValue(of(undefined)),
      updateCase: jest.fn().mockImplementation((_id: string, body: Case) => of(body)),
    }
    TestBed.configureTestingModule({
      providers: [CaseStateService, { provide: CaseControllerService, useValue: controllerMock }],
    })
    return TestBed.inject(CaseStateService)
  }

  afterEach(() => {
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  it('loads the namespace cases into the signal', () => {
    const svc = makeService(jest.fn().mockReturnValue(of([caseWith('a')])))

    svc.loadCases('ns-1')

    expect(svc.cases()).toEqual([caseWith('a')])
  })

  it('clears the list on a namespace switch so a failed load cannot show the previous namespace', () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const listMine = jest
      .fn()
      .mockReturnValueOnce(of([caseWith('a')])) // ns-1 loads fine
      .mockReturnValueOnce(throwError(() => new Error('boom'))) // ns-2 load fails
    const svc = makeService(listMine)

    svc.loadCases('ns-1')
    expect(svc.cases()).toEqual([caseWith('a')])

    svc.loadCases('ns-2') // switch → list cleared first, then the load errors
    expect(svc.cases()).toEqual([]) // NOT ns-1's cases
  })

  it('keeps the previous list on a same-namespace reload until the new data arrives', () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined)
    const listMine = jest
      .fn()
      .mockReturnValueOnce(of([caseWith('a')]))
      .mockReturnValueOnce(throwError(() => new Error('boom')))
    const svc = makeService(listMine)

    svc.loadCases('ns-1')
    expect(svc.cases()).toEqual([caseWith('a')])

    svc.loadCases('ns-1') // same namespace, reload fails → keep what we had
    expect(svc.cases()).toEqual([caseWith('a')])
  })

  it('deleteCase calls the controller and reloads the current namespace on success', () => {
    const listMine = jest.fn().mockReturnValue(of([caseWith('a')]))
    const svc = makeService(listMine)
    svc.loadCases('ns-1')
    expect(listMine).toHaveBeenCalledTimes(1)

    svc.deleteCase('a').subscribe()

    expect(controllerMock.deleteCase).toHaveBeenCalledWith('a')
    expect(listMine).toHaveBeenCalledTimes(2) // reloaded after the delete
  })

  it('setStarred flips favorite optimistically and keeps it on success', () => {
    const svc = makeService(jest.fn().mockReturnValue(of([caseWith('a', false)])))
    svc.loadCases('ns-1')

    svc.setStarred('a', true).subscribe()

    expect(controllerMock.starCase).toHaveBeenCalledWith('a')
    expect(svc.cases()[0].favorite).toBe(true)
  })

  it('setStarred reverts the optimistic favorite when the request fails', () => {
    const svc = makeService(jest.fn().mockReturnValue(of([caseWith('a', false)])))
    svc.loadCases('ns-1')
    controllerMock.starCase.mockReturnValue(throwError(() => new Error('boom')))

    svc.setStarred('a', true).subscribe({ error: () => undefined })

    expect(svc.cases()[0].favorite).toBe(false) // reverted locally, no reload needed
  })

  it('setStarred uses unstarCase and clears favorite when starred=false', () => {
    const svc = makeService(jest.fn().mockReturnValue(of([caseWith('a', true)])))
    svc.loadCases('ns-1')

    svc.setStarred('a', false).subscribe()

    expect(controllerMock.unstarCase).toHaveBeenCalledWith('a')
    expect(svc.cases()[0].favorite).toBe(false)
  })

  describe('renameCase', () => {
    const titledCase = (id: string, title?: string, extra: Partial<Case> = {}): Case =>
      ({ id, namespaceId: 'ns', favorite: false, title, ...extra }) as unknown as Case

    it('patches the title optimistically and sends the whole resource', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([titledCase('a', 'Old')])))
      svc.loadCases('ns-1')

      svc.renameCase('a', 'New').subscribe()

      // namespaceId is @NotNull on the DTO, so a title-only body would be rejected with a 400.
      expect(controllerMock.updateCase).toHaveBeenCalledWith('a', expect.objectContaining({ namespaceId: 'ns' }))
      expect(controllerMock.updateCase).toHaveBeenCalledWith('a', expect.objectContaining({ title: 'New' }))
      expect(svc.cases()[0].title).toBe('New')
    })

    it('reverts the optimistic title when the request fails', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([titledCase('a', 'Old')])))
      svc.loadCases('ns-1')
      controllerMock.updateCase.mockReturnValue(throwError(() => new Error('boom')))

      svc.renameCase('a', 'New').subscribe({ error: () => undefined })

      expect(svc.cases()[0].title).toBe('Old')
    })

    it('reverts to undefined when the case had no title (the drawer falls back to the id)', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([titledCase('a')])))
      svc.loadCases('ns-1')
      controllerMock.updateCase.mockReturnValue(throwError(() => new Error('boom')))

      svc.renameCase('a', 'New').subscribe({ error: () => undefined })

      expect(svc.cases()[0].title).toBeUndefined()
    })

    it('does not reload the namespace on success (unlike deleteCase)', () => {
      const listMine = jest.fn().mockReturnValue(of([titledCase('a', 'Old')]))
      const svc = makeService(listMine)
      svc.loadCases('ns-1')

      svc.renameCase('a', 'New').subscribe()

      expect(listMine).toHaveBeenCalledTimes(1)
    })

    it('preserves favorite and role while syncing the rename response', () => {
      // Single-case endpoints map a DTO that carries neither favorite nor role: merging the
      // response back would silently un-star the case and drop its ADMIN-gated actions.
      const stored = titledCase('a', 'Old', { favorite: true, role: 'ADMIN' } as Partial<Case>)
      const svc = makeService(jest.fn().mockReturnValue(of([stored])))
      svc.loadCases('ns-1')
      controllerMock.updateCase.mockReturnValue(of(titledCase('a', 'New')))

      svc.renameCase('a', 'New').subscribe()

      expect(svc.cases()[0].favorite).toBe(true)
      expect(svc.cases()[0].role).toBe('ADMIN')
    })

    it('does nothing until subscribed', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([titledCase('a', 'Old')])))
      svc.loadCases('ns-1')

      svc.renameCase('a', 'New')

      expect(controllerMock.updateCase).not.toHaveBeenCalled()
      expect(svc.cases()[0].title).toBe('Old')
    })

    it('errors without calling the controller when the case is not in the list', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([titledCase('a', 'Old')])))
      svc.loadCases('ns-1')
      let failed = false

      svc.renameCase('gone', 'New').subscribe({ error: () => (failed = true) })

      expect(failed).toBe(true)
      expect(controllerMock.updateCase).not.toHaveBeenCalled()
    })
  })

  describe('updateCaseFields', () => {
    const fieldCase = (id: string, extra: Partial<Case> = {}): Case =>
      ({ id, namespaceId: 'ns', favorite: false, runCostThreshold: 10, ...extra }) as unknown as Case

    it('applies the patch optimistically and syncs back the server value on success', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a', { runCostThreshold: 10 })])))
      svc.loadCases('ns-1')
      controllerMock.updateCase.mockReturnValue(of(fieldCase('a', { runCostThreshold: 20 })))

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe()

      expect(svc.cases()[0].runCostThreshold).toBe(20)
    })

    it('reverts the optimistic patch when the request fails', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a', { runCostThreshold: 10 })])))
      svc.loadCases('ns-1')
      controllerMock.updateCase.mockReturnValue(throwError(() => new Error('boom')))

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe({ error: () => undefined })

      expect(svc.cases()[0].runCostThreshold).toBe(10)
    })

    it('waits for the first save to complete before sending the next one', () => {
      const first = new Subject<Case>()
      const second = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first).mockReturnValueOnce(second)

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe()
      svc.updateCaseFields('a', { runCostThreshold: 30 }).subscribe()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(1)
      expect(svc.cases()[0].runCostThreshold).toBe(20)

      first.next(fieldCase('a', { runCostThreshold: 20 }))
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(1)
      first.complete()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(2)
      expect(svc.cases()[0].runCostThreshold).toBe(30)
      second.next(fieldCase('a', { runCostThreshold: 30 }))
      second.complete()
      expect(svc.cases()[0].runCostThreshold).toBe(30)
    })

    it('restores the confirmed value when both queued saves fail and allows retry', () => {
      const first = new Subject<Case>()
      const second = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first).mockReturnValueOnce(second)

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe({ error: () => undefined })
      svc.updateCaseFields('a', { runCostThreshold: 30 }).subscribe({ error: () => undefined })
      first.error(new Error('first rejected'))
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(2)
      expect(svc.cases()[0].runCostThreshold).toBe(30)
      second.error(new Error('second rejected'))
      expect(svc.cases()[0].runCostThreshold).toBe(10)

      svc.updateCaseFields('a', { runCostThreshold: 40 }).subscribe()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(3)
      expect(svc.cases()[0].runCostThreshold).toBe(40)
    })

    it('preserves a successful save when the following save fails', () => {
      const first = new Subject<Case>()
      const second = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first).mockReturnValueOnce(second)

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe()
      svc.updateCaseFields('a', { runCostThreshold: 30 }).subscribe({ error: () => undefined })
      first.next(fieldCase('a', { runCostThreshold: 20 }))
      first.complete()
      second.error(new Error('second rejected'))
      expect(svc.cases()[0].runCostThreshold).toBe(20)
    })

    it('queues renames using the confirmed threshold, independently of other cases', () => {
      const first = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a'), fieldCase('b')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first)

      svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe({ error: () => undefined })
      svc.renameCase('a', 'Renamed').subscribe()
      svc.updateCaseFields('b', { runCostThreshold: 50 }).subscribe()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(2)
      expect(svc.cases()[1].runCostThreshold).toBe(50)

      first.error(new Error('threshold rejected'))
      expect(controllerMock.updateCase).toHaveBeenLastCalledWith(
        'a',
        expect.objectContaining({ title: 'Renamed', runCostThreshold: 10 })
      )
      expect(svc.cases()[0]).toEqual(expect.objectContaining({ title: 'Renamed', runCostThreshold: 10 }))
    })

    it('finishes subscribed saves in order when the first caller unsubscribes', () => {
      const first = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first)

      const subscription = svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe()
      svc.updateCaseFields('a', { runCostThreshold: 30 }).subscribe()
      subscription.unsubscribe()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(1)
      first.next(fieldCase('a', { runCostThreshold: 20 }))
      first.complete()
      expect(controllerMock.updateCase).toHaveBeenCalledTimes(2)
      expect(svc.cases()[0].runCostThreshold).toBe(30)
    })

    it('keeps queued saves after navigation replaces the visible case list', () => {
      const first = new Subject<Case>()
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns')
      controllerMock.updateCase.mockReturnValueOnce(first)

      const subscription = svc.updateCaseFields('a', { runCostThreshold: 20 }).subscribe({ error: () => undefined })
      svc.renameCase('a', 'Renamed').subscribe()
      subscription.unsubscribe()
      controllerMock.listMineByParentCase.mockReturnValue(of([fieldCase('b')]))
      svc.loadCases('other-ns')
      first.error(new Error('threshold rejected'))

      expect(controllerMock.updateCase).toHaveBeenLastCalledWith(
        'a',
        expect.objectContaining({ title: 'Renamed', runCostThreshold: 10 })
      )
      expect(svc.cases()).toEqual([fieldCase('b')])
    })

    it('errors without calling the controller when the case is not in the list', () => {
      const svc = makeService(jest.fn().mockReturnValue(of([fieldCase('a')])))
      svc.loadCases('ns-1')
      let failed = false

      svc.updateCaseFields('gone', { runCostThreshold: 20 }).subscribe({ error: () => (failed = true) })

      expect(failed).toBe(true)
      expect(controllerMock.updateCase).not.toHaveBeenCalled()
    })
  })
})

import { TestBed } from '@angular/core/testing'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { CaseStateService } from './case-state.service'

describe('CaseStateService', () => {
  const caseWith = (id: string, favorite = false): Case => ({ id, namespaceId: 'ns', favorite }) as unknown as Case
  let controllerMock: {
    listMineByParentCase: jest.Mock
    deleteCase: jest.Mock
    starCase: jest.Mock
    unstarCase: jest.Mock
  }

  function makeService(listMineMock?: jest.Mock): CaseStateService {
    controllerMock = {
      listMineByParentCase: listMineMock ?? jest.fn().mockReturnValue(of([])),
      deleteCase: jest.fn().mockReturnValue(of(undefined)),
      starCase: jest.fn().mockReturnValue(of(undefined)),
      unstarCase: jest.fn().mockReturnValue(of(undefined)),
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
})

import { TestBed } from '@angular/core/testing'
import { Case, CaseControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { CaseStateService } from './case-state.service'

describe('CaseStateService', () => {
  const caseWith = (id: string): Case => ({ id, namespaceId: 'ns' }) as unknown as Case
  let controllerMock: { listMineByParentCase: jest.Mock }

  function makeService(listMineMock?: jest.Mock): CaseStateService {
    controllerMock = { listMineByParentCase: listMineMock ?? jest.fn().mockReturnValue(of([])) }
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
})

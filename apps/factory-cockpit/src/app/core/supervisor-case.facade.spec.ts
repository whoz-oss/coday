import { TestBed } from '@angular/core/testing'
import { of, throwError } from 'rxjs'
import { AgentOsApiService } from './agentos-api.service'
import { FactoryRun } from './models'
import { SupervisorCaseFacade } from './supervisor-case.facade'
import { SupervisorCaseStoreService } from './supervisor-case-store.service'

describe('SupervisorCaseFacade', () => {
  const run = { id: 'wf-1', title: 'Run one', namespaceId: 'ns-1', ticket: 'ABC-1' } as FactoryRun
  const agentOs = { createCase: jest.fn(), addMessage: jest.fn() }
  const persistence = { load: jest.fn(), save: jest.fn() }

  beforeEach(() => {
    jest.clearAllMocks()
    TestBed.configureTestingModule({
      providers: [
        SupervisorCaseFacade,
        { provide: AgentOsApiService, useValue: agentOs },
        { provide: SupervisorCaseStoreService, useValue: persistence },
      ],
    })
  })

  it('restores the persisted identity while rebuilding the current environment URL', () => {
    persistence.load.mockReturnValue({ caseId: 'case-1', namespaceId: 'ns-1', createdAt: '2026-01-01' })

    expect(TestBed.inject(SupervisorCaseFacade).restore(run)).toEqual({
      caseId: 'case-1',
      namespaceId: 'ns-1',
      agentOsUrl: '/agentos/home?ns=ns-1&case=case-1',
    })
  })

  it('persists creation before swallowing an initial-message failure', () => {
    agentOs.createCase.mockReturnValue(of({ id: 'case-2' }))
    agentOs.addMessage.mockReturnValue(throwError(() => new Error('unavailable')))
    let result: unknown

    TestBed.inject(SupervisorCaseFacade)
      .open(run)
      .subscribe((value) => (result = value))

    expect(persistence.save).toHaveBeenCalledWith(
      { workflowId: 'wf-1', namespaceId: 'ns-1' },
      expect.objectContaining({ caseId: 'case-2', namespaceId: 'ns-1' })
    )
    expect(result).toEqual(expect.objectContaining({ caseId: 'case-2', namespaceId: 'ns-1' }))
  })
})

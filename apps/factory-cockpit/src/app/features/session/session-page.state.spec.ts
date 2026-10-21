import { TestBed } from '@angular/core/testing'
import { AGENTOS_BASE_URL, FactoryStore } from '../../core/factory.store'
import { SessionPageState } from './session-page.state'

describe('SessionPageState', () => {
  const sessions = new Map<string, any>()
  const store = {
    runs: jest.fn(() => [{ id: 'run-1', namespaceId: 'namespace-1' }]),
    session: jest.fn((id: string) => sessions.get(id)),
    selectSession: jest.fn(),
    clearSessionSelection: jest.fn(),
  }

  beforeEach(() => {
    jest.clearAllMocks()
    sessions.clear()
    TestBed.configureTestingModule({
      providers: [
        SessionPageState,
        { provide: FactoryStore, useValue: store },
        { provide: AGENTOS_BASE_URL, useValue: '' },
      ],
    })
  })

  it('owns selection lifecycle and releases the previous session', () => {
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1', 'namespace-1')
    state.selection.set({ stepId: 'step-1' })
    state.selectSession('run-2', 'namespace-2')

    expect(store.selectSession).toHaveBeenNthCalledWith(1, 'run-1', 'namespace-1')
    expect(store.clearSessionSelection).toHaveBeenCalledWith('run-1')
    expect(store.selectSession).toHaveBeenNthCalledWith(2, 'run-2', 'namespace-2')
    expect(state.selection()).toBeNull()

    state.destroy()
    expect(store.clearSessionSelection).toHaveBeenLastCalledWith('run-2')
  })

  it('keeps attempt event selection scoped to the clicked retry', () => {
    const event = { id: 'event-1' }
    sessions.set('run-1', {
      phase: { stepId: 'step-1' },
      events: [],
      eventsByAttemptId: new Map([['attempt-1', [event]]]),
      eventsByStepId: new Map(),
      attempts: [{ attemptId: 'attempt-1', caseId: 'case-1' }],
    })
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Step', stepId: 'step-1', attemptId: 'attempt-1', caseId: 'case-1' } as any)

    expect(state.selectedEvents()).toEqual([event])
    expect(state.selectedAttempt()?.attemptId).toBe('attempt-1')
    expect(state.selectedAttemptCaseId()).toBe('case-1')
    expect(state.caseLink('case-1')).toContain('ns=namespace-1')
  })
})

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

  it('keeps attempt detail and events scoped to the clicked retry', () => {
    const event = { id: 'event-1' }
    sessions.set('run-1', {
      phase: { stepId: 'current-step', name: 'Old agent phase' },
      phaseByAttemptId: new Map([['attempt-1', { stepId: 'step-1', name: 'Engineer attempt', caseId: 'case-1' }]]),
      events: [],
      eventsByAttemptId: new Map([['attempt-1', [event]]]),
      eventsByStepId: new Map(),
      attempts: [{ attemptId: 'attempt-1', caseId: 'case-1' }],
    })
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Step', stepId: 'step-1', attemptId: 'attempt-1', caseId: 'case-1' } as any)

    expect(state.selectedPhase()).toEqual({ stepId: 'step-1', name: 'Engineer attempt', caseId: 'case-1' })
    expect(state.selectedEvents()).toEqual([event])
    expect(state.selectedAttempt()?.attemptId).toBe('attempt-1')
    expect(state.selectedAttemptCaseId()).toBe('case-1')
    expect(state.caseLink('case-1')).toContain('ns=namespace-1')
  })

  it('shows request detail without leaking the active agent phase or events', () => {
    const launchRequest = {
      name: 'Run title',
      kind: 'demande lancement',
      owner: 'non renseigné',
      sections: [{ label: 'Commentaire initial', body: 'Aucun commentaire initial' }],
    }
    sessions.set('run-1', {
      phase: { stepId: 'agent', name: 'Architect analyses bug' },
      launchRequest,
      phaseByStepId: new Map([['code', { stepId: 'code', name: 'Code workspace' }]]),
      events: [{ id: 'old-agent-event' }],
      eventsByStepId: new Map([
        ['launch request', []],
        ['code', [{ id: 'code-event' }]],
      ]),
      attempts: [],
      lanes: [],
    })
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')

    state.selectBlock({ label: 'launch request', kind: 'launch_request' } as any)
    expect(state.selectedPhase()).toBe(launchRequest)
    expect(state.selectedEvents()).toEqual([])
    expect(state.selectedAttemptCaseId()).toBeUndefined()

    state.selectBlock({ label: 'Code workspace', stepId: 'code' } as any)
    expect(state.selectedPhase()).toEqual({ stepId: 'code', name: 'Code workspace' })
    expect(state.selectedEvents()).toEqual([{ id: 'code-event' }])
  })

  it('preserves an existing attempt selection across refresh and deterministically falls back when it disappears', () => {
    const session: any = {
      phase: { stepId: 'agent' },
      launchRequest: { name: 'Run title', kind: 'demande lancement' },
      phaseByAttemptId: new Map([['attempt-1', { stepId: 'agent', name: 'Attempt 1' }]]),
      events: [],
      eventsByAttemptId: new Map([['attempt-1', [{ id: 'attempt-event' }]]]),
      eventsByStepId: new Map([['launch request', []]]),
      attempts: [{ attemptId: 'attempt-1' }],
      lanes: [
        { request: { label: 'launch request', kind: 'launch_request' }, blocks: [] },
        { blocks: [{ label: 'Attempt 1', stepId: 'agent', attemptId: 'attempt-1' }] },
        { blocks: [{ label: 'Code', stepId: 'code' }] },
      ],
    }
    sessions.set('run-1', session)
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Attempt 1', stepId: 'agent', attemptId: 'attempt-1' } as any)

    state.reconcileSelection()
    expect(state.selection()).toMatchObject({ stepId: 'agent', attemptId: 'attempt-1' })
    expect(state.selectedEvents()).toEqual([{ id: 'attempt-event' }])

    session.lanes[1].blocks = []
    state.reconcileSelection()
    expect(state.selection()).toEqual({ stepId: 'launch request', kind: 'launch_request' })
    expect(state.selectedPhase()).toEqual({ name: 'Run title', kind: 'demande lancement' })
    expect(state.selectedEvents()).toEqual([])

    state.reconcileSelection()
    expect(state.selection()).toEqual({ stepId: 'launch request', kind: 'launch_request' })
  })
})

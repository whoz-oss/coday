import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { of, throwError } from 'rxjs'
import { FactoryStore } from '../../core/factory.store'
import { SessionPageState } from './session-page.state'

describe('SessionPageState', () => {
  const sessions = signal(new Map<string, any>())
  const store = {
    runs: jest.fn(() => [{ id: 'run-1', namespaceId: 'namespace-1' }]),
    session: jest.fn((id: string) => sessions().get(id)),
    selectSession: jest.fn(),
    clearSessionSelection: jest.fn(),
    expandedDefinition: jest.fn(),
  }
  const router = { navigate: jest.fn() }

  beforeEach(() => {
    jest.clearAllMocks()
    sessions.set(new Map())
    store.runs.mockImplementation(() => [{ id: 'run-1', namespaceId: 'namespace-1' }])
    router.navigate.mockResolvedValue(true)
    TestBed.configureTestingModule({
      providers: [
        SessionPageState,
        { provide: FactoryStore, useValue: store },
        { provide: Router, useValue: router },
      ],
    })
  })

  describe('save as reusable workflow', () => {
    const draft = { workflowType: 'architect-custom-flow-derived', version: '1.0.0', steps: [{ id: 'a', name: 'A' }] }
    const expandedSession = (expansion?: { stepIds: string[] }): any => ({
      phase: {},
      lanes: [],
      events: [],
      ...(expansion ? { expansion } : {}),
    })

    it('is offered only for a run that received steps from an Architect continuation', () => {
      sessions.update((current) =>
        new Map(current).set('run-1', expandedSession()).set('run-2', expandedSession({ stepIds: ['implement'] }))
      )
      const state = TestBed.inject(SessionPageState)
      state.selectSession('run-1', 'namespace-1')
      expect(state.canSaveAsWorkflow()).toBe(false)
      state.saveAsReusableWorkflow()
      expect(store.expandedDefinition).not.toHaveBeenCalled()
      expect(router.navigate).not.toHaveBeenCalled()

      state.selectSession('run-2', 'namespace-1')
      expect(state.canSaveAsWorkflow()).toBe(true)
    })

    it('reads the draft of the run and opens the composer with it in the navigation state', () => {
      sessions.update((current) => new Map(current).set('run-1', expandedSession({ stepIds: ['implement'] })))
      store.expandedDefinition.mockReturnValue(of({ workflowId: 'run-1', definition: draft }))
      const state = TestBed.inject(SessionPageState)
      state.selectSession('run-1', 'namespace-1')

      state.saveAsReusableWorkflow()

      expect(store.expandedDefinition).toHaveBeenCalledWith('run-1', 'namespace-1')
      expect(router.navigate).toHaveBeenCalledWith(['/workflows', 'new'], {
        state: { workflowDraft: { workflowId: 'run-1', namespaceId: 'namespace-1', definition: draft } },
      })
      expect(state.saveDefinitionError()).toBeNull()
      expect(state.savingDefinition()).toBe(false)
    })

    it('shows the backend error and hands nothing over when the draft cannot be read', () => {
      sessions.update((current) => new Map(current).set('run-1', expandedSession({ stepIds: ['implement'] })))
      store.expandedDefinition.mockReturnValue(
        throwError(() => ({ code: 'WORKFLOW_NOT_EXPANDED', message: 'No steps were added to this run.', status: 409 }))
      )
      const state = TestBed.inject(SessionPageState)
      state.selectSession('run-1', 'namespace-1')

      state.saveAsReusableWorkflow()

      expect(state.saveDefinitionError()).toBe('No steps were added to this run. (WORKFLOW_NOT_EXPANDED)')
      expect(state.savingDefinition()).toBe(false)
      expect(router.navigate).not.toHaveBeenCalled()
    })

    it('never reads a draft without a known namespace', () => {
      store.runs.mockImplementation(() => [])
      sessions.update((current) => new Map(current).set('run-1', expandedSession({ stepIds: ['implement'] })))
      const state = TestBed.inject(SessionPageState)
      state.selectSession('run-1')

      state.saveAsReusableWorkflow()

      expect(store.expandedDefinition).not.toHaveBeenCalled()
      expect(state.saveDefinitionError()).toContain('namespace')
    })

    it('clears a previous error when another session is selected', () => {
      sessions.update((current) => new Map(current).set('run-1', expandedSession({ stepIds: ['implement'] })))
      store.expandedDefinition.mockReturnValue(throwError(() => ({ code: 'X', message: 'Boom', status: 500 })))
      const state = TestBed.inject(SessionPageState)
      state.selectSession('run-1', 'namespace-1')
      state.saveAsReusableWorkflow()
      expect(state.saveDefinitionError()).toBe('Boom (X)')

      state.selectSession('run-2', 'namespace-1')
      expect(state.saveDefinitionError()).toBeNull()
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
    sessions.update((current) =>
      new Map(current).set('run-1', {
      phase: { stepId: 'current-step', name: 'Old agent phase' },
      phaseByAttemptId: new Map([['attempt-1', { stepId: 'step-1', name: 'Engineer attempt', caseId: 'case-1' }]]),
      events: [],
      eventsByAttemptId: new Map([['attempt-1', [event]]]),
      eventsByStepId: new Map(),
      attempts: [{ attemptId: 'attempt-1', caseId: 'case-1' }],
      })
    )
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Step', stepId: 'step-1', attemptId: 'attempt-1', caseId: 'case-1' } as any)

    expect(state.selectedPhase()).toEqual({ stepId: 'step-1', name: 'Engineer attempt', caseId: 'case-1' })
    expect(state.selectedEvents()).toEqual([event])
    expect(state.selectedAttempt()?.attemptId).toBe('attempt-1')
    expect(state.selectedAttemptCaseId()).toBe('case-1')
    expect(state.caseLink('case-1')).toContain('ns=namespace-1')
  })

  it('keeps a projected non-agent execution scoped to its own evidence', () => {
    const executionEvent = { id: 'execution-event' }
    sessions.update((current) => new Map(current).set('run-1', {
      phase: { stepId: 'current' }, events: [], attempts: [], lanes: [],
      phaseByExecutionId: new Map([['execution-1', { stepId: 'build', name: 'Build execution' }]]),
      eventsByExecutionId: new Map([['execution-1', [executionEvent]]]),
    }))
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Build execution', stepId: 'build', executionId: 'execution-1' } as any)
    expect(state.selectedPhase()).toEqual({ stepId: 'build', name: 'Build execution' })
    expect(state.selectedEvents()).toEqual([executionEvent])
  })

  it('does not preserve a vanished execution merely because its linked attempt remains', () => {
    const session: any = {
      phase: { stepId: 'build' }, events: [], attempts: [{ attemptId: 'attempt-1' }],
      phaseByExecutionId: new Map([['execution-1', { stepId: 'build' }]]),
      eventsByExecutionId: new Map([['execution-1', []]]),
      lanes: [{ blocks: [{ label: 'Execution', stepId: 'build', executionId: 'execution-1', attemptId: 'attempt-1' }] }],
    }
    sessions.update((current) => new Map(current).set('run-1', session))
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock(session.lanes[0].blocks[0])
    session.lanes[0].blocks = [{ label: 'Later attempt', stepId: 'build', attemptId: 'attempt-1' }]
    sessions.update((current) => new Map(current).set('run-1', session))
    state.reconcileSelection()
    expect(state.selection()?.executionId).toBeUndefined()
  })

  it('shows request detail without leaking the active agent phase or events', () => {
    const launchRequest = {
      name: 'Run title',
      kind: 'demande lancement',
      owner: 'non renseigné',
      sections: [{ label: 'Commentaire initial', body: 'Aucun commentaire initial' }],
    }
    sessions.update((current) =>
      new Map(current).set('run-1', {
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
    )
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

  it('retains an inactive routing step selection without inventing a timeline execution', () => {
    sessions.update((current) =>
      new Map(current).set('run-1', {
      phase: { stepId: 'architect' },
      lanes: [],
      events: [],
      routingSteps: [{ id: 'backend', status: 'skipped', activation: 'not_selected', executed: false }],
      phaseByStepId: new Map([['backend', { stepId: 'backend', stepStatus: 'skipped' }]]),
      eventsByStepId: new Map([['backend', []]]),
      })
    )
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectStep('backend')
    state.reconcileSelection()
    expect(state.selection()).toEqual({ stepId: 'backend' })
    expect(state.selectedPhase()?.stepStatus).toBe('skipped')
    expect(state.selectedAttempt()).toBeNull()
    expect(state.selectedEvents()).toEqual([])
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
    sessions.update((current) => new Map(current).set('run-1', session))
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')
    state.selectBlock({ label: 'Attempt 1', stepId: 'agent', attemptId: 'attempt-1' } as any)

    state.reconcileSelection()
    expect(state.selection()).toMatchObject({ stepId: 'agent', attemptId: 'attempt-1' })
    expect(state.selectedEvents()).toEqual([{ id: 'attempt-event' }])

    session.lanes[1].blocks = []
    sessions.update((current) => new Map(current).set('run-1', session))
    state.reconcileSelection()
    expect(state.selection()).toEqual({ stepId: 'launch request', kind: 'launch_request' })
    expect(state.selectedPhase()).toEqual({ name: 'Run title', kind: 'demande lancement' })
    expect(state.selectedEvents()).toEqual([])

    state.reconcileSelection()
    expect(state.selection()).toEqual({ stepId: 'launch request', kind: 'launch_request' })
  })

  it('follows the active step after a live projection update until the user selects a step', () => {
    const initial = {
      phase: { stepId: 'frontend' },
      lanes: [{ blocks: [{ label: 'Frontend', stepId: 'frontend' }] }],
      events: [],
    }
    sessions.update((current) => new Map(current).set('run-1', initial))
    const state = TestBed.inject(SessionPageState)
    state.selectSession('run-1')

    state.selectDefaultStep()
    const frontendSelection = state.selection()
    expect(frontendSelection).toEqual({ stepId: 'frontend' })

    // A fresh SSE projection for the same phase must retain the selection
    // object, so selection-context consumers do not reset their local state.
    sessions.update((current) => new Map(current).set('run-1', { ...initial }))
    state.selectDefaultStep()
    expect(state.selection()).toBe(frontendSelection)

    const transitioned = {
      ...initial,
      phase: { stepId: 'build' },
      lanes: [{ blocks: [{ label: 'Frontend', stepId: 'frontend' }, { label: 'Build', stepId: 'build' }] }],
    }
    // This signal update represents the fresh workflow projection received via SSE.
    sessions.update((current) => new Map(current).set('run-1', transitioned))
    state.selectDefaultStep()
    expect(state.selection()).toEqual({ stepId: 'build' })
    expect(state.selection()).not.toBe(frontendSelection)

    state.selectStep('frontend')
    sessions.update((current) => new Map(current).set('run-1', { ...transitioned, phase: { stepId: 'review' } }))
    state.selectDefaultStep()
    expect(state.selection()).toEqual({ stepId: 'frontend' })
  })
})

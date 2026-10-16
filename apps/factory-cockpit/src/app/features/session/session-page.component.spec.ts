import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { BehaviorSubject, of } from 'rxjs'
import { AGENTOS_BASE_URL, FactoryStore } from '../../core/factory.store'
import { AgentQuestion, FactoryRun, SessionDetail } from '../../core/models'
import { ShellState } from '../../core/shell-state'
import { SessionPageComponent } from './session-page.component'

/**
 * Focused unit tests for `currentNamespaceId()` — the namespace resolution
 * used to build AgentOS deep-link URLs in the session detail panel.
 *
 * The fix (2025): `run.namespaceId` is now the primary source. The previous
 * implementation only looked at `agentQuestions[].case.namespaceId` and
 * `agentQuestionsError.namespaceId`, causing the Case link to open AgentOS
 * without a namespace (wrong context) whenever no agent questions were active.
 */
describe('SessionPageComponent — currentNamespaceId()', () => {
  /** Minimal FactoryRun for tests that need a run with a known namespace. */
  const runWithNs = (namespaceId: string): FactoryRun =>
    ({
      id: 'wf-1',
      namespaceId,
      title: 'Test run',
      project: namespaceId,
      status: 'working',
      costUsd: 0,
      durationSec: 0,
      tokens: 0,
      phases: [],
    }) as FactoryRun

  /** Minimal SessionDetail for tests that need session-level data. */
  const sessionBase = (): SessionDetail =>
    ({
      id: 'wf-1',
      sandbox: 'test',
      goal: 'Test',
      status: 'running',
      executionState: 'running',
      activelyRunning: true,
      startedAt: new Date().toISOString(),
      workflow: 'test',
      costUsd: 0,
      durationSec: 0,
      tokens: 0,
      tokensRead: 0,
      tokensWritten: 0,
      steps: [],
      lanes: [],
      nowSec: 0,
      events: [],
      phase: {
        name: 'test',
        status: 'running',
        stepStatus: 'running',
        durationSec: 0,
        owner: 'agent',
        kind: 'agent',
        attempt: '1/1',
        sections: [],
      },
    }) as SessionDetail

  function makeStore(
    runs: FactoryRun[] = [],
    session?: SessionDetail
  ): {
    session: jest.Mock
    runs: ReturnType<typeof signal<FactoryRun[]>>
    namespaces: ReturnType<typeof signal<Map<string, string>>>
    answeringQuestionId: ReturnType<typeof signal<string | null>>
    questionFeedback: ReturnType<typeof signal<null>>
    selectSession: jest.Mock
    clearSessionSelection: jest.Mock
  } {
    return {
      session: jest.fn().mockReturnValue(session),
      runs: signal(runs),
      namespaces: signal(new Map()),
      answeringQuestionId: signal(null),
      questionFeedback: signal(null),
      selectSession: jest.fn(),
      clearSessionSelection: jest.fn(),
    }
  }

  function makeComponent(runId: string, runs: FactoryRun[], session?: SessionDetail): SessionPageComponent {
    const store = makeStore(runs, session)
    TestBed.configureTestingModule({
      providers: [
        { provide: FactoryStore, useValue: store },
        { provide: AGENTOS_BASE_URL, useValue: '' },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(new Map([['runId', runId]])) },
        },
        {
          provide: ShellState,
          useValue: { crumbs: signal([]) },
        },
      ],
    })
    const component = TestBed.runInInjectionContext(() => new SessionPageComponent())
    return component
  }

  afterEach(() => TestBed.resetTestingModule())

  it('returns run.namespaceId when the run is loaded — primary source', () => {
    const run = runWithNs('ns-uuid-abc')
    const component = makeComponent('wf-1', [run], sessionBase())

    expect(component['currentNamespaceId']()).toBe('ns-uuid-abc')
  })

  it('returns empty string when neither run nor session provides a namespace', () => {
    const component = makeComponent('wf-1', [], undefined)

    expect(component['currentNamespaceId']()).toBe('')
  })

  it('falls back to agentQuestions[].case.namespaceId when run has no namespace', () => {
    const session = sessionBase()
    const question: AgentQuestion = {
      questionEventId: 'q-1',
      caseId: 'case-1',
      attemptId: 'a-1',
      stepId: 'build',
      question: 'Choose',
      questionType: 'SINGLE_CHOICE',
      options: ['A'],
      answered: false,
      case: { id: 'case-1', namespaceId: 'ns-from-question', workflowId: 'wf-1', stepId: 'build' },
    }
    session.agentQuestions = [question]
    // Run without namespaceId (orphan run)
    const runWithoutNs = { ...runWithNs(''), namespaceId: undefined } as FactoryRun
    const component = makeComponent('wf-1', [runWithoutNs], session)

    expect(component['currentNamespaceId']()).toBe('ns-from-question')
  })

  it('falls back to agentQuestionsError.namespaceId when run has no namespace and no questions', () => {
    const session = sessionBase()
    session.agentQuestionsError = {
      code: 'AGENT_QUESTIONS_UNAVAILABLE',
      message: 'unavailable',
      namespaceId: 'ns-from-error',
    }
    const runWithoutNs = { ...runWithNs(''), namespaceId: undefined } as FactoryRun
    const component = makeComponent('wf-1', [runWithoutNs], session)

    expect(component['currentNamespaceId']()).toBe('ns-from-error')
  })

  it('prefers run.namespaceId over agentQuestions namespace when both are present', () => {
    const run = runWithNs('ns-from-run')
    const session = sessionBase()
    const question: AgentQuestion = {
      questionEventId: 'q-1',
      caseId: 'case-1',
      attemptId: 'a-1',
      stepId: 'build',
      question: 'Choose',
      questionType: 'FREE_TEXT',
      answered: false,
      case: { id: 'case-1', namespaceId: 'ns-from-question', workflowId: 'wf-1', stepId: 'build' },
    }
    session.agentQuestions = [question]
    const component = makeComponent('wf-1', [run], session)

    // run.namespaceId must win — it is the canonical source
    expect(component['currentNamespaceId']()).toBe('ns-from-run')
  })

  it('builds a caseLink with the correct namespace from run.namespaceId', () => {
    const run = runWithNs('ns-real-uuid')
    const component = makeComponent('wf-1', [run], sessionBase())

    const link = component['caseLink']('case-xyz')
    expect(link).toBe('/agentos/home?ns=ns-real-uuid&case=case-xyz')
  })

  it('builds a caseLink without ns param when namespace is unknown', () => {
    const runWithoutNs = { ...runWithNs(''), namespaceId: undefined } as FactoryRun
    const component = makeComponent('wf-1', [runWithoutNs], sessionBase())

    const link = component['caseLink']('case-xyz')
    expect(link).toBe('/agentos/home?case=case-xyz')
  })

  it('switches scoped store selection from route A to B and clears on destroy', () => {
    const routeIds = new BehaviorSubject(new Map([['runId', 'wf-a']]))
    const store = makeStore([], undefined)
    TestBed.configureTestingModule({
      providers: [
        { provide: FactoryStore, useValue: store },
        { provide: AGENTOS_BASE_URL, useValue: '' },
        { provide: ActivatedRoute, useValue: { paramMap: routeIds.asObservable() } },
        { provide: ShellState, useValue: { crumbs: signal([]) } },
      ],
    })
    const fixture = TestBed.createComponent(SessionPageComponent)
    fixture.componentRef.setInput('runId', '')
    fixture.detectChanges()
    expect(store.selectSession).toHaveBeenLastCalledWith('wf-a')

    routeIds.next(new Map([['runId', 'wf-b']]))
    fixture.detectChanges()
    expect(store.selectSession).toHaveBeenLastCalledWith('wf-b')

    fixture.destroy()
    expect(store.clearSessionSelection).toHaveBeenCalledWith('wf-b')
  })
})

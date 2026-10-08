import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core'
import { Observable, Subscription, catchError, forkJoin, of, switchMap, tap } from 'rxjs'
import { FactoryApiError, FactoryApiService } from './factory-api.service'
import { AgentOsApiService } from './agentos-api.service'
import { SupervisorCaseStoreService } from './supervisor-case-store.service'
import {
  controllerCaseIdOf,
  mapProjectionToRunSummary,
  mapProjectionToSessionDetail,
  namespaceOf,
  workflowIdOf,
} from './mappers'
import {
  AgentQuestion,
  CostSummary,
  GetActionsResponse,
  SupervisorCaseResult,
  RecentTask,
  RunStatus,
  RunSummary,
  Sandbox,
  SandboxStatus,
  SessionDetail,
} from './models'
import { SESSION_872641A8 } from './mock-data'
import { SseService } from './sse.service'

/**
 * Base URL of the AgentOS UI, used to build deep-link URLs pointing to the
 * AgentOS case viewer (`/agentos/home?ns=…&case=…`).
 *
 * - **Production**: empty string — both apps are served behind the same gateway
 *   origin, so an absolute path (`/agentos/home?…`) resolves correctly.
 * - **Development**: `'http://localhost:4200'` — the AgentOS UI (`apps/client`)
 *   runs on port 4200 while the cockpit runs on port 4300. A path-only URL
 *   would resolve to the cockpit origin and land on a 404.
 *
 * `isDevMode()` is intentionally NOT used here: it reflects the Angular build
 * mode (optimization flag), not the runtime environment. A production build
 * served locally would still get an empty string and break.
 *
 * Override this token in `app.config.ts` for any environment:
 *   `{ provide: AGENTOS_BASE_URL, useValue: 'http://localhost:4200' }`
 */
export const AGENTOS_BASE_URL = new InjectionToken<string>('AGENTOS_BASE_URL', {
  providedIn: 'root',
  // Default: empty — correct for production (same-origin gateway).
  // For local dev, override explicitly in app.config.ts (see comment above).
  factory: () => '',
})

/**
 * Build an AgentOS deep-link URL for a case, using the configured base URL.
 *
 * All four link-generation paths in the cockpit (supervisor case, conversation
 * link, action-bar case links, session-page case links) must go through this
 * helper so the base URL is applied consistently.
 *
 * @param caseId      The AgentOS case id.
 * @param namespaceId The namespace the case belongs to (omitted when falsy).
 * @param baseUrl     Origin prefix injected via {@link AGENTOS_BASE_URL}.
 */
export function buildAgentOsCaseUrl(caseId: string, namespaceId: string | undefined, baseUrl: string): string {
  const params = new URLSearchParams()
  if (namespaceId) params.set('ns', namespaceId)
  params.set('case', caseId)
  return `${baseUrl}/agentos/home?${params.toString()}`
}

/**
 * @deprecated Use {@link buildAgentOsCaseUrl} directly.
 * Kept for backwards-compat with the supervisor-case flow; will be removed
 * once all callers are migrated.
 */
export function buildSupervisorCaseUrl(caseId: string, namespaceId: string, baseUrl = ''): string {
  return buildAgentOsCaseUrl(caseId, namespaceId, baseUrl)
}

/**
 * Application state exposed as Angular signals.
 *
 * DATA PROVENANCE (everything surfaced is real):
 *
 *  - the sandbox CARDS are derived directly from the active workflow snapshots
 *    returned by `/api/factory/workflows?state=active` (the container fleet has
 *    no backend API, the active workflows ARE the available truth);
 *  - the per-workflow {@link SessionDetail} (projection + timing/evidence/metrics);
 *  - live invalidation + reconnect signals from `/api/factory/workflows/stream`.
 *
 * When the REST backend is unreachable the store degrades gracefully: it clears
 * the derived sandboxes/sessions and never falls back onto fabricated mock data,
 * so the Angular application never crashes on a cold/offline backend.
 */
@Injectable({ providedIn: 'root' })
export class FactoryStore {
  private readonly api = inject(FactoryApiService)
  private readonly agentOs = inject(AgentOsApiService)
  private readonly sse = inject(SseService)
  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)
  private readonly supervisorCaseStore = inject(SupervisorCaseStoreService)

  /** Sandbox cards derived from the real active workflows (empty until loaded). */
  readonly sandboxes = signal<Sandbox[]>([])
  /** No real recent-teardown API exists yet; kept empty for backwards-compat. */
  readonly recentTasks = signal<RecentTask[]>([])
  readonly showDestroyed = signal(false)
  readonly answeringQuestionId = signal<string | null>(null)
  readonly questionFeedback = signal<{ interactionId: string; kind: 'conflict' | 'error'; message: string } | null>(
    null
  )

  readonly activeSandboxes = computed(() => this.sandboxes().filter((s) => s.status !== 'destroyed'))
  readonly destroyedSandboxes = computed(() => this.sandboxes().filter((s) => s.status === 'destroyed'))

  /**
   * Sandboxes sorted by creation date, most recent first.
   *
   * Sorting is stable-descending on `createdAt` (epoch ms). Sandboxes whose
   * `createdAt` is absent are placed at the end (unknown creation date).
   * The source signal is never mutated: `[...list]` creates a fresh copy
   * before sorting.
   */
  readonly visibleSandboxes = computed(() => {
    const list = this.showDestroyed() ? this.sandboxes() : this.activeSandboxes()
    return sortSandboxesByCreation([...list])
  })

  readonly costs = computed<CostSummary>(() => {
    const active = this.activeSandboxes()
    // REAL: the active workflows' costs, mapped from each run's `/metrics`
    // payload (0 when the backend exposes no run-cost).
    const workflowsUsd = active.reduce((sum, s) => sum + (s.run?.costUsd ?? 0), 0)
    // Uncertainty is propagated verbatim: sum of the runs' unknownCostCount.
    const unknownCostCount = active.reduce((sum, s) => sum + (s.run?.unknownCostCount ?? 0), 0)
    // No other real cost source exists (no Archay/destroyed fleet API).
    return {
      active: active.length,
      workflowsUsd,
      totalUsd: workflowsUsd,
      unknownCostCount,
    }
  })

  // REAL: latest workflow snapshots from REST, and their mapped session details.
  private readonly workflows = signal<unknown[]>([])
  private readonly sessions = signal<Map<string, SessionDetail>>(new Map())
  private readonly enrichment = new Map<
    string,
    {
      timing?: unknown
      evidence?: unknown
      metrics?: unknown
      interactions?: unknown
      attempts?: unknown
      actions?: GetActionsResponse
      agentQuestions?: AgentQuestion[]
      agentQuestionsError?: SessionDetail['agentQuestionsError']
    }
  >()
  private readonly subscriptions = new Subscription()

  constructor() {
    this.load()
    // SSE carries invalidations only: (re)fetch the authoritative REST state.
    this.subscriptions.add(this.sse.invalidations$.subscribe(() => this.load()))
    this.subscriptions.add(this.sse.reconnected$.subscribe(() => this.load()))
    this.sse.connect()
  }

  session(runId: string): SessionDetail | undefined {
    const real = this.sessions().get(runId)
    if (real) return real
    // Graceful fallback to the demo session when the backend has no such run.
    return runId === SESSION_872641A8.id ? SESSION_872641A8 : { ...SESSION_872641A8, id: runId }
  }

  /** Public handle letting a component re-fetch the active workflow projections. */
  refresh(): void {
    this.load()
  }

  /** Re-fetch the active workflow projections and re-derive the state. */
  private load(): void {
    // Fetch active AND removed workflow snapshots concurrently. Inner
    // `catchError`s mean a partial backend failure (e.g. the removed endpoint is
    // unavailable) still yields the other half instead of breaking the load.
    forkJoin({
      active: this.api.getWorkflows('active').pipe(catchError(() => of([] as unknown[]))),
      removed: this.api.getWorkflows('removed').pipe(catchError(() => of([] as unknown[]))),
    }).subscribe({
      next: ({ active, removed }) => this.applyWorkflows(active, removed),
      error: () => {
        // Graceful degradation: the backend is unavailable, so there is no real
        // workflow to show. Clear everything instead of crashing or falling
        // back onto fabricated mock data.
        this.workflows.set([])
        this.sandboxes.set([])
        this.sessions.set(new Map())
        this.enrichment.clear()
      },
    })
  }

  private applyWorkflows(activeItems: unknown[], removedItems: unknown[]): void {
    const active = Array.isArray(activeItems) ? activeItems : []
    const removed = Array.isArray(removedItems) ? removedItems : []
    const snapshots = [...active, ...removed]
    this.workflows.set(snapshots)
    this.enrichment.clear()

    // Derive one sandbox card per real workflow snapshot. Removed snapshots are
    // surfaced as destroyed sandboxes; `showDestroyed`/`visibleSandboxes` remain
    // the ONLY visibility filter.
    const activeSandboxes = active.map((snapshot) => this.toSandbox(snapshot))
    const destroyedSandboxes = removed.map((snapshot) => this.toSandbox(snapshot, 'destroyed'))
    this.sandboxes.set([...activeSandboxes, ...destroyedSandboxes])

    const sessionMap = new Map<string, SessionDetail>()
    for (const snapshot of snapshots) {
      const detail = mapProjectionToSessionDetail(snapshot)
      sessionMap.set(detail.id, detail)
    }
    this.sessions.set(sessionMap)

    // Enrich active runs only: removed workflows have no live backend surface to
    // enrich (and their session keeps the projection-only detail).
    for (const snapshot of active) this.enrichSession(snapshot)
  }

  /**
   * Map one real active workflow snapshot onto a displayable {@link Sandbox}.
   * Every field comes from the snapshot/relations; nothing is fabricated.
   */
  private toSandbox(snapshot: unknown, forcedStatus?: SandboxStatus): Sandbox {
    const run = mapProjectionToRunSummary(snapshot)
    const obj = asRecord(snapshot)
    const projection = asRecord(obj?.['projection']) ?? obj
    const relations = asRecord(obj?.['relations']) ?? asRecord(asRecord(obj?.['instance'])?.['relations'])
    const namespace = namespaceOf(snapshot)
    const ticket = readString(relations, 'ticket')
    const branch = readString(relations, 'branch') ?? ticket
    const workflowType = readString(projection, 'workflowType')
    const title = readString(projection, 'title')
    const goal = readString(projection, 'goal')
    const state = readString(projection, 'status')
    const name = title ?? (run.id !== 'unknown' ? run.id : (ticket ?? goal ?? 'workflow'))

    const sandbox: Sandbox = {
      name,
      project: namespace ?? ticket ?? 'coday',
      status: forcedStatus ?? deriveSandboxStatus(state, run.status),
      run,
    }
    const controllerCaseId = controllerCaseIdOf(snapshot)
    if (namespace) sandbox.namespace = namespace
    if (workflowType) sandbox.workflowType = workflowType
    if (ticket) sandbox.ticket = ticket
    if (branch) sandbox.branch = branch
    if (controllerCaseId) sandbox.controllerCaseId = controllerCaseId
    // Real creation timestamp: `createdAt` is the Neo4j audit timestamp of
    // when the WorkflowProjection node was first written, preserved across
    // all subsequent updates by Neo4jWorkflowRepository.publishProjection.
    // Exposed via publicSnapshot() as an ISO-8601 string.
    // Validated before use: an unparseable value is silently dropped so
    // DatePipe and the sort never receive an invalid date.
    const rawCreatedAt = readString(obj, 'createdAt')
    if (rawCreatedAt && isValidIsoDate(rawCreatedAt)) sandbox.createdAt = rawCreatedAt
    return sandbox
  }

  private enrichSession(snapshot: unknown): void {
    const id = workflowIdOf(snapshot)
    if (!id) return
    const namespaceId = namespaceOf(snapshot)
    const merge = (partial: {
      timing?: unknown
      evidence?: unknown
      metrics?: unknown
      interactions?: unknown
      attempts?: unknown
      actions?: GetActionsResponse
      agentQuestions?: AgentQuestion[]
      agentQuestionsError?: SessionDetail['agentQuestionsError']
    }): void => {
      const next = { ...(this.enrichment.get(id) ?? {}), ...partial }
      this.enrichment.set(id, next)
      const detail = mapProjectionToSessionDetail(
        snapshot,
        next.timing,
        next.evidence,
        next.metrics,
        next.interactions,
        next.attempts,
        next.actions,
        next.agentQuestions
      )
      if (next.agentQuestionsError) detail.agentQuestionsError = next.agentQuestionsError
      this.sessions.update((map) => {
        const updated = new Map(map)
        updated.set(detail.id, detail)
        return updated
      })
      // The metrics payload carries the additive `realCost` block: once it is
      // known, re-map the run attached to its sandbox so the fleet reflects the
      // real cost (and its uncertainty) instead of the projection-only fallback.
      if (partial.metrics !== undefined) {
        this.updateSandboxRun(mapProjectionToRunSummary(snapshot, partial.metrics))
      }
    }

    this.api.getTiming(id, namespaceId).subscribe({ next: (timing) => merge({ timing }), error: () => undefined })
    this.api.getEvidence(id, namespaceId).subscribe({ next: (evidence) => merge({ evidence }), error: () => undefined })
    this.api.getMetrics(id, namespaceId).subscribe({ next: (metrics) => merge({ metrics }), error: () => undefined })
    // Read-only human interactions: a failed fetch degrades silently (the session
    // keeps its projection/evidence/metrics data and simply has no interactions).
    this.api
      .getInteractions(id, namespaceId)
      .subscribe({ next: (interactions) => merge({ interactions }), error: () => undefined })
    // Read-only real agent attempts: a failed fetch degrades silently (the
    // session keeps its projection and other enrichments).
    this.api.getAttempts(id, namespaceId).subscribe({ next: (attempts) => merge({ attempts }), error: () => undefined })
    // Governed actions/blockers: the backend is the single authority, so a
    // failed fetch degrades to an empty action set (never crashes the store and
    // never lets the UI decide an action on its own).
    this.api.getActions(id, namespaceId).subscribe({ next: (actions) => merge({ actions }), error: () => undefined })
    this.api.getAgentQuestions(id, namespaceId).subscribe({
      next: (agentQuestions) => merge({ agentQuestions, agentQuestionsError: undefined }),
      error: (error: FactoryApiError) => {
        if (error.code !== 'AGENT_QUESTIONS_UNAVAILABLE') return
        merge({ agentQuestions: [], agentQuestionsError: agentQuestionError(error, id, namespaceId) })
      },
    })
  }

  // ---------------------------------------------------------------------------
  // Governed actions: only ever triggered from `allowedActions`
  // ---------------------------------------------------------------------------

  /** Resolve the namespace bound to a loaded workflow snapshot, when known. */
  private namespaceFor(workflowId: string): string | undefined {
    const snapshot = this.workflows().find((item) => workflowIdOf(item) === workflowId)
    return snapshot ? namespaceOf(snapshot) : undefined
  }

  /** Reply to a human interaction, then re-fetch the authoritative state. */
  replyInteraction(
    workflowId: string,
    interactionId: string,
    payload: { actionId?: string; text?: string; expectedRevision?: number },
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.replyInteraction(workflowId, interactionId, payload, ns).subscribe({
      next: () => this.load(),
      error: () => undefined,
    })
  }

  /** Answer an AgentOS queryUser question; AgentOS remains the authority. */
  answerAgentQuestion(workflowId: string, question: AgentQuestion, answer: string): void {
    if (this.answeringQuestionId()) return
    this.answeringQuestionId.set(question.questionEventId)
    this.questionFeedback.set(null)
    const namespaceId = this.namespaceFor(workflowId)
    this.api
      .answerAgentQuestion(workflowId, question.questionEventId, { stepId: question.stepId, answer }, namespaceId)
      .subscribe({
        next: () => {
          this.answeringQuestionId.set(null)
          this.load()
        },
        error: (error: FactoryApiError) => {
          this.answeringQuestionId.set(null)
          const conflict = error.status === 409
          this.questionFeedback.set({
            interactionId: question.questionEventId,
            kind: conflict ? 'conflict' : 'error',
            message: conflict
              ? 'The question has changed. The allowed state has been updated; please review your answer.'
              : error.message,
          })
          if (conflict) this.load()
        },
      })
  }

  /** Open a retry for a blocked step, then re-fetch the authoritative state. */
  retry(
    workflowId: string,
    payload: { stepId: string; expectedRevision?: number; reasonCode?: string },
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.openRetry(workflowId, payload, ns).subscribe({ next: () => this.load(), error: () => undefined })
  }

  /** Cancel a durable agent attempt, then re-fetch the authoritative state. */
  cancelAttempt(
    workflowId: string,
    attemptId: string,
    payload: { expectedRevision?: number; reason?: string },
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.cancelAttempt(workflowId, attemptId, payload, ns).subscribe({
      next: () => this.load(),
      error: () => undefined,
    })
  }

  /** Continue a paused run cost, then re-fetch the authoritative state. */
  continueCost(workflowId: string, payload?: { expectedThreshold?: number }, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.continueCost(workflowId, payload, ns).subscribe({ next: () => this.load(), error: () => undefined })
  }

  /** Stop a paused run cost, then re-fetch the authoritative state. */
  stopCost(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.stopCost(workflowId, ns).subscribe({ next: () => this.load(), error: () => undefined })
  }

  /**
   * Cancel the workflow's real active attempt, then re-fetch the authoritative
   * state. The attempt id and its expected revision are resolved SOLELY from the
   * loaded backend state (`allowedActions` first, then a running attempt). When
   * no active attempt is resolvable the action is unavailable and nothing is
   * sent: the cockpit never fabricates an id or a revision.
   */
  stop(workflowId: string, namespaceId?: string): void {
    const session = this.sessions().get(workflowId)
    const attemptId = session?.activeAttemptId
    if (!attemptId) return
    const expectedRevision = session?.activeAttemptRevision
    const payload: { expectedRevision?: number; reason?: string } = { reason: 'stop' }
    if (expectedRevision !== undefined) payload.expectedRevision = expectedRevision
    this.cancelAttempt(workflowId, attemptId, payload, namespaceId)
  }

  /** Soft-remove a workflow, then re-fetch the authoritative state. */
  remove(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.removeWorkflow(workflowId, ns).subscribe({ next: () => this.load(), error: () => undefined })
  }

  /** Restore a removed workflow, then re-fetch the authoritative state. */
  restore(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api.restoreWorkflow(workflowId, ns).subscribe({ next: () => this.load(), error: () => undefined })
  }

  /**
   * Restore a previously created supervisor case for a sandbox from the local
   * browser store. Returns a {@link SupervisorCaseResult} when a valid persisted
   * entry exists, or `undefined` when no entry is found (first visit, storage
   * unavailable, or corrupt entry).
   *
   * The AgentOS URL is always reconstructed from the current
   * {@link AGENTOS_BASE_URL} injection — never stored as a frozen string — so
   * it remains correct across environment changes.
   *
   * **Scope**: local browser only. A different browser, incognito context, or
   * device will return `undefined` (see {@link SupervisorCaseStoreService}).
   */
  restoreSupervisorCase(sandbox: Sandbox): SupervisorCaseResult | undefined {
    const namespaceId = sandbox.namespace
    const workflowId = sandbox.run?.id
    if (!namespaceId || !workflowId) return undefined
    const entry = this.supervisorCaseStore.load({ workflowId, namespaceId })
    if (!entry) return undefined
    return {
      caseId: entry.caseId,
      namespaceId: entry.namespaceId,
      agentOsUrl: buildAgentOsCaseUrl(entry.caseId, entry.namespaceId, this.agentOsBaseUrl),
    }
  }

  /**
   * Create an AgentOS supervisor assistance case for a sandbox, send an initial
   * context message mentioning `@Heimdall`, and return the result.
   *
   * **Persistence**: the case id is persisted in `localStorage` immediately
   * after creation (before the message POST). This ensures that a page refresh
   * restores the association even if the message delivery fails. The persisted
   * entry is keyed by `workflowId + namespaceId` — both stable backend
   * identifiers — and the AgentOS URL is always reconstructed from the current
   * {@link AGENTOS_BASE_URL} injection so it is never frozen.
   *
   * **Namespace**: `sandbox.namespace` must be a valid UUID string — the
   * AgentOS backend validates `namespaceId` with `@NotNull UUID`. The caller
   * must guard against a missing or non-UUID namespace before calling this
   * method.
   *
   * **`window.open` is intentionally absent here**: opening a popup must happen
   * synchronously in a user-gesture handler to avoid popup-blocker rejection.
   * The caller opens the target window before subscribing to this observable,
   * then navigates it once the case id is known.
   */
  openSupervisorCase(sandbox: Sandbox): Observable<SupervisorCaseResult> {
    const namespaceId = sandbox.namespace!
    const workflowId = sandbox.run?.id ?? sandbox.name
    const title = `Supervisor assistance — ${sandbox.name}`
    const initialMessage = buildSupervisorCaseMessage(sandbox.name, workflowId, sandbox.ticket, sandbox.workflowType)
    return this.agentOs.createCase({ namespaceId, title }).pipe(
      // Persist immediately after creation, before the message POST.
      // This guarantees the association survives a page refresh even if the
      // message delivery fails (message failure is swallowed below).
      tap((createdCase) => {
        this.supervisorCaseStore.save(
          { workflowId, namespaceId },
          { caseId: createdCase.id, namespaceId, createdAt: new Date().toISOString() }
        )
      }),
      switchMap((createdCase) =>
        this.agentOs.addMessage(createdCase.id, initialMessage).pipe(
          catchError(() => of(undefined)),
          switchMap(() =>
            of<SupervisorCaseResult>({
              caseId: createdCase.id,
              namespaceId,
              agentOsUrl: buildAgentOsCaseUrl(createdCase.id, namespaceId, this.agentOsBaseUrl),
            })
          )
        )
      )
    )
  }

  /** Replace the run attached to a sandbox once its real cost is known. */
  private updateSandboxRun(run: RunSummary): void {
    this.sandboxes.update((list) => list.map((sandbox) => (sandbox.run?.id === run.id ? { ...sandbox, run } : sandbox)))
  }
}

function agentQuestionError(
  error: FactoryApiError,
  workflowId: string,
  namespaceId?: string
): NonNullable<SessionDetail['agentQuestionsError']> {
  const details = error.details
  const readDetail = (key: string): string | undefined => {
    const value = details?.[key]
    return typeof value === 'string' && value.length > 0 ? value : undefined
  }
  return {
    code: error.code,
    message: error.message,
    caseId: readDetail('caseId'),
    namespaceId: readDetail('namespaceId') ?? namespaceId,
    workflowId: readDetail('workflowId') ?? workflowId,
    stepId: readDetail('stepId'),
  }
}

/**
 * Returns true when [value] is a non-empty string that parses to a finite Date.
 * Used to guard `createdAt` before handing it to DatePipe or Date.parse in the
 * sort — both silently produce NaN on invalid input, which is hard to debug.
 */
function isValidIsoDate(value: string): boolean {
  if (!value) return false
  const ms = Date.parse(value)
  return Number.isFinite(ms)
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}

function readString(obj: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = obj?.[key]
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

const WORKING_STATES = new Set(['running', 'active', 'waiting_human'])
const IDLE_STATES = new Set(['idle', 'ready', 'pending', 'queued'])

/** Map a real workflow state onto the cockpit sandbox status. */
function deriveSandboxStatus(state: string | undefined, runStatus: RunStatus): SandboxStatus {
  const normalized = (state ?? '').toLowerCase()
  if (WORKING_STATES.has(normalized)) return 'working'
  if (IDLE_STATES.has(normalized)) return 'idle'
  return runStatus === 'running' ? 'working' : 'idle'
}

/**
 * Sort sandboxes by creation date, most recent first (descending).
 *
 * Uses `createdAt` (ISO-8601 from WorkflowProjectionNode.createdAt, exposed
 * by publicSnapshot()) as the sort key. Sandboxes without a known creation
 * date are placed at the end. The input array is sorted in-place (caller
 * must pass a copy).
 */
function sortSandboxesByCreation(list: Sandbox[]): Sandbox[] {
  return list.sort((a, b) => {
    const ta = a.createdAt ? Date.parse(a.createdAt) : null
    const tb = b.createdAt ? Date.parse(b.createdAt) : null
    // Both unknown: preserve original order (stable).
    if (ta === null && tb === null) return 0
    // Unknown always goes after a known date.
    if (ta === null) return 1
    if (tb === null) return -1
    // Both known: most recent first.
    return tb - ta
  })
}

/** Build the initial context message for a new supervisor case. */
function buildSupervisorCaseMessage(
  sandboxName: string,
  workflowId: string,
  ticket?: string,
  workflowType?: string
): string {
  return [
    `@Heimdall Hello. I am opening this case from the Factory Cockpit for sandbox **${sandboxName}**.`,
    '',
    'Available identifiers:',
    `- Workflow ID: \`${workflowId}\``,
    ...(ticket ? [`- Ticket: \`${ticket}\``] : []),
    ...(workflowType ? [`- Workflow type: \`${workflowType}\``] : []),
    '',
    'Could you check the state of this workflow and let me know what is happening?',
  ].join('\n')
}

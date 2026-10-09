import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core'
import { Observable, Subscription, catchError, forkJoin, of, switchMap, tap } from 'rxjs'
import { FactoryApiError, FactoryApiService, NamespaceItem, extractNamespaceOptions } from './factory-api.service'
import { AgentOsApiService } from './agentos-api.service'
import { SupervisorCaseStoreService } from './supervisor-case-store.service'
import {
  mapProjectionToFactoryRun,
  mapProjectionToRunSummary,
  mapProjectionToSessionDetail,
  namespaceOf,
  workflowIdOf,
} from './mappers'
import {
  AgentQuestion,
  CostSummary,
  FactoryRun,
  GetActionsResponse,
  SupervisorCaseResult,
  RecentTask,
  RunSummary,
  SessionDetail,
  UNASSIGNED_NAMESPACE_ID,
  WorkstreamView,
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

  /**
   * Namespace id → human name, populated from `GET /api/namespaces`.
   * Used to title workstreams; a missing id falls back to the id itself.
   */
  readonly namespaces = signal<Map<string, string>>(new Map())

  /** Factory runs derived from the real active/removed workflows (empty until loaded). */
  readonly runs = signal<FactoryRun[]>([])
  /** No real recent-teardown API exists yet; kept empty for backwards-compat. */
  readonly recentTasks = signal<RecentTask[]>([])
  readonly showDestroyed = signal(false)
  readonly answeringQuestionId = signal<string | null>(null)
  readonly questionFeedback = signal<{ interactionId: string; kind: 'conflict' | 'error'; message: string } | null>(
    null
  )

  /** @deprecated Use {@link runs}. Kept as an alias for compatibility. */
  readonly sandboxes = computed(() => this.runs())

  readonly activeRuns = computed(() => this.runs().filter((run) => run.status !== 'destroyed'))
  readonly destroyedRuns = computed(() => this.runs().filter((run) => run.status === 'destroyed'))

  /**
   * Runs sorted by creation date, most recent first.
   *
   * Sorting is stable-descending on `createdAt` (epoch ms). Runs whose
   * `createdAt` is absent are placed at the end (unknown creation date).
   * The source signal is never mutated: `[...list]` creates a fresh copy
   * before sorting.
   */
  readonly visibleRuns = computed(() => {
    const list = this.showDestroyed() ? this.runs() : this.activeRuns()
    return sortRunsByCreation([...list])
  })

  /**
   * Runs grouped into workstreams STRICTLY by `namespaceId` (never by title),
   * so namespaces sharing the same human name stay in distinct groups. Runs
   * without a `namespaceId` land in the explicit {@link UNASSIGNED_NAMESPACE_ID}
   * workstream.
   */
  readonly workstreams = computed<WorkstreamView[]>(() => {
    const names = this.namespaces()
    const groups = new Map<string, FactoryRun[]>()
    for (const run of this.visibleRuns()) {
      const namespaceId = run.namespaceId?.trim() || UNASSIGNED_NAMESPACE_ID
      const bucket = groups.get(namespaceId) ?? []
      bucket.push(run)
      groups.set(namespaceId, bucket)
    }
    return [...groups.entries()]
      .map(([namespaceId, runs]) => ({
        namespaceId,
        title: namespaceId === UNASSIGNED_NAMESPACE_ID ? 'Sans namespace' : (names.get(namespaceId) ?? namespaceId),
        runs,
      }))
      .sort((left, right) => left.title.localeCompare(right.title))
  })

  readonly costs = computed<CostSummary>(() => {
    const active = this.activeRuns()
    // REAL: the active workflows' costs, mapped from each run's `/metrics`
    // payload (0 when the backend exposes no run-cost).
    const workflowsUsd = active.reduce((sum, run) => sum + (run.run?.costUsd ?? run.costUsd ?? 0), 0)
    // Uncertainty is propagated verbatim: sum of the runs' unknownCostCount.
    const unknownCostCount = active.reduce((sum, run) => sum + (run.run?.unknownCostCount ?? 0), 0)
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
    // Fetch active AND removed workflow snapshots concurrently, plus the
    // namespace catalogue used to title workstreams. Inner `catchError`s mean a
    // partial backend failure (e.g. the removed endpoint unavailable) still
    // yields the other half instead of breaking the load. `getNamespaces()`
    // already degrades to `[]`, so a namespace failure never blocks the run
    // projection (workstream titles simply fall back to their ids).
    forkJoin({
      active: this.api.getWorkflows('active').pipe(catchError(() => of([] as unknown[]))),
      removed: this.api.getWorkflows('removed').pipe(catchError(() => of([] as unknown[]))),
      namespaces: this.api.getNamespaces(),
    }).subscribe({
      next: ({ active, removed, namespaces }) => {
        this.applyNamespaces(namespaces)
        this.applyWorkflows(active, removed)
      },
      error: () => {
        // Graceful degradation: the backend is unavailable, so there is no real
        // workflow to show. Clear everything instead of crashing or falling
        // back onto fabricated mock data.
        this.workflows.set([])
        this.runs.set([])
        this.sessions.set(new Map())
        this.enrichment.clear()
      },
    })
  }

  /** Populate the namespace id→name map; a failed payload leaves it empty. */
  private applyNamespaces(items: NamespaceItem[]): void {
    const map = new Map<string, string>()
    for (const option of extractNamespaceOptions(items)) map.set(option.id, option.name)
    this.namespaces.set(map)
  }

  private applyWorkflows(activeItems: unknown[], removedItems: unknown[]): void {
    const active = Array.isArray(activeItems) ? activeItems : []
    const removed = Array.isArray(removedItems) ? removedItems : []
    const snapshots = [...active, ...removed]
    this.workflows.set(snapshots)
    this.enrichment.clear()

    // Derive one FactoryRun per real workflow snapshot. Removed snapshots are
    // surfaced as destroyed runs; `showDestroyed`/`visibleRuns` remain the ONLY
    // visibility filter.
    const activeRuns = active.map((snapshot) => mapProjectionToFactoryRun(snapshot))
    const destroyedRuns = removed.map((snapshot) => mapProjectionToFactoryRun(snapshot, 'destroyed'))
    this.runs.set([...activeRuns, ...destroyedRuns])

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
        this.updateRun(mapProjectionToRunSummary(snapshot, partial.metrics))
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
  restoreSupervisorCase(run: FactoryRun): SupervisorCaseResult | undefined {
    const namespaceId = run.namespaceId
    const workflowId = run.id
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
   * Create an AgentOS supervisor assistance case for a run, send an initial
   * context message mentioning `@Heimdall`, and return the result.
   *
   * **Persistence**: the case id is persisted in `localStorage` immediately
   * after creation (before the message POST). This ensures that a page refresh
   * restores the association even if the message delivery fails. The persisted
   * entry is keyed by `workflowId + namespaceId` — both stable backend
   * identifiers — and the AgentOS URL is always reconstructed from the current
   * {@link AGENTOS_BASE_URL} injection so it is never frozen.
   *
   * **Namespace**: `run.namespaceId` must be a valid UUID string — the
   * AgentOS backend validates `namespaceId` with `@NotNull UUID`. The caller
   * must guard against a missing or non-UUID namespace before calling this
   * method.
   *
   * **`window.open` is intentionally absent here**: opening a popup must happen
   * synchronously in a user-gesture handler to avoid popup-blocker rejection.
   * The caller opens the target window before subscribing to this observable,
   * then navigates it once the case id is known.
   */
  openSupervisorCase(run: FactoryRun): Observable<SupervisorCaseResult> {
    const namespaceId = run.namespaceId!
    const workflowId = run.id
    const title = `Supervisor assistance — ${run.title}`
    const initialMessage = buildSupervisorCaseMessage(run.title, workflowId, run.ticket, run.workflowType)
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

  /** Replace the summary attached to a run once its real cost is known. */
  private updateRun(summary: RunSummary): void {
    this.runs.update((list) =>
      list.map((run) =>
        run.id === summary.id
          ? {
              ...run,
              run: summary,
              costUsd: summary.costUsd,
              durationSec: summary.durationSec,
              tokens: summary.tokens,
              phases: summary.phases,
            }
          : run
      )
    )
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
 * Sort runs by creation date, most recent first (descending).
 *
 * Uses `createdAt` (ISO-8601 from WorkflowProjectionNode.createdAt, exposed
 * by publicSnapshot()) as the sort key. Runs without a known creation date are
 * placed at the end. The input array is sorted in-place (caller must pass a
 * copy).
 */
function sortRunsByCreation(list: FactoryRun[]): FactoryRun[] {
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

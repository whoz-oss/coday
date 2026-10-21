import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core'
import { Observable, Subscription, catchError, finalize, forkJoin, of, switchMap, tap } from 'rxjs'
import { FactoryApiError, FactoryApiService, NamespaceItem, extractNamespaceOptions } from './factory-api.service'
import { SupervisorCaseFacade } from './supervisor-case.facade'
import {
  mapProjectionToFactoryRun,
  mapProjectionToSessionDetail,
  mapSessionDetailToRunSummary,
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
  private readonly sse = inject(SseService)
  private readonly supervisorCases = inject(SupervisorCaseFacade)

  /**
   * Namespace id → human name, populated from `GET /api/agentos/api/namespaces`.
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
    // Best-known real costs from the same bounded enrichment used by cards and detail.
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
  private overviewRequest: Subscription | null = null
  private overviewRefreshPending = false
  private detailRequest: Subscription | null = null
  private detailRefreshPending = false
  private selectedWorkflowId: string | null = null
  private selectedNamespaceId: string | null = null
  /** One coalesced enrichment request per overview run; avoids duplicate N+1 bursts. */
  private readonly overviewEnrichmentRequests = new Map<string, Subscription>()
  private readonly overviewEnrichmentPending = new Set<string>()

  constructor() {
    this.load()
    // SSE carries invalidations only. Refresh the overview, and only refresh
    // expensive detail data when the invalidation targets the visible run.
    this.subscriptions.add(
      this.sse.invalidations$.subscribe((invalidation) => {
        this.load()
        if (invalidation.workflowId) this.queueOverviewEnrichment(invalidation.workflowId)
        else this.queueAllOverviewEnrichment()
        if (!invalidation.workflowId || invalidation.workflowId === this.selectedWorkflowId) {
          this.refreshSelectedSession()
        }
      })
    )
    this.subscriptions.add(
      this.sse.reconnected$.subscribe(() => {
        this.load()
        this.queueAllOverviewEnrichment()
        this.refreshSelectedSession()
      })
    )
    this.sse.connect()
  }

  session(runId: string): SessionDetail | undefined {
    const real = this.sessions().get(runId)
    if (real) return real
    // Keep the intentional built-in demo only; never fabricate an arbitrary
    // real run when a scoped fetch has not found it.
    return runId === SESSION_872641A8.id ? SESSION_872641A8 : undefined
  }

  /** Select the only workflow whose expensive detail endpoints may be loaded. */
  selectSession(workflowId: string, namespaceId?: string): void {
    const id = workflowId.trim()
    const namespace = namespaceId?.trim() || null
    if (!id) {
      this.clearSessionSelection()
      return
    }
    if (id === this.selectedWorkflowId) {
      if (namespace && namespace !== this.selectedNamespaceId) {
        this.selectedNamespaceId = namespace
        this.cancelDetailRequest()
        this.startDetailRequest(id)
        return
      }
      this.queueDetailRefresh(id)
      return
    }
    this.selectedWorkflowId = id
    this.selectedNamespaceId = namespace
    this.cancelDetailRequest()
    this.startDetailRequest(id)
  }

  /** Leave the detail view and cancel its in-flight HTTP requests. */
  clearSessionSelection(workflowId?: string): void {
    if (workflowId && workflowId !== this.selectedWorkflowId) return
    this.selectedWorkflowId = null
    this.selectedNamespaceId = null
    this.cancelDetailRequest()
  }

  /** Public refresh of projections plus the currently visible detail. */
  refresh(): void {
    this.load()
    this.queueAllOverviewEnrichment()
    this.refreshSelectedSession()
  }

  /** Coalesce overview invalidations, retaining one trailing refresh while active. */
  private load(): void {
    if (this.overviewRequest) {
      this.overviewRefreshPending = true
      return
    }
    this.overviewRequest = this.loadOverview()
      .pipe(
        finalize(() => {
          this.overviewRequest = null
          if (this.overviewRefreshPending) {
            this.overviewRefreshPending = false
            this.load()
          }
        })
      )
      .subscribe()
  }

  /** Re-fetch projections without aborting an active same-scope request. */
  private loadOverview(): Observable<void> {
    return forkJoin({
      active: this.api.getWorkflows('active').pipe(catchError(() => of([] as unknown[]))),
      removed: this.api.getWorkflows('removed').pipe(catchError(() => of([] as unknown[]))),
      namespaces: this.api.getNamespaces().pipe(catchError(() => of([] as NamespaceItem[]))),
    }).pipe(
      tap(({ active, removed, namespaces }) => {
        this.applyNamespaces(namespaces)
        this.applyWorkflows(active, removed)
      }),
      switchMap(() => of(undefined))
    )
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
    // Preserve a selected snapshot only when it is newer than the overview
    // response. Otherwise accepting the fresh list is required for terminal
    // SSE transitions (running → failed/completed) to reach both card/detail.
    const selectedId = this.selectedWorkflowId
    const candidateSelected = selectedId ? this.workflowSnapshot(selectedId) : undefined
    const incomingSelected = selectedId ? snapshots.find((item) => workflowIdOf(item) === selectedId) : undefined
    const selectedSnapshot =
      candidateSelected && snapshotRevision(candidateSelected) > snapshotRevision(incomingSelected)
        ? candidateSelected
        : undefined
    const effectiveSnapshots = selectedSnapshot
      ? snapshots.some((item) => workflowIdOf(item) === selectedId)
        ? snapshots.map((item) => (workflowIdOf(item) === selectedId ? selectedSnapshot : item))
        : [...snapshots, selectedSnapshot]
      : snapshots
    const effectiveActive = selectedSnapshot
      ? active.some((item) => workflowIdOf(item) === selectedId)
        ? active.map((item) => (workflowIdOf(item) === selectedId ? selectedSnapshot : item))
        : removed.some((item) => workflowIdOf(item) === selectedId)
          ? active
          : [...active, selectedSnapshot]
      : active
    const effectiveRemoved = selectedSnapshot
      ? removed.map((item) => (workflowIdOf(item) === selectedId ? selectedSnapshot : item))
      : removed
    this.workflows.set(effectiveSnapshots)

    // Targeted enrichment purge: only evict entries for workflows that no
    // longer appear in the combined snapshot set. This preserves enrichment
    // (timing, evidence, attempts…) for workflows that survived the refresh,
    // so the selection and detail panel remain stable after an SSE reload.
    const liveIds = new Set(effectiveSnapshots.map((s) => workflowIdOf(s)).filter((id): id is string => !!id))
    for (const id of [...this.enrichment.keys()]) {
      if (!liveIds.has(id)) this.enrichment.delete(id)
    }
    // Also abort in-flight enrichment of vanished workflows: a late response would
    // otherwise merge back into `enrichment`/`sessions` and resurrect a purged run.
    // Map entries are dropped BEFORE unsubscribing so the finalize hook is a no-op.
    for (const [id, request] of [...this.overviewEnrichmentRequests]) {
      if (liveIds.has(id)) continue
      this.overviewEnrichmentRequests.delete(id)
      this.overviewEnrichmentPending.delete(id)
      if (this.detailRequest === request) this.detailRequest = null
      request.unsubscribe()
    }

    // Derive one FactoryRun per real workflow snapshot. Removed snapshots are
    // surfaced as destroyed runs; `showDestroyed`/`visibleRuns` remain the ONLY
    // visibility filter.
    const activeRuns = effectiveActive.map((snapshot) => mapProjectionToFactoryRun(snapshot))
    const destroyedRuns = effectiveRemoved.map((snapshot) => mapProjectionToFactoryRun(snapshot, 'destroyed'))
    this.runs.set([...activeRuns, ...destroyedRuns])

    // Build the session map by merging fresh projection data with any already-
    // loaded enrichment for each workflow (preserves stable detail on refresh).
    const sessionMap = new Map<string, SessionDetail>()
    for (const snapshot of effectiveSnapshots) {
      const id = workflowIdOf(snapshot)
      const existing = id ? this.enrichment.get(id) : undefined
      const detail = mapProjectionToSessionDetail(
        snapshot,
        existing?.timing,
        existing?.evidence,
        existing?.metrics,
        existing?.interactions,
        existing?.attempts,
        existing?.actions,
        existing?.agentQuestions
      )
      if (existing?.agentQuestionsError) detail.agentQuestionsError = existing.agentQuestionsError
      sessionMap.set(detail.id, detail)
    }
    this.sessions.set(sessionMap)

    // Cards and details share the same enrichment path. A run is enriched on
    // first sight only: later refreshes are driven by the targeted triggers
    // (SSE invalidation, reconnect, refresh(), governed actions). Re-enriching
    // every run on each reload would turn each SSE burst into an N+1 storm and
    // duplicate the requests those triggers already queued.
    for (const snapshot of effectiveActive) {
      const id = workflowIdOf(snapshot)
      if (!id || id === this.selectedWorkflowId) continue
      if (this.enrichment.has(id) || this.overviewEnrichmentRequests.has(id)) continue
      this.queueOverviewEnrichment(id)
    }

    // If a deep link selected a run before this
    // initial list arrived, start its detail load now.
    if (
      this.selectedWorkflowId &&
      effectiveActive.some((snapshot) => workflowIdOf(snapshot) === this.selectedWorkflowId) &&
      !this.enrichment.has(this.selectedWorkflowId)
    ) {
      this.refreshSelectedSession()
    }
  }

  private workflowSnapshot(workflowId: string): unknown | undefined {
    return this.workflows().find((item) => workflowIdOf(item) === workflowId)
  }

  private refreshSelectedSession(): void {
    if (this.selectedWorkflowId) this.queueDetailRefresh(this.selectedWorkflowId)
  }

  private queueDetailRefresh(workflowId: string): void {
    if (workflowId !== this.selectedWorkflowId) return
    if (this.detailRequest) {
      this.detailRefreshPending = true
      return
    }
    this.startDetailRequest(workflowId)
  }

  private startDetailRequest(workflowId: string): void {
    if (workflowId !== this.selectedWorkflowId) return
    // The overview enrichment of this run is already in flight and fetches the
    // very same endpoints: adopt it as the detail request instead of issuing a
    // duplicate set. Cancelling the detail then cancels it (see
    // queueOverviewEnrichment's finalize for the trailing-refresh hand-over).
    const inFlight = this.overviewEnrichmentRequests.get(workflowId)
    if (inFlight) {
      this.detailRequest = inFlight
      return
    }
    const snapshot = this.workflowSnapshot(workflowId)
    const namespaceId = snapshot ? namespaceOf(snapshot) : this.selectedNamespaceId
    // A copied legacy URL may not carry namespaceId. In that case wait for the
    // authorized overview to resolve the workflow; never issue an unscoped detail GET.
    if (!snapshot && !namespaceId) return
    let request: Subscription | null = null
    const detailLoad = snapshot
      ? this.enrichSession(snapshot)
      : this.loadSelectedWorkflow(workflowId, namespaceId as string)
    request = detailLoad
      .pipe(
        finalize(() => {
          if (this.detailRequest !== request) return
          this.detailRequest = null
          if (this.detailRefreshPending) {
            this.detailRefreshPending = false
            const selectedId = this.selectedWorkflowId
            if (selectedId === workflowId) this.startDetailRequest(selectedId)
          }
        })
      )
      .subscribe()
    this.detailRequest = request
  }

  private cancelDetailRequest(): void {
    this.detailRefreshPending = false
    const request = this.detailRequest
    request?.unsubscribe()
    this.detailRequest = null
  }

  private refreshAfterAction(workflowId: string): void {
    this.load()
    if (workflowId === this.selectedWorkflowId) this.queueDetailRefresh(workflowId)
    else this.queueOverviewEnrichment(workflowId)
  }

  private loadSelectedWorkflow(workflowId: string, namespaceId: string): Observable<void> {
    return this.api.getWorkflow(workflowId, namespaceId).pipe(
      switchMap((snapshot) => {
        if (workflowIdOf(snapshot) !== workflowId) return of(undefined)
        const current = this.workflows()
        if (!current.some((item) => workflowIdOf(item) === workflowId)) {
          this.workflows.set([...current, snapshot])
          this.runs.update((runs) => [...runs, mapProjectionToFactoryRun(snapshot)])
          const detail = mapProjectionToSessionDetail(snapshot)
          this.sessions.update((sessions) => new Map(sessions).set(detail.id, detail))
        }
        return this.enrichSession(snapshot)
      }),
      catchError(() => of(undefined))
    )
  }

  private enrichSession(snapshot: unknown): Observable<void> {
    const id = workflowIdOf(snapshot)
    if (!id) return of(undefined)
    const namespaceId = namespaceOf(snapshot)
    // The snapshot may be refreshed while the requests are in flight (an SSE
    // reload lands before they complete): always merge onto the latest one so
    // an obsolete snapshot can never overwrite a newer card/detail state.
    const latestSnapshot = (): unknown => this.workflowSnapshot(id) ?? snapshot
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
      const current = latestSnapshot()
      const detail = mapProjectionToSessionDetail(
        current,
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
      // Cards consume exactly the same enriched detail as the detail page.
      this.updateRun(mapSessionDetailToRunSummary(current, detail))
    }

    const optional = <T>(request: Observable<T>): Observable<T | undefined> =>
      request.pipe(catchError(() => of(undefined)))

    return forkJoin({
      timing: optional(this.api.getTiming(id, namespaceId)),
      evidence: optional(this.api.getEvidence(id, namespaceId)),
      metrics: optional(this.api.getMetrics(id, namespaceId)),
      interactions: optional(this.api.getInteractions(id, namespaceId)),
      attempts: optional(this.api.getAttempts(id, namespaceId)),
      actions: optional(this.api.getActions(id, namespaceId)),
      agentQuestions: this.api.getAgentQuestions(id, namespaceId).pipe(
        catchError((error: FactoryApiError) => {
          if (error.code === 'AGENT_QUESTIONS_UNAVAILABLE') {
            merge({ agentQuestions: [], agentQuestionsError: agentQuestionError(error, id, namespaceId) })
          }
          return of(undefined)
        })
      ),
    }).pipe(
      tap((result) => {
        // One atomic merge prevents partial/late response combinations and the
        // outer switchMap guarantees an obsolete A request cannot overwrite B.
        const partial = Object.fromEntries(
          Object.entries(result).filter(([, value]) => value !== undefined)
        ) as Parameters<typeof merge>[0]
        merge(partial)
      }),
      switchMap(() => of(undefined))
    )
  }

  private queueAllOverviewEnrichment(): void {
    for (const snapshot of this.workflows()) {
      const id = workflowIdOf(snapshot)
      if (id) this.queueOverviewEnrichment(id)
    }
  }

  private queueOverviewEnrichment(workflowId: string): void {
    if (workflowId === this.selectedWorkflowId) return
    if (this.overviewEnrichmentRequests.has(workflowId)) {
      this.overviewEnrichmentPending.add(workflowId)
      return
    }
    const snapshot = this.workflowSnapshot(workflowId)
    if (!snapshot || this.runs().find((run) => run.id === workflowId)?.status === 'destroyed') return
    let request: Subscription | null = null
    request = this.enrichSession(snapshot)
      .pipe(
        finalize(() => {
          if (this.overviewEnrichmentRequests.get(workflowId) !== request) return
          this.overviewEnrichmentRequests.delete(workflowId)
          // The selected run adopts its in-flight overview request as its detail
          // request: release it and hand any trailing refresh over to the detail path.
          const adopted = this.detailRequest === request
          if (adopted) this.detailRequest = null
          const trailing = this.overviewEnrichmentPending.delete(workflowId)
          if (workflowId === this.selectedWorkflowId) {
            if (adopted && (trailing || this.detailRefreshPending)) {
              this.detailRefreshPending = false
              this.startDetailRequest(workflowId)
            }
          } else if (trailing) {
            this.queueOverviewEnrichment(workflowId)
          }
        })
      )
      .subscribe()
    this.overviewEnrichmentRequests.set(workflowId, request)
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
      next: () => this.refreshAfterAction(workflowId),
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
          this.refreshAfterAction(workflowId)
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
          if (conflict) this.refreshAfterAction(workflowId)
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
    this.api
      .openRetry(workflowId, payload, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
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
      next: () => this.refreshAfterAction(workflowId),
      error: () => undefined,
    })
  }

  /** Continue a paused run cost, then re-fetch the authoritative state. */
  continueCost(workflowId: string, payload?: { expectedThreshold?: number }, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api
      .continueCost(workflowId, payload, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
  }

  /** Stop a paused run cost, then re-fetch the authoritative state. */
  stopCost(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api
      .stopCost(workflowId, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
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
    this.api
      .removeWorkflow(workflowId, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
  }

  /** Restore a removed workflow, then re-fetch the authoritative state. */
  restore(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    this.api
      .restoreWorkflow(workflowId, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
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
    return this.supervisorCases.restore(run)
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
    return this.supervisorCases.open(run)
  }

  /** Replace the summary attached to a run once its real cost is known. */
  private updateRun(summary: RunSummary): void {
    this.runs.update((list) =>
      list.map((run) =>
        run.id === summary.id
          ? {
              ...run,
              status:
                run.status === 'destroyed'
                  ? 'destroyed'
                  : summary.cardState === 'failed'
                    ? 'failed'
                    : summary.cardState === 'blocked'
                      ? 'blocked'
                      : summary.cardState === 'waiting_human'
                        ? 'waiting_human'
                        : summary.cardState === 'running'
                          ? 'working'
                          : 'idle',
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

function snapshotRevision(snapshot: unknown): number {
  if (typeof snapshot !== 'object' || snapshot === null) return -1
  const value = (snapshot as Record<string, unknown>)['revision']
  return typeof value === 'number' && Number.isFinite(value) ? value : -1
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

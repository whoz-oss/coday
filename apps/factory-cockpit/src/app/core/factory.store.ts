import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core'
import {
  EMPTY,
  Observable,
  Subscription,
  catchError,
  finalize,
  forkJoin,
  of,
  switchMap,
  tap,
  from,
  mergeMap,
  toArray,
  timer,
  map,
} from 'rxjs'
import {
  FactoryApiError,
  FactoryApiService,
  MAX_CUMULATIVE_USAGE_CASE_IDS,
  CumulativeUsage,
  NamespaceItem,
  extractNamespaceOptions,
} from './factory-api.service'
import { SupervisorCaseFacade } from './supervisor-case.facade'
import {
  extractAttempts,
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
  ParentRunDetail,
  SupervisorCaseResult,
  RecentTask,
  RunSummary,
  SessionDetail,
  StopFeedback,
  UNASSIGNED_NAMESPACE_ID,
  WorkstreamView,
} from './models'
import { SESSION_872641A8 } from './mock-data'
import { SseService } from './sse.service'
import { StepFeedback, stepCommandFeedback } from './step-command-feedback'

/**
 * Build a relative AgentOS deep-link URL (`/agentos/home?ns=…&case=…`) for a case.
 *
 * All four link-generation paths in the cockpit (supervisor case, conversation
 * link, action-bar case links, session-page case links) must go through this
 * helper so links are built consistently.
 *
 * @param caseId      The AgentOS case id.
 * @param namespaceId The namespace the case belongs to (omitted when falsy).
 */
export function buildAgentOsCaseUrl(caseId: string, namespaceId: string | undefined): string {
  const params = new URLSearchParams()
  if (namespaceId) params.set('ns', namespaceId)
  params.set('case', caseId)
  return `/agentos/home?${params.toString()}`
}

/**
 * @deprecated Use {@link buildAgentOsCaseUrl} directly.
 * Kept for backwards-compat with the supervisor-case flow; will be removed
 * once all callers are migrated.
 */
export function buildSupervisorCaseUrl(caseId: string, namespaceId: string): string {
  return buildAgentOsCaseUrl(caseId, namespaceId)
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
export class FactoryStore implements OnDestroy {
  private readonly api = inject(FactoryApiService)
  private readonly sse = inject(SseService)
  private readonly supervisorCases = inject(SupervisorCaseFacade)

  /**
   * Namespace id → human name, populated from `GET /api/agentos/api/namespaces`.
   * Used to title workstreams; a missing id falls back to the id itself.
   */
  readonly namespaces = signal<Map<string, string>>(new Map())
  readonly namespaceOptions = signal<NamespaceItem[]>([])
  readonly selectedNamespaceId = signal<string | null>(null)
  readonly namespaceOptionsLoading = signal(true)
  readonly namespaceOptionsError = signal<FactoryApiError | null>(null)
  readonly workstreamMappingMissing = signal(false)
  readonly overviewError = signal<FactoryApiError | null>(null)
  readonly namespaceSelectionRequired = computed(() => false)

  /** Factory runs derived from the real active/removed workflows (empty until loaded). */
  readonly runs = signal<FactoryRun[]>([])
  /** Loaded parent Runs (`Run` entity with fragment chain, findings and activeEscalation, Lot 5) keyed by `runId`. */
  readonly parentRuns = signal<Map<string, ParentRunDetail>>(new Map())
  /** No real recent-teardown API exists yet; kept empty for backwards-compat. */
  readonly recentTasks = signal<RecentTask[]>([])
  readonly showDestroyed = signal(false)
  readonly answeringQuestionId = signal<string | null>(null)
  readonly questionFeedback = signal<{ interactionId: string; kind: 'conflict' | 'error'; message: string } | null>(
    null
  )
  readonly resumingResearchStepId = signal<string | null>(null)
  readonly researchResumptionFeedback = signal<StepFeedback | null>(null)
  private readonly researchResumptionKeys = new Map<string, { payload: string; key: string }>()
  /** Last Stop that was refused, not confirmed or failed, shown on the run it targets; cleared by the next Stop. */
  readonly stopFeedback = signal<StopFeedback | null>(null)
  readonly retryingStepId = signal<string | null>(null)
  readonly retryFeedback = signal<StepFeedback | null>(null)
  private readonly retryKeys = new Map<string, { payload: string; key: string }>()

  /** @deprecated Use {@link runs}. Kept as an alias for compatibility. */
  readonly sandboxes = computed(() => this.runs())

  readonly activeRuns = computed(() => this.runs().filter((run) => run.status !== 'destroyed'), {
    equal: sameReferences,
  })
  readonly destroyedRuns = computed(() => this.runs().filter((run) => run.status === 'destroyed'), {
    equal: sameReferences,
  })

  /**
   * Runs sorted by creation date, most recent first.
   *
   * Sorting is stable-descending on `createdAt` (epoch ms). Runs whose
   * `createdAt` is absent are placed at the end (unknown creation date).
   * The source signal is never mutated: `[...list]` creates a fresh copy
   * before sorting.
   */
  readonly visibleRuns = computed(
    () => {
      const list = this.showDestroyed() ? this.runs() : this.activeRuns()
      return sortRunsByCreation([...list])
    },
    { equal: sameReferences }
  )

  /**
   * Runs grouped into workstreams STRICTLY by `namespaceId` (never by title),
   * so namespaces sharing the same human name stay in distinct groups. Runs
   * without a `namespaceId` land in the explicit {@link UNASSIGNED_NAMESPACE_ID}
   * workstream.
   */
  private workstreamViews: WorkstreamView[] = []
  readonly workstreams = computed<WorkstreamView[]>(() => {
    const names = this.namespaces()
    const groups = new Map<string, FactoryRun[]>()
    for (const run of this.visibleRuns()) {
      const namespaceId = run.namespaceId?.trim() || UNASSIGNED_NAMESPACE_ID
      const bucket = groups.get(namespaceId) ?? []
      bucket.push(run)
      groups.set(namespaceId, bucket)
    }
    const previous = new Map(this.workstreamViews.map((group) => [group.namespaceId, group]))
    const next = [...groups.entries()]
      .map(([namespaceId, runs]) => {
        const old = previous.get(namespaceId)
        const title = namespaceId === UNASSIGNED_NAMESPACE_ID ? 'No namespace' : (names.get(namespaceId) ?? namespaceId)
        const stableRuns = retainArray(old?.runs ?? [], runs)
        return old && old.title === title && old.runs === stableRuns ? old : { namespaceId, title, runs: stableRuns }
      })
      .sort((left, right) => left.title.localeCompare(right.title))
    this.workstreamViews = retainArray(this.workstreamViews, next)
    return this.workstreamViews
  })

  readonly costs = computed<CostSummary>(
    () => {
      const active = this.activeRuns()
      // Do not turn unavailable analytics into a false zero or fall back to a
      // legacy snapshot/budget cost. A wholly empty dashboard legitimately totals 0.
      const realCostOf = (run: FactoryRun): number | null => (run.run ? run.run.costUsd : run.costUsd)
      const hasUnavailableAnalytics = active.some((run) => realCostOf(run) === null)
      const hasUnknownCompleteness = active.some((run) => run.run?.unknownCostCount == null)
      const workflowsUsd = hasUnavailableAnalytics
        ? null
        : active.reduce<number>((sum, run) => sum + (realCostOf(run) ?? 0), 0)
      const unknownCostCount = hasUnknownCompleteness
        ? null
        : active.reduce<number>((sum, run) => sum + (run.run?.unknownCostCount ?? 0), 0)
      return { active: active.length, workflowsUsd, totalUsd: workflowsUsd, unknownCostCount }
    },
    { equal: sameCardData }
  )

  // REAL: latest workflow snapshots from REST, and their mapped session details.
  private readonly workflows = signal<unknown[]>([])
  private readonly sessions = signal<Map<string, SessionDetail>>(new Map())
  private readonly enrichment = new Map<string, EnrichmentPartial>()
  private readonly subscriptions = new Subscription()
  private overviewRequest: Subscription | null = null
  private overviewRefreshPending = false
  private detailRequest: Subscription | null = null
  private detailRefreshPending = false
  private selectedWorkflowId: string | null = null
  private detailNamespaceId: string | null = null
  private namespaceGeneration = 0
  /** One coalesced enrichment request per overview run; avoids duplicate N+1 bursts. */
  private readonly overviewEnrichmentRequests = new Map<string, Subscription>()
  private readonly overviewEnrichmentPending = new Set<string>()
  /** Single in-flight metrics-only refresh of the visible active runs (never overlaps itself). */
  private usageRefreshRequest: Subscription | null = null
  /** Cumulative usage read once per removed run (retries and failed turns included), kept across reloads. */
  private readonly removedUsage = new Map<string, CumulativeUsage>()
  /** Removed runs already tried: a failed read is never retried in a loop. */
  private readonly removedUsageAttempted = new Set<string>()
  private removedUsageRequest: Subscription | null = null
  private destroyed = false

  constructor() {
    this.loadNamespaceOptions()
    // Periodic refresh:
    // - In all-namespace mode: refresh overview every 15s
    // - When viewing a session detail that is running or waiting: refresh detail every 4s
    //   as a safety net in case an SSE frame is missed or arrives before an attempt transition commits.
    this.subscriptions.add(
      timer(4000, 4000).subscribe(() => {
        if (this.selectedWorkflowId) {
          const current = this.sessions().get(this.selectedWorkflowId)
          if (current && (current.status === 'running' || current.status === 'queued')) {
            this.refreshSelectedSession()
          }
        }
      })
    )
    // - With a namespace selected, SSE only signals workflow projection changes, not usage
    //   growth: refresh the cumulative usage of the active runs with a bounded metrics-only read.
    this.subscriptions.add(
      timer(15000, 15000).subscribe(() => {
        if (this.selectedNamespaceId() === null) this.refresh()
        else this.refreshActiveUsage()
      })
    )
    // SSE carries invalidations only. Refresh the overview, and only refresh
    // expensive detail data when the invalidation targets the visible run.
    this.subscriptions.add(
      this.sse.invalidations$.subscribe((invalidation) => {
        const namespaceId = this.selectedNamespaceId()
        if (!namespaceId || (invalidation.namespaceId && invalidation.namespaceId !== namespaceId)) return
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
        if (!this.selectedNamespaceId()) return
        this.load()
        this.queueAllOverviewEnrichment()
        this.refreshSelectedSession()
      })
    )
  }

  ngOnDestroy(): void {
    this.destroyed = true
    this.subscriptions.unsubscribe()
    this.cancelNamespaceRequests()
    this.sse.close()
  }

  selectNamespace(namespaceId: string | null): void {
    const namespace = namespaceId?.trim() || null
    if (namespace === this.selectedNamespaceId()) return
    this.namespaceGeneration += 1
    this.cancelNamespaceRequests()
    this.selectedNamespaceId.set(namespace)
    this.workstreamMappingMissing.set(false)
    this.overviewError.set(null)
    this.clearScopedState()
    if (!namespace) {
      this.sse.close()
      this.load()
      return
    }
    this.sse.connect(namespace)
    this.load()
  }

  private loadNamespaceOptions(): void {
    this.namespaceOptionsLoading.set(true)
    this.namespaceOptionsError.set(null)
    this.api
      .getNamespaces()
      .pipe(finalize(() => this.namespaceOptionsLoading.set(false)))
      .subscribe({
        next: (items) => {
          this.namespaceOptions.set(items)
          this.applyNamespaces(items)
          this.namespaceOptionsLoading.set(false)
          this.load()
        },
        error: (error: FactoryApiError) => this.namespaceOptionsError.set(error),
      })
  }

  session(runId: string): SessionDetail | undefined {
    const real = this.sessions().get(runId)
    if (real) return real
    // Keep the intentional built-in demo only; never fabricate an arbitrary
    // real run when a scoped fetch has not found it.
    return runId === SESSION_872641A8.id ? SESSION_872641A8 : undefined
  }

  /**
   * Look up the parent Run associated with a session `workflowId` (either `runId`
   * itself or the prefix before `__f<ordinal>`).
   */
  parentRunFor(workflowOrRunId: string): ParentRunDetail | undefined {
    if (!workflowOrRunId) return undefined
    const direct = this.parentRuns().get(workflowOrRunId)
    if (direct) return direct
    const sep = workflowOrRunId.indexOf('__f')
    if (sep > 0) {
      const parentId = workflowOrRunId.slice(0, sep)
      return this.parentRuns().get(parentId)
    }
    return undefined
  }

  /** Fetch and cache a parent Run (`GET /api/factory/runs/:runId`). */
  loadParentRun(workflowOrRunId: string, namespaceId?: string): void {
    const sep = workflowOrRunId.indexOf('__f')
    const targetRunId = sep > 0 ? workflowOrRunId.slice(0, sep) : workflowOrRunId
    const ns = namespaceId ?? this.namespaceFor(workflowOrRunId)
    if (!targetRunId || !ns) return
    this.api.getParentRun(targetRunId, ns).subscribe({
      next: (detail) => {
        if (detail && detail.runId) {
          this.parentRuns.update((m) => new Map(m).set(detail.runId, detail))
        }
      },
      error: () => undefined,
    })
  }

  /** Reply to a run-level human escalation (`POST /api/factory/runs/:runId/escalations/:escalationId/reply`, Lot 5). */
  replyParentRunEscalation(
    runId: string,
    escalationId: string,
    selectedOption: string,
    text?: string,
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(runId)
    if (!ns) return
    this.api.replyParentRunEscalation(runId, escalationId, { selectedOption, text }, ns).subscribe({
      next: (updated) => {
        if (updated && updated.runId) {
          this.parentRuns.update((m) => new Map(m).set(updated.runId, updated))
        }
        this.refreshAfterAction(this.selectedWorkflowId ?? runId)
      },
      error: () => undefined,
    })
  }

  /** Select the only workflow whose expensive detail endpoints may be loaded. */
  selectSession(workflowId: string, namespaceId?: string): void {
    const id = workflowId.trim()
    const namespace = namespaceId?.trim() || null
    if (namespace && this.selectedNamespaceId() !== null && namespace !== this.selectedNamespaceId())
      this.selectNamespace(namespace)
    if (!id) {
      this.clearSessionSelection()
      return
    }
    if (id === this.selectedWorkflowId) {
      if (namespace && namespace !== this.detailNamespaceId) {
        this.detailNamespaceId = namespace
        this.cancelDetailRequest()
        this.startDetailRequest(id)
        return
      }
      this.queueDetailRefresh(id)
      return
    }
    this.selectedWorkflowId = id
    this.detailNamespaceId = namespace
    this.cancelDetailRequest()
    this.startDetailRequest(id)
  }

  /** Leave the detail view and cancel its in-flight HTTP requests. */
  clearSessionSelection(workflowId?: string): void {
    if (workflowId && workflowId !== this.selectedWorkflowId) return
    this.selectedWorkflowId = null
    this.detailNamespaceId = null
    this.cancelDetailRequest()
  }

  /** Public refresh of projections plus the currently visible detail. */
  refresh(): void {
    this.load()
    this.queueAllOverviewEnrichment()
    this.refreshSelectedSession()
  }

  /**
   * Refresh ONLY the metrics (cost + cumulative usage) of the running runs of the selected namespace.
   *
   * Bounded: one refresh at a time, at most {@link USAGE_REFRESH_CONCURRENCY} parallel reads, only runs
   * that are already fully enriched, not destroyed, still running, not the selected run (its detail
   * has its own refresh) and without an enrichment already in flight. A failed read keeps the previous
   * value: it never fabricates a zero and never touches other enrichment parts.
   */
  refreshActiveUsage(): void {
    const namespaceId = this.selectedNamespaceId()
    if (!namespaceId || (this.usageRefreshRequest && !this.usageRefreshRequest.closed)) return
    const generation = this.namespaceGeneration
    const targets = this.activeRuns().filter(
      (run) =>
        run.namespaceId === namespaceId &&
        run.run?.status === 'running' &&
        run.id !== this.selectedWorkflowId &&
        this.enrichment.has(run.id) &&
        !this.overviewEnrichmentRequests.has(run.id)
    )
    if (targets.length === 0) return
    this.usageRefreshRequest = from(targets)
      .pipe(
        mergeMap(
          (run) =>
            this.api.getMetrics(run.id, namespaceId).pipe(
              tap((metrics) => {
                if (metrics !== undefined && metrics !== null) {
                  this.applyEnrichment(run.id, namespaceId, generation, undefined, { metrics })
                }
              }),
              catchError(() => EMPTY)
            ),
          USAGE_REFRESH_CONCURRENCY
        )
      )
      .subscribe()
  }

  /**
   * One-shot, bounded cumulative-usage read for REMOVED runs.
   *
   * Factory's metrics endpoint refuses a removed workflow, so the only honest source is AgentOS'
   * lifetime aggregate over the case trees of the run's durable attempts (readable after removal).
   * The controller case is deliberately NEVER included: it can be a root shared by several Run
   * fragments, so summing it would leak other workflows' usage into this run (same rule as the
   * Factory backend, which excludes it whenever attempts exist). Without a durable attempt case the
   * run stays unavailable. Each run is tried once per namespace scope, at most
   * {@link REMOVED_USAGE_BATCH} runs at a time with {@link REMOVED_USAGE_CONCURRENCY} in parallel. A
   * run with no attempt case, more than the AgentOS id limit, or a failed read stays unavailable:
   * nothing is fabricated.
   */
  enrichRemovedRunsUsage(): void {
    if (this.destroyed || (this.removedUsageRequest && !this.removedUsageRequest.closed)) return
    const generation = this.namespaceGeneration
    const targets = this.destroyedRuns()
      .filter((run) => !!run.namespaceId && !this.removedUsageAttempted.has(run.id))
      .slice(0, REMOVED_USAGE_BATCH)
    if (targets.length === 0) return
    for (const run of targets) this.removedUsageAttempted.add(run.id)
    this.removedUsageRequest = from(targets)
      .pipe(
        mergeMap((run) => this.loadRemovedRunUsage(run, generation), REMOVED_USAGE_CONCURRENCY),
        finalize(() => {
          this.removedUsageRequest = null
          // Continue with the next batch of the same scope only.
          if (generation === this.namespaceGeneration) this.enrichRemovedRunsUsage()
        })
      )
      .subscribe()
  }

  private loadRemovedRunUsage(run: FactoryRun, generation: number): Observable<void> {
    const namespaceId = run.namespaceId as string
    return this.api.getAttempts(run.id, namespaceId).pipe(
      switchMap((attempts) => {
        // Attempt cases only: the controller case may be shared between workflows (see above).
        const caseIds = new Set<string>()
        for (const attempt of extractAttempts(attempts)) if (attempt.caseId) caseIds.add(attempt.caseId)
        if (caseIds.size === 0 || caseIds.size > MAX_CUMULATIVE_USAGE_CASE_IDS) return EMPTY
        return this.api.getCumulativeUsage([...caseIds])
      }),
      tap((usage) => {
        if (generation !== this.namespaceGeneration || !isUsableUsage(usage)) return
        this.removedUsage.set(run.id, usage)
        this.setRuns(
          this.runs().map((candidate) =>
            candidate.id === run.id && candidate.status === 'destroyed' ? withRemovedUsage(candidate, usage) : candidate
          )
        )
      }),
      map(() => undefined),
      catchError(() => of(undefined))
    )
  }

  /** Coalesce overview invalidations, retaining one trailing refresh while active. */
  private load(): void {
    if (this.namespaceOptionsLoading() && this.namespaceOptions().length === 0) return
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
    const namespaceId = this.selectedNamespaceId()
    if (!namespaceId) return this.loadAllNamespaces()
    const generation = this.namespaceGeneration
    return forkJoin({
      active: this.api.getWorkflows('active', namespaceId),
      removed: this.api.getWorkflows('removed', namespaceId),
    }).pipe(
      tap(({ active, removed }) => {
        if (generation !== this.namespaceGeneration || namespaceId !== this.selectedNamespaceId()) return
        this.workstreamMappingMissing.set(false)
        this.overviewError.set(null)
        this.applyWorkflows(active, removed)
      }),
      catchError((error: FactoryApiError) => {
        if (generation !== this.namespaceGeneration || namespaceId !== this.selectedNamespaceId()) return of(undefined)
        const mappingMissing = isMissingWorkstreamMapping(error)
        this.workstreamMappingMissing.set(mappingMissing)
        this.overviewError.set(mappingMissing ? null : error)
        this.clearScopedState()
        if (mappingMissing) this.sse.close()
        return of(undefined)
      }),
      switchMap(() => of(undefined))
    )
  }

  private loadAllNamespaces(): Observable<void> {
    const generation = this.namespaceGeneration
    const namespaces = extractNamespaceOptions(this.namespaceOptions())
    return from(namespaces).pipe(
      mergeMap(
        ({ id }, index) =>
          forkJoin({
            active: this.api.getWorkflows('active', id),
            removed: this.api.getWorkflows('removed', id),
          }).pipe(
            map((result) => ({ ...result, index, error: null as FactoryApiError | null })),
            catchError((error: FactoryApiError) =>
              of({
                index,
                active: [] as unknown[],
                removed: [] as unknown[],
                error: isMissingWorkstreamMapping(error) ? null : error,
              })
            )
          ),
        4
      ),
      toArray(),
      // Completion order is network-dependent; membership order follows the catalogue.
      map((results) => results.sort((left, right) => left.index - right.index)),
      tap((results) => {
        if (generation !== this.namespaceGeneration || this.selectedNamespaceId() !== null) return
        this.overviewError.set(results.find((result) => result.error)?.error ?? null)
        this.workstreamMappingMissing.set(false)
        this.applyWorkflows(
          results.flatMap((result) => result.active),
          results.flatMap((result) => result.removed)
        )
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
    for (const id of [...this.removedUsage.keys()]) {
      if (!liveIds.has(id)) this.removedUsage.delete(id)
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
        existing?.agentQuestions,
        existing?.attemptsLoadError
      )
      if (existing?.agentQuestionsError) detail.agentQuestionsError = existing.agentQuestionsError
      sessionMap.set(detail.id, detail)
    }
    this.sessions.set(sessionMap)

    // Derive one FactoryRun per real workflow snapshot. Removed snapshots are
    // surfaced as destroyed runs; `showDestroyed`/`visibleRuns` remain the ONLY
    // visibility filter. A run that already has enrichment keeps its enriched
    // card (cost, usage, phases) across the reload instead of flashing back to
    // "unavailable" until the next enrichment lands. A REMOVED run is never newly
    // enriched (Factory's metrics endpoint refuses a removed workflow with
    // WORKFLOW_REMOVED), so it only keeps the last value observed while it was
    // active; one never enriched stays honestly unavailable.
    const withKnownEnrichment = (snapshot: unknown, forcedStatus?: 'destroyed'): FactoryRun => {
      const base = mapProjectionToFactoryRun(snapshot, forcedStatus)
      const id = workflowIdOf(snapshot)
      const detail = id && this.enrichment.has(id) ? sessionMap.get(id) : undefined
      const enriched = detail ? withSummary(base, mapSessionDetailToRunSummary(snapshot, detail)) : base
      const usage = forcedStatus && id ? this.removedUsage.get(id) : undefined
      return usage ? withRemovedUsage(enriched, usage) : enriched
    }
    const activeRuns = effectiveActive.map((snapshot) => withKnownEnrichment(snapshot))
    const destroyedRuns = effectiveRemoved.map((snapshot) => withKnownEnrichment(snapshot, 'destroyed'))
    this.setRuns([...activeRuns, ...destroyedRuns])

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
    const namespaceId = snapshot ? namespaceOf(snapshot) : this.detailNamespaceId
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
    const generation = this.namespaceGeneration
    return this.api.getWorkflow(workflowId, namespaceId).pipe(
      switchMap((snapshot) => {
        if (
          generation !== this.namespaceGeneration ||
          (this.selectedNamespaceId() !== null && namespaceId !== this.selectedNamespaceId())
        )
          return of(undefined)
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
    const selectedNamespaceId = this.selectedNamespaceId()
    if (!namespaceId || (selectedNamespaceId !== null && namespaceId !== selectedNamespaceId)) return of(undefined)
    const generation = this.namespaceGeneration
    const merge = (partial: EnrichmentPartial): void =>
      this.applyEnrichment(id, namespaceId, generation, snapshot, partial)

    const optional = <T>(request: Observable<T>): Observable<T | undefined> =>
      request.pipe(catchError(() => of(undefined)))

    return forkJoin({
      timing: optional(this.api.getTiming(id, namespaceId)),
      evidence: optional(this.api.getEvidence(id, namespaceId)),
      metrics: optional(this.api.getMetrics(id, namespaceId)),
      interactions: optional(this.api.getInteractions(id, namespaceId)),
      attempts: this.api.getAttempts(id, namespaceId).pipe(
        map((attempts) => ({ attempts })),
        catchError((error: FactoryApiError) => of({ attempts: undefined, attemptsLoadError: error.message }))
      ),
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
        const attemptResult = result.attempts as { attempts?: unknown; attemptsLoadError?: string } | undefined
        const partial = Object.fromEntries(
          Object.entries(result).filter(([key, value]) => key !== 'attempts' && value !== undefined)
        ) as Parameters<typeof merge>[0]
        if (attemptResult?.attempts !== undefined) {
          partial.attempts = attemptResult.attempts
          // A successful retry clears a previous stale-attempt warning.
          partial.attemptsLoadError = undefined
        }
        if (attemptResult?.attemptsLoadError) partial.attemptsLoadError = attemptResult.attemptsLoadError
        merge(partial)
      }),
      switchMap(() => of(undefined))
    )
  }

  /**
   * Merge a partial enrichment into the run's stored enrichment and re-derive its session detail and
   * card from the LATEST snapshot (an SSE reload may land while requests are in flight, so an obsolete
   * snapshot can never overwrite a newer card/detail state). Ignored for a stale namespace generation.
   */
  private applyEnrichment(
    id: string,
    namespaceId: string,
    generation: number,
    fallbackSnapshot: unknown,
    partial: EnrichmentPartial
  ): void {
    if (
      generation !== this.namespaceGeneration ||
      (this.selectedNamespaceId() !== null && namespaceId !== this.selectedNamespaceId())
    )
      return
    const current = this.workflowSnapshot(id) ?? fallbackSnapshot
    if (current === undefined) return
    const next = { ...(this.enrichment.get(id) ?? {}), ...partial }
    this.enrichment.set(id, next)
    const detail = mapProjectionToSessionDetail(
      current,
      next.timing,
      next.evidence,
      next.metrics,
      next.interactions,
      next.attempts,
      next.actions,
      next.agentQuestions,
      next.attemptsLoadError
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

  private cancelNamespaceRequests(): void {
    this.overviewRefreshPending = false
    this.overviewRequest?.unsubscribe()
    this.overviewRequest = null
    this.usageRefreshRequest?.unsubscribe()
    this.usageRefreshRequest = null
    const removedUsageRequest = this.removedUsageRequest
    this.removedUsageRequest = null
    removedUsageRequest?.unsubscribe()
    this.cancelDetailRequest()
    for (const request of this.overviewEnrichmentRequests.values()) request.unsubscribe()
    this.overviewEnrichmentRequests.clear()
    this.overviewEnrichmentPending.clear()
  }

  private clearScopedState(): void {
    this.workflows.set([])
    this.workstreamViews = []
    this.runs.set([])
    this.sessions.set(new Map())
    this.enrichment.clear()
    this.removedUsage.clear()
    this.removedUsageAttempted.clear()
    if (this.detailNamespaceId !== this.selectedNamespaceId()) {
      this.selectedWorkflowId = null
      this.detailNamespaceId = null
    }
  }

  // ---------------------------------------------------------------------------
  // Governed actions: only ever triggered from `allowedActions`
  // ---------------------------------------------------------------------------

  /** Resolve the namespace bound to a loaded workflow snapshot, when known. */
  private namespaceFor(workflowId: string): string | undefined {
    const snapshot = this.workflows().find((item) => workflowIdOf(item) === workflowId)
    const namespace = snapshot
      ? namespaceOf(snapshot)
      : (this.detailNamespaceId ?? this.selectedNamespaceId() ?? undefined)
    return namespace && (this.selectedNamespaceId() === null || namespace === this.selectedNamespaceId())
      ? namespace
      : undefined
  }

  /** Reply to a human interaction, then re-fetch the authoritative state. */
  replyInteraction(
    workflowId: string,
    interactionId: string,
    payload: { actionId?: string; text?: string; expectedRevision?: number },
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    if (!ns) return
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
    if (!namespaceId) {
      this.answeringQuestionId.set(null)
      return
    }
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

  /**
   * Retry a FAILED agent step as a new attempt of the same run and case, for an explicitly authorized
   * `retry` action. The operator text is optional (0 to 4000 characters). A stable idempotency key is kept
   * for the same workflow/step/revision/context payload so a transport-ambiguous resend replays the original
   * command; a changed payload receives a fresh key.
   */
  retryFailedStep(
    workflowId: string,
    payload: { stepId: string; expectedRevision: number; additionalContext?: string },
    namespaceId?: string
  ): void {
    if (this.retryingStepId()) return
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    const additionalContext = (payload.additionalContext ?? '').trim()
    if (!ns || additionalContext.length > 4000) return

    const fingerprint = JSON.stringify({
      namespaceId: ns,
      expectedRevision: payload.expectedRevision,
      additionalContext,
    })
    const target = `${workflowId}:${payload.stepId}`
    const previous = this.retryKeys.get(target)
    const idempotencyKey = previous?.payload === fingerprint ? previous.key : generateRetryKey()
    this.retryKeys.set(target, { payload: fingerprint, key: idempotencyKey })
    this.retryingStepId.set(payload.stepId)
    this.retryFeedback.set(null)

    this.api
      .retryFailedStep(
        workflowId,
        payload.stepId,
        { namespaceId: ns, expectedRevision: payload.expectedRevision, additionalContext },
        idempotencyKey
      )
      .subscribe({
        next: () => {
          this.retryingStepId.set(null)
          this.retryKeys.delete(target)
          this.refreshAfterAction(workflowId)
        },
        error: (error: FactoryApiError) => {
          this.retryingStepId.set(null)
          this.retryFeedback.set(stepCommandFeedback(payload.stepId, error, 'retry'))
          if (error.status === 409) this.refreshAfterAction(workflowId)
        },
      })
  }

  /**
   * Submit additional operator context for an explicitly authorized
   * `resume_research` action. A stable idempotency key is retained for the same
   * workflow/step/revision/context payload so transport-ambiguous retries replay
   * the original command. A changed payload receives a fresh key.
   */
  resumeResearch(
    workflowId: string,
    payload: { stepId: string; expectedRevision: number; additionalContext: string },
    namespaceId?: string
  ): void {
    if (this.resumingResearchStepId()) return
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    const additionalContext = payload.additionalContext.trim()
    if (!ns || !additionalContext || additionalContext.length > 4000) return

    const fingerprint = JSON.stringify({
      namespaceId: ns,
      expectedRevision: payload.expectedRevision,
      additionalContext,
    })
    const target = `${workflowId}:${payload.stepId}`
    const previous = this.researchResumptionKeys.get(target)
    const idempotencyKey = previous?.payload === fingerprint ? previous.key : generateResearchResumptionKey()
    this.researchResumptionKeys.set(target, { payload: fingerprint, key: idempotencyKey })
    this.resumingResearchStepId.set(payload.stepId)
    this.researchResumptionFeedback.set(null)

    this.api
      .resumeResearch(
        workflowId,
        payload.stepId,
        { namespaceId: ns, expectedRevision: payload.expectedRevision, additionalContext },
        idempotencyKey
      )
      .subscribe({
        next: () => {
          this.resumingResearchStepId.set(null)
          this.researchResumptionKeys.delete(target)
          this.refreshAfterAction(workflowId)
        },
        error: (error: FactoryApiError) => {
          this.resumingResearchStepId.set(null)
          this.researchResumptionFeedback.set(stepCommandFeedback(payload.stepId, error, 'resumption'))
          if (error.status === 409) this.refreshAfterAction(workflowId)
        },
      })
  }

  /** Cancel a durable agent attempt, then re-fetch the authoritative state. */
  cancelAttempt(
    workflowId: string,
    attemptId: string,
    payload: { expectedRevision?: number; reason?: string },
    namespaceId?: string
  ): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    if (!ns) return
    this.stopFeedback.set(null)
    this.api.cancelAttempt(workflowId, attemptId, payload, ns).subscribe({
      next: (response) => {
        // The run is sealed, but the agents are only known to be stopped once AgentOS proved the kill.
        if (response?.killProven === false) {
          this.stopFeedback.set({
            workflowId,
            kind: 'unconfirmed',
            message: 'Stop requested but the agents are not confirmed stopped. Retry stop.',
          })
        }
        this.refreshAfterAction(workflowId)
      },
      error: (error: FactoryApiError) => {
        this.stopFeedback.set({ workflowId, ...stopErrorFeedback(error) })
        // A stale revision or an already finished attempt: the allowed state moved, show the current one.
        if (error.status === 409) this.refreshAfterAction(workflowId)
      },
    })
  }

  /** Continue a paused run cost, then re-fetch the authoritative state. */
  continueCost(workflowId: string, payload?: { expectedThreshold?: number }, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    if (!ns) return
    this.api
      .continueCost(workflowId, payload, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
  }

  /** Stop a paused run cost, then re-fetch the authoritative state. */
  stopCost(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    if (!ns) return
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
    if (!ns) return
    this.api
      .removeWorkflow(workflowId, ns)
      .subscribe({ next: () => this.refreshAfterAction(workflowId), error: () => undefined })
  }

  /** Restore a removed workflow, then re-fetch the authoritative state. */
  restore(workflowId: string, namespaceId?: string): void {
    const ns = namespaceId ?? this.namespaceFor(workflowId)
    if (!ns) return
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
   * identifiers.
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

  /** Reuse unchanged final cards, without confusing runs from different namespaces. */
  private setRuns(next: FactoryRun[]): void {
    const current = this.runs()
    const key = (run: FactoryRun): string => JSON.stringify([run.namespaceId, run.id])
    const previous = new Map(current.map((run) => [key(run), run]))
    const reconciled = next.map((run) => {
      const old = previous.get(key(run))
      return old && sameCardData(old, run) ? old : run
    })
    this.runs.set(retainArray(current, reconciled))
  }

  /** Replace the summary attached to a run once its real cost is known. */
  private updateRun(summary: RunSummary): void {
    this.setRuns(this.runs().map((run) => (run.id === summary.id ? withSummary(run, summary) : run)))
  }
}

/** Attach an enriched card summary to a run; a destroyed run stays destroyed. */
function withSummary(run: FactoryRun, summary: RunSummary): FactoryRun {
  const mainCaseId = summary.mainCaseId ?? run.mainCaseId
  return {
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
    ...(mainCaseId ? { mainCaseId } : {}),
  }
}

/** Max parallel metrics reads of one {@link FactoryStore.refreshActiveUsage} pass. */
const USAGE_REFRESH_CONCURRENCY = 4
/** Removed runs read per {@link FactoryStore.enrichRemovedRunsUsage} batch, with this many in parallel. */
const REMOVED_USAGE_BATCH = 8
const REMOVED_USAGE_CONCURRENCY = 2

/** An AgentOS aggregate is only trusted when its cost and unknown count are finite numbers. */
function isUsableUsage(usage: CumulativeUsage | null | undefined): usage is CumulativeUsage {
  return (
    !!usage && Number.isFinite(usage.cost) && Number.isFinite(usage.unknownCostCount) && usage.unknownCostCount >= 0
  )
}

/** Attach the lifetime usage of a removed run; a missing token total stays unknown, never zero. */
function withRemovedUsage(run: FactoryRun, usage: CumulativeUsage): FactoryRun {
  const tokens = Number.isFinite(usage.totalTokens) ? usage.totalTokens : null
  return {
    ...run,
    costUsd: usage.cost,
    finalCostUsd: usage.cost,
    tokens,
    ...(run.run ? { run: { ...run.run, costUsd: usage.cost, unknownCostCount: usage.unknownCostCount, tokens } } : {}),
  }
}

function sameReferences<T>(previous: T[], next: T[]): boolean {
  return previous.length === next.length && previous.every((item, index) => item === next[index])
}

function retainArray<T>(previous: T[], next: T[]): T[] {
  return sameReferences(previous, next) ? previous : next
}

/** Cards contain only plain mapped objects, arrays and scalar values (no raw payloads). */
function sameCardData(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true
  if (Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length && left.every((item, index) => sameCardData(item, right[index]))
  }
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false
  if (Array.isArray(left) || Array.isArray(right)) return false
  const a = left as Record<string, unknown>
  const b = right as Record<string, unknown>
  const keys = Object.keys(a)
  return (
    keys.length === Object.keys(b).length && keys.every((key) => Object.hasOwn(b, key) && sameCardData(a[key], b[key]))
  )
}

/** Parts of a run's enrichment that a single read can update. */
interface EnrichmentPartial {
  timing?: unknown
  evidence?: unknown
  metrics?: unknown
  interactions?: unknown
  attempts?: unknown
  attemptsLoadError?: string
  actions?: GetActionsResponse
  agentQuestions?: AgentQuestion[]
  agentQuestionsError?: SessionDetail['agentQuestionsError']
}

/** What a user is told when a Stop failed; the codes are the ones of the cancel route. */
function stopErrorFeedback(error: FactoryApiError): Pick<StopFeedback, 'kind' | 'message'> {
  switch (error.code) {
    case 'STOP_NOT_AUTHORIZED':
      return { kind: 'not_authorized', message: 'You are not allowed to stop this run.' }
    case 'STOP_RUNTIME_UNAVAILABLE':
      return { kind: 'error', message: 'AgentOS is unreachable; the run was not stopped.' }
    default:
      return { kind: 'error', message: error.message }
  }
}

function generateRetryKey(): string {
  const cryptoRef = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (typeof cryptoRef?.randomUUID === 'function') return cryptoRef.randomUUID()
  return `failed-step-retry-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function generateResearchResumptionKey(): string {
  const cryptoRef = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (typeof cryptoRef?.randomUUID === 'function') return cryptoRef.randomUUID()
  return `research-resumption-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function isMissingWorkstreamMapping(error: FactoryApiError): boolean {
  return error.code === 'WORKFLOW_NOT_FOUND' && error.message === 'No workstream is mapped to the requested namespace.'
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

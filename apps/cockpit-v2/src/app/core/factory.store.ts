import { Injectable, computed, inject, signal } from '@angular/core'
import { Subscription } from 'rxjs'
import { FactoryApiService } from './factory-api.service'
import { mapProjectionToRunSummary, mapProjectionToSessionDetail, namespaceOf, workflowIdOf } from './mappers'
import { CostSummary, RecentTask, RunSummary, Sandbox, SessionDetail } from './models'
import { RECENT_TASKS, SANDBOXES, SESSION_872641A8 } from './mock-data'
import { SseService } from './sse.service'

/**
 * Application state exposed as Angular signals.
 *
 * DATA PROVENANCE (what is real vs mocked):
 *
 *  - REAL (Spring/Kotlin `factory-service` via `/api/factory/workflows`):
 *      • the workflow RUNS mapped onto active sandboxes (`RunnerSummary`);
 *      • the per-workflow {@link SessionDetail} (projection + timing/evidence/metrics);
 *      • live invalidation + reconnect signals from `/api/factory/workflows/stream`.
 *
 *  - MOCKED (no Sandbox API exists yet):
 *      • the sandbox CONTAINER FLEET itself (`SANDBOXES` names/status/archay cost);
 *      • the aggregated archay / destroyed costs surfaced by `costs()`.
 *
 * When the REST backend is unreachable the store degrades gracefully: it keeps
 * the mocked fleet and clears the real run enrichment instead of throwing, so
 * the Angular application never crashes on a cold/offline backend.
 */
@Injectable({ providedIn: 'root' })
export class FactoryStore {
  private readonly api = inject(FactoryApiService)
  private readonly sse = inject(SseService)

  // MOCKED fleet: the container lifecycle has no backend yet.
  readonly sandboxes = signal<Sandbox[]>(SANDBOXES)
  readonly recentTasks = signal<RecentTask[]>(RECENT_TASKS)
  readonly showDestroyed = signal(false)

  readonly activeSandboxes = computed(() => this.sandboxes().filter((s) => s.status !== 'destroyed'))
  readonly destroyedSandboxes = computed(() => this.sandboxes().filter((s) => s.status === 'destroyed'))

  readonly visibleSandboxes = computed(() => (this.showDestroyed() ? this.sandboxes() : this.activeSandboxes()))

  readonly costs = computed<CostSummary>(() => {
    const active = this.activeSandboxes()
    // REAL: the active workflows' costs are the `realCost.cost` values mapped
    // from each run's `/metrics` payload (0 when the backend has no run-cost).
    const workflowsUsd = active.reduce((sum, s) => sum + (s.run?.costUsd ?? 0), 0)
    // Uncertainty is propagated verbatim: sum of the runs' unknownCostCount.
    const unknownCostCount = active.reduce((sum, s) => sum + (s.run?.unknownCostCount ?? 0), 0)
    // MOCK: sandbox container fleet has no backend API yet, so the archay
    // cost and the destroyed-sandbox total remain hard-coded placeholders.
    const archayUsd = active.reduce((sum, s) => sum + s.archayCostUsd, 0)
    const destroyedUsd = 179.28 // MOCK: aggregated server cost of every destroyed sandbox.
    return {
      active: active.length,
      workflowsUsd,
      archayUsd,
      destroyedUsd,
      totalUsd: workflowsUsd + archayUsd + destroyedUsd,
      unknownCostCount,
    }
  })

  // REAL: latest workflow snapshots from REST, and their mapped session details.
  private readonly workflows = signal<unknown[]>([])
  private readonly sessions = signal<Map<string, SessionDetail>>(new Map())
  private readonly enrichment = new Map<string, { timing?: unknown; evidence?: unknown; metrics?: unknown }>()
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

  destroy(name: string): void {
    this.sandboxes.update((list) =>
      list.map(
        (s): Sandbox =>
          s.name === name ? { ...s, status: 'destroyed', finalCostUsd: s.run?.costUsd ?? 0, run: undefined } : s
      )
    )
  }

  /** Re-fetch the active workflow projections and re-map the derived state. */
  private load(): void {
    this.api.getWorkflows('active').subscribe({
      next: (items) => this.applyWorkflows(items),
      error: () => {
        // Graceful degradation: no real runs, but keep the mocked fleet intact.
        this.workflows.set([])
        this.sessions.set(new Map())
        this.enrichment.clear()
      },
    })
  }

  private applyWorkflows(items: unknown[]): void {
    const snapshots = Array.isArray(items) ? items : []
    this.workflows.set(snapshots)
    this.enrichment.clear()
    this.attachRunsToSandboxes(snapshots.map((snapshot) => mapProjectionToRunSummary(snapshot)))

    const sessionMap = new Map<string, SessionDetail>()
    for (const snapshot of snapshots) {
      const detail = mapProjectionToSessionDetail(snapshot)
      sessionMap.set(detail.id, detail)
    }
    this.sessions.set(sessionMap)

    // Enrich each session asynchronously with timing/evidence/metrics. Failures
    // are per-workflow and rolled back silently to the projection-only detail.
    for (const snapshot of snapshots) this.enrichSession(snapshot)
  }

  /** Attach the mapped real runs to the active (mock) sandboxes, in order. */
  private attachRunsToSandboxes(runs: RunSummary[]): void {
    if (runs.length === 0) return
    this.sandboxes.update((list) => {
      let index = 0
      return list.map((sandbox) => {
        if (sandbox.status === 'destroyed') return sandbox
        const run = runs[index]
        if (!run) return sandbox
        index += 1
        return { ...sandbox, run }
      })
    })
  }

  private enrichSession(snapshot: unknown): void {
    const id = workflowIdOf(snapshot)
    if (!id) return
    const namespaceId = namespaceOf(snapshot)
    const merge = (partial: { timing?: unknown; evidence?: unknown; metrics?: unknown }): void => {
      const next = { ...(this.enrichment.get(id) ?? {}), ...partial }
      this.enrichment.set(id, next)
      const detail = mapProjectionToSessionDetail(snapshot, next.timing, next.evidence, next.metrics)
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
  }

  /** Replace the run attached to a sandbox once its real cost is known. */
  private updateSandboxRun(run: RunSummary): void {
    this.sandboxes.update((list) => list.map((sandbox) => (sandbox.run?.id === run.id ? { ...sandbox, run } : sandbox)))
  }
}

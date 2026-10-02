import { Injectable, computed, signal } from '@angular/core'
import { CostSummary, RecentTask, Sandbox, SessionDetail } from './models'
import { RECENT_TASKS, SANDBOXES, SESSION_872641A8 } from './mock-data'

/**
 * État applicatif en signals.
 * Brancher ici l'API REST (HttpClient) et le flux SSE de la factory.
 */
@Injectable({ providedIn: 'root' })
export class FactoryStore {
  readonly sandboxes = signal<Sandbox[]>(SANDBOXES)
  readonly recentTasks = signal<RecentTask[]>(RECENT_TASKS)
  readonly showDestroyed = signal(false)

  readonly activeSandboxes = computed(() => this.sandboxes().filter((s) => s.status !== 'destroyed'))
  readonly destroyedSandboxes = computed(() => this.sandboxes().filter((s) => s.status === 'destroyed'))

  readonly visibleSandboxes = computed(() => (this.showDestroyed() ? this.sandboxes() : this.activeSandboxes()))

  readonly costs = computed<CostSummary>(() => {
    const active = this.activeSandboxes()
    const workflowsUsd = active.reduce((sum, s) => sum + (s.run?.costUsd ?? 0), 0)
    const archayUsd = active.reduce((sum, s) => sum + s.archayCostUsd, 0)
    const destroyedUsd = 179.28 // agrégat serveur (toutes les sandboxes détruites)
    return {
      active: active.length,
      workflowsUsd,
      archayUsd,
      destroyedUsd,
      totalUsd: workflowsUsd + archayUsd + destroyedUsd,
    }
  })

  session(runId: string): SessionDetail | undefined {
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
}

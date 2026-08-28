import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { ActivatedRoute, Router } from '@angular/router'
import { toSignal } from '@angular/core/rxjs-interop'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { FactoryRunSummary } from '../../services/factory-api.service'
import { FactoryStateService } from '../../services/factory-state.service'
import { FactoryRunDetailComponent } from '../factory-run-detail/factory-run-detail.component'
import { FactoryLaunchComponent } from '../factory-launch/factory-launch.component'

@Component({
  selector: 'agentos-factory-runs',
  imports: [FactoryRunDetailComponent, FactoryLaunchComponent],
  templateUrl: './factory-runs.component.html',
  styleUrl: './factory-runs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryRunsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly namespaceState = inject(NamespaceStateService)
  protected readonly state = inject(FactoryStateService)

  /**
   * Optional namespace override — used when the component is embedded inside
   * CaseShellComponent (shell view=factory), where the namespace comes from the
   * parent rather than from the component's own query params.
   * When provided, it takes precedence over the `?ns=` query param.
   */
  readonly embeddedNamespaceId = input<string | undefined>(undefined)

  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  })
  private readonly namespaces = toSignal(this.namespaceState.namespaces$, { initialValue: [] })
  private readonly namespacesInitialized = toSignal(this.namespaceState.initialized$, { initialValue: false })

  /**
   * The resolved namespace: embedded input wins over `?ns=` query param.
   * This allows the component to work in both standalone-route mode and
   * as an embedded child of CaseShellComponent.
   */
  protected readonly namespaceId = computed(
    () => this.embeddedNamespaceId() ?? this.queryParamMap().get('ns') ?? undefined
  )
  protected readonly selectedRunId = computed(() => this.queryParamMap().get('run') ?? undefined)
  protected readonly namespaceName = computed(
    () => this.namespaces().find((namespace) => namespace.id === this.namespaceId())?.name ?? null
  )

  protected readonly selectedNamespace = computed<Namespace | null>(
    () => this.namespaces().find((namespace) => namespace.id === this.namespaceId()) ?? null
  )
  protected readonly namespaceAvailable = computed(
    () => this.namespacesInitialized() && !!this.namespaceId() && !!this.namespaceName()
  )
  protected readonly namespacesLoading = computed(() => !!this.namespaceId() && !this.namespacesInitialized())

  /**
   * When embedded inside the shell the header (namespace label, h1) is
   * redundant because the shell tab strip already provides that context.
   */
  protected readonly isEmbedded = computed(() => this.embeddedNamespaceId() !== undefined)

  /** Whether the launch form is open. */
  protected readonly showLaunchForm = signal(false)

  constructor() {
    effect(() => {
      const namespaceId = this.namespaceId()
      const namespaceName = this.namespaceName()
      if (!namespaceId) {
        this.state.clear()
        return
      }
      if (!this.namespacesInitialized()) {
        this.state.clear()
        return
      }
      if (!namespaceName) {
        this.state.clear()
        return
      }
      this.namespaceState.selectNamespace(namespaceId)
      if (this.state.namespaceId() !== namespaceId) this.state.load(namespaceId)
    })

    effect(() => {
      const namespaceId = this.namespaceId()
      const runId = this.selectedRunId()
      if (!namespaceId || !runId || !this.namespaceAvailable()) {
        this.state.clearDetail()
        return
      }
      if (this.state.selectedRun()?.runId !== runId) this.state.loadDetail(runId, namespaceId)
    })

    // Close launch form when namespace changes.
    effect(() => {
      this.namespaceId()
      this.showLaunchForm.set(false)
    })
  }

  protected openLaunchForm(): void {
    this.showLaunchForm.set(true)
  }

  /**
   * Called when the launch form emits success.
   * Refreshes the run list and navigates to the new run if runId is known.
   */
  protected onLaunched(runId: string | null): void {
    this.showLaunchForm.set(false)
    this.state.refresh()
    if (runId && this.namespaceId()) {
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { ns: this.namespaceId(), run: runId },
        queryParamsHandling: 'merge',
      })
    }
  }

  protected onLaunchCancelled(): void {
    this.showLaunchForm.set(false)
  }

  /**
   * Navigate to a specific run while preserving all existing query params.
   * Using queryParamsHandling: 'merge' ensures ?view=factory (and any other params
   * set by the parent shell) are not lost when the run link is clicked.
   * This fixes the embedded-shell bug where clicking a run dropped ?view=factory
   * and caused the shell to revert to the Cases view.
   */
  protected selectRun(runId: string): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { run: runId },
      queryParamsHandling: 'merge',
    })
  }

  protected runTitle(run: FactoryRunSummary): string {
    return run.context?.ticketSummary || run.context?.ticketId || run.workflow || run.runId
  }

  protected runContext(run: FactoryRunSummary): string {
    const details = [run.context?.ticketId, run.workflow, run.context?.roles?.join(', ')].filter(Boolean)
    return details.join(' · ')
  }

  protected selectPhase(index: number): void {
    this.selectedPhaseIndex.set(index)
  }

  protected readonly selectedPhaseIndex = signal(0)

  protected statusClass(status: string): string {
    return `factory-runs__status--${status.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  }

  protected formatDate(value: string | null): string {
    return value
      ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
      : '—'
  }

  protected formatDuration(durationMs: number | null): string {
    if (durationMs == null) return '—'
    const seconds = Math.round(durationMs / 1000)
    const minutes = Math.floor(seconds / 60)
    return minutes ? `${minutes}m ${seconds % 60}s` : `${seconds}s`
  }
}

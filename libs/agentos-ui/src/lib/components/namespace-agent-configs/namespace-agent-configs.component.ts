import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService, AgentConfigExecutionModeEnum } from '@whoz-oss/agentos-api-client'
import {
  ButtonDirective,
  CheckDirective,
  SectionHeaderComponent,
  TableDirective,
  TagComponent,
} from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, of, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'
interface AgentConfigRow {
  key: string
  config: AgentConfig
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-namespace-agent-configs',
  imports: [
    ButtonDirective,
    CheckDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './namespace-agent-configs.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceAgentConfigsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly controller = inject(AgentConfigControllerService)
  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly rows = signal<AgentConfigRow[]>([])
  /** Key of the row whose enable/disable request is in flight, if any: every switch waits for it. */
  private readonly togglingKey = signal<string | null>(null)
  protected readonly toggleError = signal<string | null>(null)
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.config.name, row.config.description, this.mode(row), this.isEnabled(row) ? 'Enabled' : 'Disabled', row.scope]
      .filter(Boolean)
      .join(' ')
  )
  private readonly allConfigs$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return forkJoin({
        namespace: this.controller.listByParentAgentConfig(this.namespaceId),
        platform: this.controller.listPlatformAgentsAgentConfig().pipe(catchError(() => of([] as AgentConfig[]))),
      })
    })
  )
  constructor() {
    this.allConfigs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ namespace, platform }) => {
        this.rows.set([
          ...platform.map((config, index) => ({
            key: `platform:${config.id ?? index}`,
            config,
            scope: 'platform' as const,
          })),
          ...namespace.map((config, index) => ({
            key: `namespace:${config.id ?? index}`,
            config,
            scope: 'namespace' as const,
          })),
        ])
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }
  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', 'new'])
  }
  protected inspect(row: AgentConfigRow): void {
    if (!row.config.id) return
    void this.router.navigate(
      row.scope === 'platform'
        ? ['/agentos', 'admin', 'agent-configs', row.config.id, 'inspect']
        : ['/agentos', this.namespaceId, 'agent-configs', row.config.id, 'inspect']
    )
  }
  protected launch(row: AgentConfigRow): void {
    if (row.scope === 'platform' || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', row.config.id, 'launch'])
  }
  protected edit(row: AgentConfigRow): void {
    if (row.scope === 'platform' || !row.config.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', row.config.id, 'edit'])
  }
  /**
   * Enables or disables a namespace agent in place. The checkbox has already flipped natively when the change
   * fires, so on failure it is put back by hand: its `checked` binding did not change and would not do it.
   */
  protected toggleEnabled(row: AgentConfigRow, input: HTMLInputElement): void {
    const id = row.config.id
    if (row.scope === 'platform' || !id || this.togglingKey() !== null) {
      input.checked = this.isEnabled(row)
      return
    }
    const wasEnabled = this.isEnabled(row)
    this.toggleError.set(null)
    this.togglingKey.set(row.key)
    const request$ = wasEnabled ? this.controller.disableAgentConfig(id) : this.controller.enableAgentConfig(id)
    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.rows.update((rows) =>
          rows.map((current) => (current.key === row.key ? { ...current, config: updated } : current))
        )
        input.checked = updated.enabled === true
        this.togglingKey.set(null)
      },
      error: () => {
        input.checked = wasEnabled
        this.toggleError.set(`${wasEnabled ? 'Disabling' : 'Enabling'} "${row.config.name}" failed. Try again.`)
        this.togglingKey.set(null)
      },
    })
  }

  protected isToggling(): boolean {
    return this.togglingKey() !== null
  }

  protected isEnabled(row: AgentConfigRow): boolean {
    return row.config.enabled === true
  }

  protected confirmDelete(row: AgentConfigRow): void {
    if (row.scope === 'platform' || !row.config.id) return
    this.deletion.execute(row.key, {
      request$: this.controller.deleteAgentConfig(row.config.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${row.config.name}" failed. Try again.`,
    })
  }
  protected canLaunch(row: AgentConfigRow): boolean {
    return row.scope === 'namespace' && row.config.executionMode === AgentConfigExecutionModeEnum.LOOP
  }
  protected mode(row: AgentConfigRow): string {
    return (
      row.config.executionMode ??
      (row.config.advancedExecution ? AgentConfigExecutionModeEnum.ADVANCED : AgentConfigExecutionModeEnum.SIMPLE)
    )
  }
}

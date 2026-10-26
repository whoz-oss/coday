import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService, AgentConfigExecutionModeEnum } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, of, switchMap } from 'rxjs'
interface AgentConfigRow {
  key: string
  config: AgentConfig
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-namespace-agent-configs',
  imports: [SectionHeaderComponent, TableDirective, TagComponent],
  templateUrl: './namespace-agent-configs.component.html',
  styleUrl: './namespace-agent-configs.component.scss',
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
  protected readonly rows = signal<AgentConfigRow[]>([])
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
  protected delete(row: AgentConfigRow): void {
    if (row.scope === 'platform' || !row.config.id || !confirm(`Delete agent config “${row.config.name}”?`)) return
    this.controller
      .deleteAgentConfig(row.config.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
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

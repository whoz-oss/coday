import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService, AgentConfigExecutionModeEnum } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformAgentConfigsComponent — table view for platform-level agent configs.
 *
 * Loaded at /agentos/admin/agent-configs. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform configs have no namespaceId — they are shared across all namespaces.
 * "Launch" is not offered here: a loop creates cases, which always live in a namespace.
 */
@Component({
  selector: 'agentos-platform-agent-configs',
  imports: [SectionHeaderComponent, TableDirective, TableFilterComponent],
  templateUrl: './platform-agent-configs.component.html',
  styleUrl: './platform-agent-configs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAgentConfigsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly pendingDeleteId = signal<string | null>(null)
  protected readonly configs = signal<AgentConfig[]>([])
  protected readonly rowFilter = createRowFilter(this.configs, (config) =>
    [config.name, config.description, config.modelName, this.mode(config)].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.agentConfigController.listPlatformAgentsAgentConfig(true)
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (configs) => {
          this.configs.set(configs)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected mode(config: AgentConfig): string {
    return (
      config.executionMode ??
      (config.advancedExecution ? AgentConfigExecutionModeEnum.ADVANCED : AgentConfigExecutionModeEnum.SIMPLE)
    )
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'agent-configs', 'new'])
  }

  protected inspect(config: AgentConfig): void {
    if (!config.id) return
    void this.router.navigate(['/agentos', 'admin', 'agent-configs', config.id, 'inspect'])
  }

  protected edit(config: AgentConfig): void {
    if (!config.id) return
    void this.router.navigate(['/agentos', 'admin', 'agent-configs', config.id, 'edit'])
  }

  protected requestDelete(config: AgentConfig): void {
    this.pendingDeleteId.set(config.id ?? null)
  }

  protected cancelDelete(): void {
    this.pendingDeleteId.set(null)
  }

  protected confirmDelete(config: AgentConfig): void {
    this.pendingDeleteId.set(null)
    this.agentConfigController
      .deleteAgentConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }
}

import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { IntegrationConfig, IntegrationConfigControllerService } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { IntegrationConfigStateService } from '../../services/integration-config-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformIntegrationConfigsComponent — table view for platform-level integration configs.
 *
 * Loaded at /agentos/admin/integration-configs. Accessible to super-admins only
 * (the admin UI is restricted; backend platform writes require super-admin).
 * The list endpoint itself is available to any authenticated user.
 *
 * Platform integration configs have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-integration-configs',
  imports: [SectionHeaderComponent, TableDirective, TableFilterComponent],
  templateUrl: './platform-integration-configs.component.html',
  styleUrl: './platform-integration-configs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformIntegrationConfigsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(IntegrationConfigStateService)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly pendingDeleteId = signal<string | null>(null)
  protected readonly configs = signal<IntegrationConfig[]>([])
  protected readonly rowFilter = createRowFilter(this.configs, (config) =>
    [config.name, config.integrationType].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.state.loadPlatformConfigs()
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

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'integration-configs', 'new'])
  }

  protected edit(config: IntegrationConfig): void {
    void this.router.navigate(['/agentos', 'admin', 'integration-configs', config.id ?? '', 'edit'])
  }

  protected requestDelete(config: IntegrationConfig): void {
    this.pendingDeleteId.set(config.id ?? null)
  }

  protected cancelDelete(): void {
    this.pendingDeleteId.set(null)
  }

  protected confirmDelete(config: IntegrationConfig): void {
    this.pendingDeleteId.set(null)
    this.integrationConfigController
      .deleteIntegrationConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }
}

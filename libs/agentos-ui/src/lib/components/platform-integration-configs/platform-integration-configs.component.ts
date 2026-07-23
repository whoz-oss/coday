import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { IntegrationConfig, IntegrationConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

/**
 * PlatformIntegrationConfigsComponent — list view for platform-level integration configs.
 *
 * Loaded at /agentos/admin/integration-configs. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform integration configs have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-integration-configs',
  imports: [AsyncPipe, EntityListComponent, IntegrationConfigItemComponent, IconButtonComponent],
  templateUrl: './platform-integration-configs.component.html',
  styleUrl: './platform-integration-configs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformIntegrationConfigsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.integrationConfigController.listIntegrationConfig())
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly configItems$ = this.configs$.pipe(
    map((configs) =>
      configs.map(
        (c: IntegrationConfig): EntityListItem => ({
          id: c.id ?? '',
          name: c.name,
          description: c.integrationType,
        })
      )
    )
  )

  /** Full config objects indexed by id — used to resolve itemTemplate events. */
  private configsById = new Map<string, IntegrationConfig>()

  constructor() {
    this.configs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((configs) => {
      this.configsById = new Map(configs.map((c: IntegrationConfig) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'integration-configs', 'new'])
  }

  protected deleteConfig(config: IntegrationConfig): void {
    this.integrationConfigController
      .deleteIntegrationConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected onEdit(config: IntegrationConfig): void {
    this.router.navigate(['/agentos', 'admin', 'integration-configs', config.id ?? '', 'edit'])
  }

  protected resolveConfig(id: string): IntegrationConfig | null {
    return this.configsById.get(id) ?? null
  }
}

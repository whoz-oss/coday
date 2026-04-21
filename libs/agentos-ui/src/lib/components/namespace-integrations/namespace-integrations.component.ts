import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, IntegrationConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

/**
 * NamespaceIntegrationsComponent — list view for integration configs of a namespace.
 *
 * Loaded at /:namespaceId/integrations. Responsibilities:
 * - Load and display the list of integration configs via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/integrations/new)
 * - Deletion with confirmation (delegated to IntegrationConfigItemComponent)
 *
 * Create and edit are handled by IntegrationFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-integrations',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, IntegrationConfigItemComponent, IconButtonComponent],
  templateUrl: './namespace-integrations.component.html',
  styleUrl: './namespace-integrations.component.scss',
})
export class NamespaceIntegrationsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.integrationConfigController.listByParentIntegrationConfig(this.namespaceId))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly configItems$ = this.configs$.pipe(
    map((configs) =>
      configs.map(
        (c): EntityListItem => ({
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
      this.configsById = new Map(configs.map((c) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'])
  }

  protected deleteConfig(config: IntegrationConfig): void {
    this.integrationConfigController
      .deleteIntegrationConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveConfig(id: string): IntegrationConfig | null {
    return this.configsById.get(id) ?? null
  }
}

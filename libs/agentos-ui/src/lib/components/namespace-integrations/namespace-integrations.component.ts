import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { IntegrationConfig, IntegrationConfigControllerService } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

/**
 * NamespaceIntegrationsComponent — list view for integration configs of a namespace.
 *
 * Loaded at /:namespaceId/integrations. Responsibilities:
 * - Load and display the list of integration configs for the current namespace
 * - Navigate to the create form (/:namespaceId/integrations/new)
 * - Deletion with confirmation (delegated to IntegrationConfigItemComponent)
 *
 * Create and edit are handled by IntegrationFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-integrations',
  standalone: true,
  imports: [AsyncPipe, IconButtonComponent, IntegrationConfigItemComponent],
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

  protected readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.integrationConfigController.listByNamespace(this.namespaceId))
  )

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', 'new'])
  }

  protected deleteConfig(config: IntegrationConfig): void {
    this.integrationConfigController
      .delete2(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected trackById(_index: number, config: IntegrationConfig): string {
    return config.id ?? ''
  }
}

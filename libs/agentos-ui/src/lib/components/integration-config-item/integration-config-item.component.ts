import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { IntegrationConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * IntegrationConfigItemComponent — presentational component for a single integration config row.
 *
 * Displays the config name, integration type, and action buttons (edit / delete).
 * Edit navigates to the dedicated edit route; delete is emitted upward.
 *
 * Delete uses the same two-step confirmation pattern as NamespaceItemComponent.
 */
@Component({
  selector: 'agentos-integration-config-item',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './integration-config-item.component.html',
  styleUrl: './integration-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationConfigItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) config!: IntegrationConfig
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<IntegrationConfig>()

  protected readonly pendingDelete = signal(false)

  protected onEdit(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'integrations', this.config.id, 'edit'])
  }

  protected onDeleteArmed(): void {
    this.pendingDelete.set(true)
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

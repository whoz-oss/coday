import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core'
import { IntegrationConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * IntegrationConfigItemComponent — presentational component for a single integration config row.
 *
 * Displays the config name, integration type, and action buttons (edit / delete).
 * All mutations are emitted upward — no service injection.
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
  @Input({ required: true }) config!: IntegrationConfig

  @Output() editRequested = new EventEmitter<IntegrationConfig>()
  @Output() deleteRequested = new EventEmitter<IntegrationConfig>()

  protected readonly pendingDelete = signal(false)

  protected onEdit(): void {
    this.editRequested.emit(this.config)
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

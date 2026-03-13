import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * NamespaceItemComponent — presentational component for a single namespace row.
 *
 * Displays the namespace name and description, with edit and delete buttons (ds-icon-button).
 * Navigation on click, editing and deletion are emitted upward — no direct service injection.
 *
 * Delete uses an inline two-step confirmation: first click arms the delete, a second click
 * on the confirm button (or a cancel) resolves the intent. This avoids accidental deletions
 * without requiring a modal dialog.
 */
@Component({
  selector: 'agentos-namespace-item',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  @Input({ required: true }) namespace!: Namespace

  @Output() selected = new EventEmitter<Namespace>()
  @Output() editRequested = new EventEmitter<Namespace>()
  @Output() deleteRequested = new EventEmitter<Namespace>()

  protected readonly pendingDelete = signal(false)

  protected onSelect(): void {
    this.selected.emit(this.namespace)
  }

  protected onEdit(): void {
    this.editRequested.emit(this.namespace)
  }

  protected onDeleteArmed(): void {
    this.pendingDelete.set(true)
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.namespace)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

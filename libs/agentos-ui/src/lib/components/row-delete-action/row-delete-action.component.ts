import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { RowDeletionState } from '../../utils/row-deletion-state'

@Component({
  selector: 'agentos-row-delete-action',
  templateUrl: './row-delete-action.component.html',
  styleUrl: './row-delete-action.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'row-delete-action' },
})
export class RowDeleteActionComponent {
  readonly rowKey = input.required<string>()
  readonly rowLabel = input.required<string>()
  readonly state = input.required<RowDeletionState>()
  readonly deleteClass = input<string>('')
  readonly confirmLabelClass = input<string>('')

  readonly deleteRequested = output<void>()
  readonly deleteConfirmed = output<void>()
  readonly deleteCancelled = output<void>()

  protected readonly isConfirming = computed(() => this.state().confirmingId() === this.rowKey())
  protected readonly isDeleting = computed(() => this.state().deletingId() === this.rowKey())

  protected requestDelete(): void {
    this.deleteRequested.emit()
  }

  protected confirmDelete(): void {
    this.deleteConfirmed.emit()
  }

  protected cancelDelete(): void {
    this.deleteCancelled.emit()
  }
}

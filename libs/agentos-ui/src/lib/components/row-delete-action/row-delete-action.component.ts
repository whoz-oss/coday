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

  readonly request = output<void>()
  readonly confirm = output<void>()
  readonly cancel = output<void>()

  protected readonly isConfirming = computed(() => this.state().confirmingId() === this.rowKey())
  protected readonly isDeleting = computed(() => this.state().deletingId() === this.rowKey())

  protected requestDelete(): void {
    this.request.emit()
  }

  protected confirmDelete(): void {
    this.confirm.emit()
  }

  protected cancelDelete(): void {
    this.cancel.emit()
  }
}

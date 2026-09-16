import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { FormsModule } from '@angular/forms'
import { WorkflowProjectionSnapshotDto } from '../../services/factory-workflow-projection.model'

export type WorkflowProjectionCardMode = 'active' | 'removed'
type ConfirmationKind = 'remove' | 'purge'

@Component({
  selector: 'agentos-factory-workflow-projection',
  imports: [FormsModule],
  templateUrl: './factory-workflow-projection.component.html',
  styleUrl: './factory-workflow-projection.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryWorkflowProjectionComponent {
  readonly snapshot = input.required<WorkflowProjectionSnapshotDto>()
  readonly mode = input<WorkflowProjectionCardMode>('active')
  readonly pending = input(false)
  readonly removeRequested = output<string>()
  readonly restoreRequested = output<string>()
  readonly purgeRequested = output<string>()
  protected readonly confirmation = signal<ConfirmationKind | null>(null)
  protected readonly purgeText = signal('')
  protected readonly purgeMatches = computed(() => {
    const expected = this.snapshot().projection.title || this.snapshot().workflowId
    return this.purgeText().trim() === expected
  })
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('confirmationDialog')

  protected statusClass(status: string): string {
    return `workflow-projection__status--${status.replace(/_/g, '-')}`
  }
  protected displayStatus(status: string): string {
    return status.replace(/_/g, ' ')
  }
  protected openConfirmation(kind: ConfirmationKind): void {
    this.confirmation.set(kind)
    this.purgeText.set('')
    this.dialog()?.nativeElement.showModal()
  }
  protected closeConfirmation(): void {
    this.dialog()?.nativeElement.close()
    this.confirmation.set(null)
    this.purgeText.set('')
  }
  protected confirm(): void {
    if (this.confirmation() === 'remove') this.removeRequested.emit(this.snapshot().workflowId)
    if (this.confirmation() === 'purge' && this.purgeMatches()) this.purgeRequested.emit(this.snapshot().workflowId)
    this.closeConfirmation()
  }
  protected restore(): void {
    this.restoreRequested.emit(this.snapshot().workflowId)
  }
}

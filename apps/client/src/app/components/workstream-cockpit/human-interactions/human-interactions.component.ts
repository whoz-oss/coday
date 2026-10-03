import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { HumanActionDto, HumanActionId, HumanActionRequiredDto } from '../../../core/models/workstream.model'

/** Response intent emitted by the view — derived strictly from the DTO actions list. */
export interface InteractionResponse {
  workflowId: string
  interactionId: string
  actionId: HumanActionId
}

/**
 * Human interactions view: open checkpoints with their prompt, recipient/actor,
 * response options (rendered strictly from the DTO `actions` field) and a
 * resumption-state indicator.
 */
@Component({
  selector: 'app-human-interactions',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './human-interactions.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './human-interactions.component.scss',
})
export class HumanInteractionsComponent {
  @Input() interactions: HumanActionRequiredDto[] = []
  @Input() workflowId: string | null = null

  @Output() respond = new EventEmitter<InteractionResponse>()

  protected onRespond(interaction: HumanActionRequiredDto, action: HumanActionDto): void {
    if (!this.workflowId) return
    this.respond.emit({
      workflowId: this.workflowId,
      interactionId: interaction.interactionId,
      actionId: action.id,
    })
  }
}

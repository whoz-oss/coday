import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import {
  AllowedActionDto,
  HumanActionDto,
  HumanActionId,
  HumanActionRequiredDto,
} from '../../../core/models/workstream.model'

/** Response intent emitted by the view — derived strictly from the DTO actions list. */
export interface InteractionResponse {
  workflowId: string
  interactionId: string
  actionId: HumanActionId
  expectedRevision?: number
}

/**
 * Human interactions view: open checkpoints with their prompt, recipient/actor,
 * response options (rendered strictly from the DTO `actions` field) and a
 * resumption-state indicator.
 *
 * Response controls are only enabled when the backend `allowedActions` read
 * authorizes a `reply` for the interaction (an unauthorized caller gets none).
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
  @Input() allowedActions: AllowedActionDto[] = []

  @Output() respond = new EventEmitter<InteractionResponse>()

  /** True when the backend authorizes a `reply` on this interaction. */
  protected canRespond(interaction: HumanActionRequiredDto): boolean {
    return this.replyActionFor(interaction) !== undefined
  }

  protected onRespond(interaction: HumanActionRequiredDto, action: HumanActionDto): void {
    if (!this.workflowId || !this.canRespond(interaction)) return
    const allowed = this.replyActionFor(interaction)
    this.respond.emit({
      workflowId: this.workflowId,
      interactionId: interaction.interactionId,
      actionId: action.id,
      expectedRevision: allowed?.expectedRevision ?? interaction.expectedRevision,
    })
  }

  private replyActionFor(interaction: HumanActionRequiredDto): AllowedActionDto | undefined {
    return this.allowedActions.find(
      (action) =>
        (action.type ?? action.id ?? action.kind) === 'reply' &&
        (!action.interactionId || action.interactionId === interaction.interactionId)
    )
  }
}

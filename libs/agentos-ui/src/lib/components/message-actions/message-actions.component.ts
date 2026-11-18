import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { CopyButtonComponent, IconButtonComponent } from '@whoz-oss/design-system'

/**
 * What the pencil of a user message says: a re-run starts the conversation again from that message, and
 * nothing it did is rolled back.
 */
export const RERUN_TOOLTIP =
  'Edit and re-run this message. Re-running does not undo what the tools already did (files, commits, tickets, sub-cases).'

/**
 * MessageActionsComponent: the actions under the bubble of a message, always visible.
 *
 * Every bubble can be copied. The pencil is offered only when the host says the message can be edited (a user
 * message of the current user, while the case is idle and nothing else is being edited), and asks the host to
 * open the editor: it does nothing else.
 *
 * Usage:
 *   <agentos-message-actions [text]="text" [editable]="canEdit(item)" (editRequested)="edit(item)" />
 */
@Component({
  selector: 'agentos-message-actions',
  imports: [CopyButtonComponent, IconButtonComponent],
  templateUrl: './message-actions.component.html',
  styleUrl: './message-actions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageActionsComponent {
  /** The text that the copy button puts in the clipboard. */
  readonly text = input.required<string>()
  /** Whether the message can be edited and run again. */
  readonly editable = input(false)

  readonly editRequested = output<void>()

  protected readonly editTooltip = RERUN_TOOLTIP
}

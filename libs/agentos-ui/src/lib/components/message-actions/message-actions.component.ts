import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { CopyButtonComponent, IconButtonComponent } from '@whoz-oss/design-system'

/**
 * What the pencil of a user message says: a re-run starts the conversation again from that message, and
 * nothing it did is rolled back.
 */
export const RERUN_TOOLTIP =
  'Edit and re-run this message. Re-running does not undo what the tools already did (files, commits, tickets, sub-cases).'

/**
 * What the reload button of a user message says: it runs again from that message with its text untouched, and
 * nothing it did is rolled back.
 */
export const RELOAD_TOOLTIP =
  'Reload. Runs this message again as it is. Reloading does not undo what the tools already did (files, commits, tickets, sub-cases).'

/**
 * MessageActionsComponent: the actions under the bubble of a message, always visible.
 *
 * Every bubble can be copied. The reload button and the pencil are offered only when the host says the message can
 * be edited (a user message of the current user, while the case is idle and nothing else is being edited): reload
 * asks the host to run the message again as it is, the pencil asks it to open the editor. Neither does anything else.
 *
 * Usage:
 *   <agentos-message-actions
 *     [text]="text"
 *     [editable]="canEdit(item)"
 *     (reloadRequested)="reload(item)"
 *     (editRequested)="edit(item)"
 *   />
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
  /** Run the message again, text untouched. */
  readonly reloadRequested = output<void>()

  protected readonly editTooltip = RERUN_TOOLTIP
  protected readonly reloadTooltip = RELOAD_TOOLTIP
}

import { ChangeDetectionStrategy, Component, input } from '@angular/core'

export type NoticeTone = 'error' | 'warning' | 'success' | 'neutral'

/**
 * ds-notice — a message that is not tied to one field: a failed save, a warning, a status.
 *
 * An `error` is announced as an alert, the other tones as a status.
 */
@Component({
  selector: 'ds-notice',
  template: '<ng-content />',
  styleUrl: './notice.component.scss',
  host: {
    '[class]': "'ds-notice--' + tone()",
    '[attr.role]': "tone() === 'error' ? 'alert' : 'status'",
  },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NoticeComponent {
  readonly tone = input<NoticeTone>('error')
}

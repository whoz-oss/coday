import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { VoiceRecorderState } from './voice-recorder.service'
import { formatElapsed } from './voice-recorder.utils'

/**
 * VoiceRecorderComponent: presentational recording bar of the composer (WhatsApp-like).
 *
 * While recording: a pulsing indicator, the elapsed time, a cancel button (discards) and a send button.
 * While the recording is uploaded and transcribed: a progress line and no button. An error from the
 * recorder is shown inline, whatever the state. Renders nothing when idle without an error.
 *
 * It holds no logic: the host owns the [VoiceRecorderService] and reacts to [cancelled] and [sent].
 */
@Component({
  selector: 'agentos-voice-recorder',
  imports: [IconButtonComponent],
  templateUrl: './voice-recorder.component.html',
  styleUrl: './voice-recorder.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VoiceRecorderComponent {
  readonly state = input.required<VoiceRecorderState>()
  readonly elapsedMs = input(0)
  readonly error = input<string | null>(null)

  readonly cancelled = output<void>()
  readonly sent = output<void>()

  protected readonly formatElapsed = formatElapsed
}

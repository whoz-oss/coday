import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  viewChild,
} from '@angular/core'
import { VOICE_RECORDER_STATE, VoiceRecorderState } from './voice-recorder.service'
import { formatElapsed } from './voice-recorder.utils'

/**
 * VoiceRecorderComponent: presentational recording area of the composer.
 *
 * It takes the place of the textarea while a voice message is recorded: a pulsing dot, the elapsed time and a short
 * hint. While the recording is uploaded and transcribed it shows a spinner and its own line. An error from the recorder
 * is one line, whatever the state. Renders nothing when idle without an error.
 *
 * It has no button: the composer's action bar sends (its send button) and cancels (where the mic was).
 *
 * The textarea is hidden while recording, so the keys are read here: the bar takes the focus when the recording
 * starts and hands every keydown to the host through [keyPressed] (the host knows the Enter preference). The recorder
 * holds no other logic: the host owns the [VoiceRecorderService].
 */
@Component({
  selector: 'agentos-voice-recorder',
  templateUrl: './voice-recorder.component.html',
  styleUrl: './voice-recorder.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VoiceRecorderComponent {
  private readonly injector = inject(Injector)

  readonly state = input.required<VoiceRecorderState>()
  readonly elapsedMs = input(0)
  readonly error = input<string | null>(null)
  /** The key that sends the recording, as the host's Enter preference defines it (shown in the hint). */
  readonly sendKey = input('Enter')

  /** A key pressed while the recording bar has the focus. */
  readonly keyPressed = output<KeyboardEvent>()

  private readonly bar = viewChild<ElementRef<HTMLElement>>('bar')

  protected readonly formatElapsed = formatElapsed
  protected readonly states = VOICE_RECORDER_STATE

  constructor() {
    // The bar exists once the recording view is rendered: focus it then, not when the state changes.
    effect(() => {
      if (this.state() !== VOICE_RECORDER_STATE.RECORDING) return
      afterNextRender(() => this.bar()?.nativeElement.focus(), { injector: this.injector })
    })
  }
}

import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, OnDestroy, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Audio as AudioContent, ExchangeControllerService } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { Observable } from 'rxjs'
import { formatElapsed } from '../voice-recorder/voice-recorder.utils'

type AudioMessageState = 'idle' | 'loading' | 'ready' | 'unavailable'

/**
 * AudioMessageComponent: the player of a voice message in the timeline.
 *
 * The recording lives in the case exchange. It is fetched through the exchange download endpoint on the
 * first click on play (lazy: a long conversation does not download every recording on open) and played
 * from an object URL. The URL is built from a NEW Blob typed with the message's own MIME type: the server
 * answers `video/webm` for a .webm file, which a media element may refuse as audio, so the response's
 * Content-Type is never trusted.
 *
 * A file that cannot be fetched leaves a small note: the transcript, shown by the host under the player,
 * remains readable. The object URL is revoked when the bubble is destroyed.
 */
@Component({
  selector: 'agentos-audio-message',
  imports: [IconButtonComponent],
  templateUrl: './audio-message.component.html',
  styleUrl: './audio-message.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AudioMessageComponent implements OnDestroy {
  private readonly exchange = inject(ExchangeControllerService)
  private readonly destroyRef = inject(DestroyRef)

  readonly caseId = input.required<string>()
  readonly audio = input.required<AudioContent>()

  protected readonly state = signal<AudioMessageState>('idle')
  protected readonly url = signal<string | null>(null)

  protected readonly formatElapsed = formatElapsed

  /** Fetches the recording, then shows the player (which starts playing: the user just asked for it). */
  protected load(): void {
    if (this.state() !== 'idle') return
    this.state.set('loading')
    // The generated method is typed Observable<string> but requests responseType 'blob' (Accept
    // application/octet-stream), so at runtime it yields a Blob (same cast as ExchangeStateService).
    const download$ = this.exchange.downloadCaseFileExchange(
      this.caseId(),
      this.audio().path
    ) as unknown as Observable<Blob>
    download$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (body) => {
        const typed = new Blob([body], { type: this.audio().mimeType })
        this.url.set(URL.createObjectURL(typed))
        this.state.set('ready')
      },
      error: (err) => {
        console.warn('[AudioMessage] recording could not be loaded', err)
        this.state.set('unavailable')
      },
    })
  }

  ngOnDestroy(): void {
    this.revoke()
  }

  private revoke(): void {
    const url = this.url()
    if (url) URL.revokeObjectURL(url)
    this.url.set(null)
  }
}

import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { EmptyStateComponent, IconButtonComponent, SpinnerComponent } from '@whoz-oss/design-system'
import { MarkdownPipe } from '../../pipes/markdown.pipe'
import { detectFormat, reindentJson } from '../../services/exchange-content.utils'
import { ExchangeFileRef } from '../../services/exchange-state.service'

/**
 * ExchangeContentViewerComponent — presentational preview pane for a single file.
 *
 * Renders markdown via the `agentosMarkdown` pipe, JSON re-indented, YAML/text in a <pre>,
 * and HTML in a sandboxed <iframe> (with a title — fixing the legacy a11y gap). Non-viewable
 * files (binary or > 20 MB) and load errors show a "Download instead" CTA. Images are shown from an
 * object URL prepared by the parent (`imageUrl`), scaled to fit the pane; if the browser cannot decode
 * one, the same "Preview not available" state is shown rather than a broken-image icon.
 *
 * I/O in signals (decision #8). The smart parent owns content loading + the canView decision.
 */
@Component({
  selector: 'agentos-exchange-content-viewer',
  standalone: true,
  imports: [MarkdownPipe, SpinnerComponent, EmptyStateComponent, IconButtonComponent],
  templateUrl: './exchange-content-viewer.component.html',
  styleUrl: './exchange-content-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeContentViewerComponent {
  readonly file = input.required<ExchangeFileRef>()
  readonly content = input<string | null>(null)
  /** Object URL of the image to display when the file is an image. */
  readonly imageUrl = input<string | null>(null)
  readonly status = input<'loading' | 'ready' | 'error'>('loading')
  /** False when the file is binary or too large to preview — only a download is offered. */
  readonly canView = input<boolean>(true)

  readonly closeRequested = output<void>()
  readonly downloadRequested = output<ExchangeFileRef>()

  // read:ElementRef so the host element (not the ds-icon-button instance) is returned.
  private readonly backButton = viewChild('backButton', { read: ElementRef })

  constructor() {
    // A new image (or a new file) gets a fresh chance to decode.
    effect(() => {
      this.imageUrl()
      this.file()
      this.imageFailed.set(false)
    })

    // a11y: move focus into the preview when it opens so keyboard users land on the
    // back control rather than losing focus to <body>.
    afterNextRender(() => {
      const host = this.backButton()?.nativeElement as HTMLElement | undefined
      host?.querySelector('button')?.focus()
    })
  }

  protected readonly filename = computed(() => {
    const path = this.file().path
    return path.split('/').pop() ?? path
  })

  protected readonly format = computed(() => detectFormat(this.filename()))

  /** Set when the browser could not decode the image, to fall back to the download CTA. */
  protected readonly imageFailed = signal(false)

  protected onImageError(): void {
    this.imageFailed.set(true)
  }

  /** JSON is re-indented for readability; other text formats render as-is. */
  protected readonly displayContent = computed(() => {
    const raw = this.content()
    if (raw == null) return ''
    return this.format() === 'json' ? reindentJson(raw) : raw
  })

  protected onClose(): void {
    this.closeRequested.emit()
  }

  protected onDownload(): void {
    this.downloadRequested.emit(this.file())
  }
}

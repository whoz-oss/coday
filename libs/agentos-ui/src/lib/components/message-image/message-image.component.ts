import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { ensureImageBlob } from '../../services/exchange-content.utils'
import { ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import { FileChipComponent } from '../file-chip/file-chip.component'

/**
 * MessageImageComponent: an image a message carries, shown in the conversation as a thumbnail that opens full size on
 * click (Escape, the backdrop or the close button bring it back).
 *
 * It reads the bytes of the file itself, from the case or the namespace it is in, so it does not depend on what the
 * file drawer is showing. When the image cannot be read (deleted, not allowed, too slow) it falls back on the file
 * chip, which still says what the message carries.
 *
 * Usage:
 *   <agentos-message-image [caseId]="caseId" [namespaceId]="namespaceId" [scope]="file.scope" [path]="file.path" [name]="file.name" />
 */
@Component({
  selector: 'agentos-message-image',
  imports: [FileChipComponent, IconButtonComponent],
  templateUrl: './message-image.component.html',
  styleUrl: './message-image.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'close()' },
})
export class MessageImageComponent {
  private readonly state = inject(ExchangeStateService)

  readonly caseId = input.required<string>()
  readonly namespaceId = input.required<string>()
  readonly scope = input.required<ExchangeScope>()
  readonly path = input.required<string>()
  /** The file name, for the tooltip and the alternative text. */
  readonly name = input.required<string>()

  /** Object URL of the image, once read. */
  protected readonly url = signal<string | null>(null)
  protected readonly failed = signal(false)
  protected readonly opened = signal(false)

  constructor() {
    effect((onCleanup) => {
      const scope = this.scope()
      const path = this.path()
      const id = scope === ExchangeFileEntryScopeEnum.CASE ? this.caseId() : this.namespaceId()
      this.url.set(null)
      this.failed.set(false)
      this.opened.set(false)
      if (!id) return
      // An object URL keeps its blob in memory until revoked.
      let objectUrl: string | null = null
      const subscription = untracked(() => this.state.getBlobIn(scope, id, path)).subscribe({
        next: (blob) => {
          objectUrl = URL.createObjectURL(ensureImageBlob(blob instanceof Blob ? blob : new Blob([blob]), path))
          this.url.set(objectUrl)
        },
        error: () => this.failed.set(true),
      })
      onCleanup(() => {
        subscription.unsubscribe()
        if (objectUrl) URL.revokeObjectURL(objectUrl)
      })
    })
  }

  protected close(): void {
    this.opened.set(false)
  }
}

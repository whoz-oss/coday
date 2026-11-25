import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { ExchangeScope } from '../../services/exchange-state.service'
import { kindLabel, mentionScopeWord, middleTruncate } from '../composer-attachments/composer-attachments.utils'

/**
 * FileChipComponent: presentational chip of a file of an exchange, as the composer shows the files picked in the file
 * drawer and as a message shows the files it carries: a kind icon, the name cut in the middle, its extension, and
 * the exchange (case or namespace) it is in.
 *
 * Read-only unless [removable]: then a remove button ends the chip and emits [removed]. It knows nothing of the list
 * it is in, nor of what removing means.
 *
 * Usage:
 *   <agentos-file-chip name="readme.md" path="docs/readme.md" [scope]="scope" />
 */
@Component({
  selector: 'agentos-file-chip',
  imports: [IconButtonComponent],
  templateUrl: './file-chip.component.html',
  styleUrl: './file-chip.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FileChipComponent {
  /** The file name, as shown (cut in the middle when long). */
  readonly name = input.required<string>()
  /** The path in its exchange, shown in full on hover. Defaults to the name. */
  readonly path = input<string | null>(null)
  readonly scope = input.required<ExchangeScope>()
  /** Ends the chip with a remove button. */
  readonly removable = input(false)
  /** Disables the remove button. */
  readonly disabled = input(false)

  readonly removed = output<void>()

  protected readonly scopeWord = computed(() => mentionScopeWord(this.scope()))
  /** Scope-qualified path, as the agent reads it. */
  protected readonly fullPath = computed(() => `${this.scopeWord()}:${this.path() ?? this.name()}`)
  protected readonly icon = computed(() => (this.scopeWord() === 'namespace' ? 'folder_shared' : 'description'))
  protected readonly shownName = computed(() => middleTruncate(this.name()))
  protected readonly kind = computed(() => kindLabel(this.name()))
}

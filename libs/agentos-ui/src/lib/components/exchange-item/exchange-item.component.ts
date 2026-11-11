import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { ExchangeDirectoryEntry } from '@whoz-oss/agentos-api-client'
import { EntityCardBadge, IconButtonComponent } from '@whoz-oss/design-system'
import { GitFileStatus } from '../../services/exchange-environment.service'
import { GIT_FILE_LABELS } from '../../services/exchange-git-tree.utils'
import { formatDate, formatSize, getFileIcon } from '../../services/exchange-content.utils'
import { injectCopyFeedback } from '../../services/clipboard.utils'

/** View-model for one dense file row. */
export interface ExchangeFileRow {
  path: string
  filename: string
  meta: string
  icon: string
  badges?: EntityCardBadge[]
  gitStatus?: GitFileStatus
  missing?: boolean
}

/**
 * ExchangeItemComponent — presentational dense row for one exchange file.
 *
 * Selecting the row (click / Enter / Space) requests a preview. Download and "copy relative path" are
 * always offered; delete is only rendered when `canWrite` is true (per the scope's server-computed
 * capability). The copied value is `row.path`, which is already relative to the scope root.
 *
 * I/O in signals (decision #8), aligned on `ai-provider-item`.
 */
@Component({
  selector: 'agentos-exchange-item',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './exchange-item.component.html',
  styleUrl: './exchange-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeItemComponent {
  readonly row = input.required<ExchangeFileRow>()
  readonly active = input<boolean>(false)
  readonly canWrite = input<boolean>(false)
  /** Offers a checkbox to pick the row (never on a missing, i.e. deleted, row). */
  readonly selectable = input<boolean>(false)
  readonly selected = input<boolean>(false)

  readonly selectionToggled = output<void>()
  readonly viewRequested = output<void>()
  readonly downloadRequested = output<void>()
  readonly deleteRequested = output<void>()
  readonly diffRequested = output<void>()
  protected readonly gitLabels = GIT_FILE_LABELS

  private readonly copyFeedback = injectCopyFeedback()
  protected readonly copyState = this.copyFeedback.state

  protected copyPath(): void {
    void this.copyFeedback.copy(this.row().path)
  }

  /**
   * The checkbox is controlled by `selected`: it goes back to it at once, and shows the new state only if the
   * parent applies it (the next binding update), so it never shows something the parent refused.
   */
  protected onToggle(event: Event): void {
    const checkbox = event.target as HTMLInputElement
    checkbox.checked = this.selected()
    this.selectionToggled.emit()
  }

  /**
   * Build the row view-model from a directory entry.
   *
   * Size and timestamp are optional on the wire (a directory reports no size), so a file missing
   * either still renders rather than showing `NaN`.
   */
  static toRow(file: ExchangeDirectoryEntry): ExchangeFileRow {
    const parts = [
      file.size !== undefined ? formatSize(file.size) : null,
      file.lastModified ? formatDate(file.lastModified) : null,
    ]
    return {
      path: file.path,
      filename: file.name,
      meta: parts.filter((p) => p !== null).join(' · '),
      icon: getFileIcon(file.name),
    }
  }
}

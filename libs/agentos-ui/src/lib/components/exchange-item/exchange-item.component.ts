import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { ExchangeDirectoryEntry } from '@whoz-oss/agentos-api-client'
import { EntityCardBadge, IconButtonComponent } from '@whoz-oss/design-system'
import { GitFileStatus } from '../../services/exchange-environment.service'
import { GIT_FILE_LABELS } from '../../services/exchange-git-tree.utils'
import { formatDate, formatSize, getFileIcon } from '../../services/exchange-content.utils'

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
 * Selecting the row (click / Enter / Space) requests a preview. Download is always offered;
 * delete is only rendered when `canWrite` is true (per the scope's server-computed capability).
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

  readonly viewRequested = output<void>()
  readonly downloadRequested = output<void>()
  readonly deleteRequested = output<void>()
  readonly diffRequested = output<void>()
  protected readonly gitLabels = GIT_FILE_LABELS

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

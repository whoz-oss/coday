import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { EntityCardBadge, IconButtonComponent } from '@whoz-oss/design-system'
import { formatDate, formatSize, getFileIcon } from '../../services/exchange-content.utils'

/** View-model for one dense file row. */
export interface ExchangeFileRow {
  path: string
  filename: string
  meta: string
  icon: string
  badges?: EntityCardBadge[]
}

/**
 * ExchangeItemComponent — presentational dense row for one exchange file.
 *
 * Selecting the row (click / Enter / Space) requests a preview. Download is always offered;
 * delete is only rendered when `canWrite` is true (namespace rows never pass canWrite=true).
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

  /** Build the row view-model from a manifest file entry. */
  static toRow(file: ExchangeFileEntry): ExchangeFileRow {
    return {
      path: file.path,
      filename: file.filename,
      meta: `${formatSize(file.size)} · ${formatDate(file.lastModified)}`,
      icon: getFileIcon(file.filename),
      badges:
        file.scope === ExchangeFileEntryScopeEnum.NAMESPACE ? [{ label: 'read-only', variant: 'info' }] : undefined,
    }
  }
}

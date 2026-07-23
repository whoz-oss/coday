import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { ExchangeFileRef, ExchangeScope } from '../../services/exchange-state.service'
import { ExchangeFileRow, ExchangeItemComponent } from '../exchange-item/exchange-item.component'

/**
 * ExchangeFileListComponent — dense vertical list of exchange file rows for one scope.
 *
 * Replaces `ds-entity-list` (a full-page pattern). Per-row actions are rebuilt into an
 * `ExchangeFileRef` using this list's `scope`. Active-row highlight is wired by hand.
 *
 * Loading / empty / error states are owned by the parent section (exchange-drawer); this
 * component only renders the rows it is given. I/O in signals (decision #8).
 */
@Component({
  selector: 'agentos-exchange-file-list',
  standalone: true,
  imports: [ExchangeItemComponent],
  templateUrl: './exchange-file-list.component.html',
  styleUrl: './exchange-file-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeFileListComponent {
  readonly rows = input.required<ExchangeFileRow[]>()
  readonly scope = input.required<ExchangeScope>()
  readonly activeFile = input<ExchangeFileRef | null>(null)
  readonly canWrite = input<boolean>(false)

  readonly fileSelected = output<ExchangeFileRef>()
  readonly downloadRequested = output<ExchangeFileRef>()
  readonly deleteRequested = output<ExchangeFileRef>()

  protected isActive(row: ExchangeFileRow): boolean {
    const active = this.activeFile()
    return active !== null && active.scope === this.scope() && active.path === row.path
  }

  protected onView(row: ExchangeFileRow): void {
    this.fileSelected.emit({ scope: this.scope(), path: row.path })
  }

  protected onDownload(row: ExchangeFileRow): void {
    this.downloadRequested.emit({ scope: this.scope(), path: row.path })
  }

  protected onDelete(row: ExchangeFileRow): void {
    this.deleteRequested.emit({ scope: this.scope(), path: row.path })
  }
}

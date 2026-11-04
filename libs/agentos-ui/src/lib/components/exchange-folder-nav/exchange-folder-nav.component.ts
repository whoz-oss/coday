import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { ExchangePathSegment } from '../../services/exchange-state.service'

/**
 * ExchangeFolderNavComponent — "where am I, and how do I go back" bar of a file-exchange section.
 *
 * Shows an explicit Back button (to the parent folder) next to the breadcrumb. The last crumb is the
 * current folder: it is a label, not a link. Emits the path to navigate to (empty string = scope root).
 *
 * Presentational, OnPush, I/O in signals.
 */
@Component({
  selector: 'agentos-exchange-folder-nav',
  templateUrl: './exchange-folder-nav.component.html',
  styleUrl: './exchange-folder-nav.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeFolderNavComponent {
  /** Path of the folder being browsed, root first. Empty when at the scope root. */
  readonly breadcrumb = input.required<ExchangePathSegment[]>()

  /** A folder, or the scope root (empty string), was chosen. */
  readonly navigated = output<string>()

  protected readonly current = computed(() => this.breadcrumb().at(-1) ?? null)
  protected readonly ancestors = computed(() => this.breadcrumb().slice(0, -1))
  protected readonly parent = computed(() => {
    const segments = this.breadcrumb()
    if (segments.length === 0) return null
    const parent = segments.at(-2)
    return { label: parent?.label ?? 'root', path: parent?.path ?? '' }
  })
}

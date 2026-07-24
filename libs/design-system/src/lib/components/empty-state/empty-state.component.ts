import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { MatIcon } from '@angular/material/icon'

/**
 * DsEmptyState — a centred placeholder shown when a list or panel has no content.
 *
 * Renders an optional Material icon, a title, an optional hint, and projects any
 * content (typically an action button) below the text.
 *
 * CSS contract: --color-text, --color-text-secondary
 *
 * @example
 * <ds-empty-state icon="inbox" title="No configurations yet" hint="Create one to get started.">
 *   <button mat-flat-button (click)="create()">New configuration</button>
 * </ds-empty-state>
 */
@Component({
  selector: 'ds-empty-state',
  standalone: true,
  imports: [MatIcon],
  templateUrl: './empty-state.component.html',
  styleUrl: './empty-state.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyStateComponent {
  /** Optional Material icon name rendered above the title. */
  readonly icon = input<string>()

  /** Primary message describing the empty state. */
  readonly title = input<string>('')

  /** Optional secondary text giving more context or next steps. */
  readonly hint = input<string>()
}

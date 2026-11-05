import { booleanAttribute, ChangeDetectionStrategy, Component, input } from '@angular/core'

/**
 * ds-page — the layout of every settings page that is not a bare list.
 *
 * ```html
 * <ds-page>
 *   <ds-section-header title="Edit user">…</ds-section-header>
 *   <form>…</form>
 * </ds-page>
 * ```
 *
 * - the `ds-section-header` takes the whole column, like the one of a list;
 * - everything else stops at one width (720px) and aligns left; `full` lifts that for a page made of a table;
 * - `framed` is for a page rendered OUTSIDE a settings shell (profile, new namespace, namespaces list): it brings
 *   the same 1180px frame as the shells, centered, with its own gutter. Inside a shell the page fills the content
 *   column and the shell provides the gutter.
 */
@Component({
  selector: 'ds-page',
  template: `
    <div class="ds-page">
      <ng-content select="ds-section-header" />
      <div class="ds-page__body" [class.ds-page__body--full]="full()">
        <ng-content />
      </div>
    </div>
  `,
  styleUrl: './page.component.scss',
  host: { '[class.ds-page--framed]': 'framed()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageComponent {
  /** Takes the 1180px frame of the settings shells (for a page rendered outside one). */
  readonly framed = input(false, { transform: booleanAttribute })
  /** Lifts the 720px width of the body (a page made of a table). */
  readonly full = input(false, { transform: booleanAttribute })
}

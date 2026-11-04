import { ChangeDetectionStrategy, Component, input } from '@angular/core'

/**
 * ds-section-header — the header of every settings page: title, supporting copy and projected actions.
 *
 * Anything with a `headerMeta` attribute (a link, a tag) is shown under the copy. Projected actions (a filter, a
 * `dsButton`) lay themselves out: on a narrow screen the actions stack and stretch, and each of them is
 * responsible for its own touch target.
 */
@Component({
  selector: 'ds-section-header',
  template: `
    <div class="section-header__copy">
      <h2>{{ title() }}</h2>
      @if (description()) {
        <p>{{ description() }}</p>
      }
      <div class="section-header__meta"><ng-content select="[headerMeta]" /></div>
    </div>
    <div class="section-header__actions"><ng-content /></div>
  `,
  styleUrl: './section-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SectionHeaderComponent {
  readonly title = input.required<string>()
  readonly description = input<string>('')
}

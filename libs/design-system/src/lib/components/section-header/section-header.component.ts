import { ChangeDetectionStrategy, Component, input } from '@angular/core'

/** Heading, supporting copy and projected actions for a settings section. */
@Component({
  selector: 'ds-section-header',
  template: `
    <div class="section-header__copy">
      <h2>{{ title() }}</h2>
      @if (description()) {
        <p>{{ description() }}</p>
      }
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

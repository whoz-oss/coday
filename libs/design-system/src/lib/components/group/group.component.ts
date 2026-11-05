import { ChangeDetectionStrategy, Component, input } from '@angular/core'

/** ds-group — a titled group of fields without a frame: the title is a small uppercase label over a rule. */
@Component({
  selector: 'ds-group',
  template: `
    <section class="ds-group">
      <h3 class="ds-group__title">{{ heading() }}</h3>
      <ng-content />
    </section>
  `,
  styleUrl: './group.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GroupComponent {
  readonly heading = input.required<string>()
}

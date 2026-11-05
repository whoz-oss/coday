import { ChangeDetectionStrategy, Component, input } from '@angular/core'

/** ds-card — a bordered block of a read-only page, with an optional heading. */
@Component({
  selector: 'ds-card',
  template: `
    @if (heading()) {
      <h3 class="ds-card__heading">{{ heading() }}</h3>
    }
    <ng-content />
  `,
  styleUrl: './card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CardComponent {
  readonly heading = input<string>('')
}

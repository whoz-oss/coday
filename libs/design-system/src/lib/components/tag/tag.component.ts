import { ChangeDetectionStrategy, Component, input } from '@angular/core'

export type TagVariant = 'accent' | 'neutral' | 'outline'

@Component({
  selector: 'ds-tag',
  template: '<ng-content />',
  styleUrl: './tag.component.scss',
  host: { '[class]': "'tag tag--' + variant()" },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TagComponent {
  readonly variant = input<TagVariant>('accent')
}

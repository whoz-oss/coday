import { ChangeDetectionStrategy, Component, Input } from '@angular/core'

export type SpinnerSize = 'sm' | 'md' | 'lg'

/**
 * DsSpinner — a lightweight, pure-CSS loading indicator.
 *
 * Exposes role="status" with aria-live="polite" so assistive tech announces the
 * loading state. Provide a meaningful `label` to describe what is loading.
 *
 * CSS contract: --color-border, --color-primary
 *
 * @example
 * <ds-spinner size="lg" label="Loading agents" />
 */
@Component({
  selector: 'ds-spinner',
  standalone: true,
  templateUrl: './spinner.component.html',
  styleUrl: './spinner.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpinnerComponent {
  /** Visual size of the spinner. */
  @Input() size: SpinnerSize = 'md'

  /** Accessible label announced to assistive technology. */
  @Input() label: string = 'Loading'
}

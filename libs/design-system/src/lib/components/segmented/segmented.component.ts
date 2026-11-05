import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'

export interface SegmentedOption<T> {
  value: T
  label: string
  /** Element id, for the panel's aria-labelledby (tabs mode). */
  id?: string
  disabled?: boolean
}

/**
 * ds-segmented — a short row of mutually exclusive choices.
 *
 * `toggle` (default) is a group of pressed buttons (a setting); `tabs` is a tablist, for switching the panel under
 * it (give the panel `role="tabpanel"` and its id as `panelId`).
 */
@Component({
  selector: 'ds-segmented',
  template: `
    <div class="ds-segmented" [attr.role]="mode() === 'tabs' ? 'tablist' : 'group'" [attr.aria-label]="label() || null">
      @for (option of options(); track option.label) {
        <button
          type="button"
          class="ds-segmented__option"
          [class.ds-segmented__option--active]="option.value === value()"
          [attr.id]="option.id ?? null"
          [attr.role]="mode() === 'tabs' ? 'tab' : null"
          [attr.aria-selected]="mode() === 'tabs' ? option.value === value() : null"
          [attr.aria-controls]="mode() === 'tabs' ? (panelId() ?? null) : null"
          [attr.aria-pressed]="mode() === 'tabs' ? null : option.value === value()"
          [disabled]="option.disabled ?? false"
          (click)="valueChange.emit(option.value)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styleUrl: './segmented.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SegmentedComponent<T = unknown> {
  readonly options = input.required<ReadonlyArray<SegmentedOption<T>>>()
  readonly value = input.required<T>()
  readonly label = input<string>('')
  readonly mode = input<'toggle' | 'tabs'>('toggle')
  readonly panelId = input<string | undefined>(undefined)

  readonly valueChange = output<T>()
}

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { ButtonDirective } from '../button/button.directive'

export interface MemberRoleOption {
  value: string
  label: string
}

/**
 * ds-member-row — one member of a roster: name, a secondary line, a role picker and a remove button.
 *
 * Presentational: the host owns the list and applies `valueChange` / `removed`.
 */
@Component({
  selector: 'ds-member-row',
  imports: [ButtonDirective],
  template: `
    <div class="ds-member">
      <div class="ds-member__info">
        <span class="ds-member__name">{{ label() }}</span>
        @if (detail()) {
          <span class="ds-member__detail">{{ detail() }}</span>
        }
      </div>
      <select
        class="ds-control ds-member__role"
        [attr.aria-label]="'Role of ' + label()"
        (change)="onRoleChange($event)"
      >
        @for (option of options(); track option.value) {
          <option [value]="option.value" [selected]="option.value === value()">{{ option.label }}</option>
        }
      </select>
      <button
        dsButton
        variant="icon-danger"
        type="button"
        [title]="'Remove ' + label()"
        [attr.aria-label]="'Remove ' + label()"
        (click)="removed.emit()"
      >
        &times;
      </button>
    </div>
  `,
  styleUrl: './member-row.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MemberRowComponent {
  readonly label = input.required<string>()
  /** A secondary line (an email), shown when it adds something to the label. */
  readonly detail = input<string>('')
  readonly value = input.required<string>()
  readonly options = input.required<ReadonlyArray<MemberRoleOption>>()

  readonly valueChange = output<string>()
  readonly removed = output<void>()

  protected onRoleChange(event: Event): void {
    this.valueChange.emit((event.target as HTMLSelectElement).value)
  }
}

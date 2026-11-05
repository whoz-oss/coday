import { booleanAttribute, ChangeDetectionStrategy, Component, input } from '@angular/core'

/**
 * ds-form-field — a label, one control, a hint and an error, laid out the same way on every form.
 *
 * ```html
 * <ds-form-field label="Name" controlId="ns-name" required hint="Shown in the list." [error]="nameError()">
 *   <input dsControl id="ns-name" [formControl]="name" />
 * </ds-form-field>
 * ```
 *
 * What is projected (the control, and any extra `<p class="ds-hint">`) goes between the label and the `hint`.
 * `hintId` / `errorId` let the control point at them with aria-describedby.
 */
@Component({
  selector: 'ds-form-field',
  template: `
    @if (label()) {
      <label class="ds-field__label" [attr.for]="controlId() || null">
        {{ label() }}
        @if (required()) {
          <span class="ds-field__required" aria-hidden="true">*</span>
        }
        @if (labelHint()) {
          <span class="ds-field__label-hint">{{ labelHint() }}</span>
        }
      </label>
    }
    <ng-content />
    @if (hint()) {
      <p class="ds-hint" [attr.id]="hintId() || null">{{ hint() }}</p>
    }
    @if (error()) {
      <p class="ds-error" role="alert" [attr.id]="errorId() || null">{{ error() }}</p>
    }
  `,
  styleUrl: './form-field.component.scss',
  host: { '[class.ds-field--grow]': 'grow()' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormFieldComponent {
  readonly label = input<string>('')
  /** Id of the control, for the label's `for`. */
  readonly controlId = input<string>('')
  readonly required = input(false, { transform: booleanAttribute })
  /** Muted text next to the label ("optional", a unit). */
  readonly labelHint = input<string>('')
  readonly hint = input<string | null>(null)
  readonly hintId = input<string>('')
  /** The validation message; the field shows nothing while it is empty. */
  readonly error = input<string | null>(null)
  readonly errorId = input<string>('')
  /** Takes the free width of a row of fields. */
  readonly grow = input(false, { transform: booleanAttribute })
}

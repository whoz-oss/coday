import { booleanAttribute, Directive, input } from '@angular/core'

/**
 * dsControl — the skin of every text control (input, select, textarea) of the settings pages.
 *
 * The look is the global `.ds-control` of styles/_primitives.scss, including the validation state: a touched
 * invalid control (or one with aria-invalid="true") gets the error border, so a page never styles it itself.
 * `mono` switches to the skin's monospace (code, paths, JSON).
 */
@Directive({
  selector: 'input[dsControl], select[dsControl], textarea[dsControl]',
  host: { class: 'ds-control', '[class.ds-control--mono]': 'mono()' },
})
export class ControlDirective {
  readonly mono = input(false, { transform: booleanAttribute })
}

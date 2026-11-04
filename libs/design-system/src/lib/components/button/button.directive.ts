import { Directive, input } from '@angular/core'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'icon' | 'icon-danger'

/**
 * dsButton — the one button of the settings pages.
 *
 * A directive on a native `<button>` (or `<a>`), so type="submit", disabled and click keep their native behaviour
 * and nothing is re-emitted. The look is the global `.ds-button` of styles/_primitives.scss.
 *
 * Variants: `primary` (accent fill), `secondary` (outline, default), `danger`, `ghost` (text button: back links,
 * "Add…"), `icon` / `icon-danger` (square, for × and +).
 *
 * @example
 * <button dsButton variant="primary" type="submit" [disabled]="form.invalid">Save</button>
 */
@Directive({
  selector: 'button[dsButton], a[dsButton]',
  host: { '[class]': "'ds-button ds-button--' + variant()" },
})
export class ButtonDirective {
  readonly variant = input<ButtonVariant>('secondary')
}

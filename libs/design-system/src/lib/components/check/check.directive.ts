import { Directive } from '@angular/core'

/** dsCheck — a checkbox or radio with its text: `<label dsCheck><input type="checkbox" /> Text</label>`. */
@Directive({
  selector: 'label[dsCheck]',
  host: { class: 'ds-check' },
})
export class CheckDirective {}

import { Directive } from '@angular/core'

/** Applies the shared compact settings-table treatment to a native table. */
@Directive({
  selector: 'table[dsTable]',
  host: { class: 'ds-table' },
})
export class TableDirective {}

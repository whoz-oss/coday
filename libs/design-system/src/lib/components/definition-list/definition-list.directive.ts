import { Directive } from '@angular/core'

/** dsDefinitionList — read-only label / value pairs: `<dl dsDefinitionList><dt>…</dt><dd>…</dd></dl>`. */
@Directive({
  selector: 'dl[dsDefinitionList]',
  host: { class: 'ds-dl' },
})
export class DefinitionListDirective {}

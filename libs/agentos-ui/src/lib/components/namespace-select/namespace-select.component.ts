import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'

/**
 * NamespaceSelectComponent — a small labelled namespace `<select>`.
 *
 * Presentational and reusable: it sits in the entity-list toolbar (via the `toolbar-start` slot) so
 * the namespace picker shares the search line. Emits `null` for the optional "All namespaces" entry.
 */
@Component({
  selector: 'agentos-namespace-select',
  imports: [],
  templateUrl: './namespace-select.component.html',
  styleUrl: './namespace-select.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceSelectComponent {
  readonly namespaces = input.required<Namespace[]>()
  readonly value = input<string | null>(null)
  readonly includeAllOption = input<boolean>(false)

  readonly valueChange = output<string | null>()

  protected onChange(event: Event): void {
    const raw = (event.target as HTMLSelectElement).value
    this.valueChange.emit(raw === '' ? null : raw)
  }
}

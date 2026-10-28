import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'

@Component({
  selector: 'agentos-table-filter',
  templateUrl: './table-filter.component.html',
  styleUrl: './table-filter.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TableFilterComponent {
  readonly query = input('')
  readonly label = input('Filter list')
  readonly placeholder = input('Filter…')
  readonly queryChange = output<string>()
}

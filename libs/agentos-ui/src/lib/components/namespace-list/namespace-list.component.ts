import { Component } from '@angular/core'

/**
 * NamespaceListComponent — lists available namespaces.
 *
 * TODO: inject NamespaceStateService once agentos-dataflow is available
 *   - display namespaces$ list
 *   - call namespaceState.selectNamespace(id) on click
 *   - navigate to /:namespaceId/cases on selection
 */
@Component({
  selector: 'agentos-namespace-list',
  standalone: true,
  imports: [],
  templateUrl: './namespace-list.component.html',
  styleUrl: './namespace-list.component.scss',
})
export class NamespaceListComponent {}

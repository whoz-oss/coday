import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * NamespaceItemComponent — presentational component for a single namespace row.
 *
 * Displays the namespace name and description, with a delete button (ds-icon-button).
 * Navigation on click and deletion are emitted upward — no direct service injection.
 */
@Component({
  selector: 'agentos-namespace-item',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  readonly namespace = input.required<Namespace>()

  readonly selected = output<Namespace>()
  readonly deleteRequested = output<Namespace>()

  protected onSelect(): void {
    this.selected.emit(this.namespace())
  }

  protected onDelete(): void {
    this.deleteRequested.emit(this.namespace())
  }
}

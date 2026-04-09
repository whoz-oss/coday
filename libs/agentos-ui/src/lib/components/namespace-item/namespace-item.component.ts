import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * NamespaceItemComponent — presentational component for a single namespace card.
 *
 * Displays the namespace name and description. All actions (edit, integrations,
 * delete) are grouped in a ds-kebab-menu and emitted upward — no direct service
 * injection.
 *
 * Delete uses a native confirm() dialog to prevent accidental deletions.
 */
@Component({
  selector: 'agentos-namespace-item',
  standalone: true,
  imports: [KebabMenuComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  @Input({ required: true }) namespace!: Namespace

  @Output() selected = new EventEmitter<Namespace>()
  @Output() editRequested = new EventEmitter<Namespace>()
  @Output() integrationsRequested = new EventEmitter<Namespace>()
  @Output() deleteRequested = new EventEmitter<Namespace>()

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit namespace', icon: 'edit' },
    { key: 'integrations', label: 'Manage integrations', icon: 'settings' },
    { key: 'delete', label: 'Delete namespace', icon: 'delete', variant: 'danger' },
  ]

  protected onSelect(): void {
    this.selected.emit(this.namespace)
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.editRequested.emit(this.namespace)
        break
      case 'integrations':
        this.integrationsRequested.emit(this.namespace)
        break
      case 'delete':
        if (confirm(`Delete namespace "${this.namespace.name}"?`)) {
          this.deleteRequested.emit(this.namespace)
        }
        break
    }
  }
}

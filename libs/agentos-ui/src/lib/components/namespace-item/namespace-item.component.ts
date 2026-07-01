import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * NamespaceItemComponent — presentational component for a single namespace card.
 *
 * Displays the namespace name and description. All actions (edit, integrations,
 * delete) are grouped in a ds-kebab-menu and emitted upward — no direct service
 * injection.
 *
 * Delete uses a signal-based two-step inline confirmation (same pattern as
 * PromptItemComponent) to avoid the synchronous, OnPush-incompatible confirm().
 */
@Component({
  selector: 'agentos-namespace-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  @Input({ required: true }) namespace!: Namespace

  @Output() selected = new EventEmitter<Namespace>()
  @Output() editRequested = new EventEmitter<Namespace>()
  @Output() integrationsRequested = new EventEmitter<Namespace>()
  @Output() aiProvidersRequested = new EventEmitter<Namespace>()
  @Output() aiModelsRequested = new EventEmitter<Namespace>()
  @Output() agentConfigsRequested = new EventEmitter<Namespace>()
  @Output() promptsRequested = new EventEmitter<Namespace>()
  @Output() deleteRequested = new EventEmitter<Namespace>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit namespace', icon: 'edit' },
    { key: 'integrations', label: 'Manage integrations', icon: 'settings' },
    { key: 'ai-providers', label: 'AI Providers', icon: 'smart_toy' },
    { key: 'ai-models', label: 'AI models', icon: 'model_training' },
    { key: 'agent-configs', label: 'Agent Configs', icon: 'support_agent' },
    { key: 'prompts', label: 'Prompts', icon: 'description' },
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
      case 'ai-providers':
        this.aiProvidersRequested.emit(this.namespace)
        break
      case 'ai-models':
        this.aiModelsRequested.emit(this.namespace)
        break
      case 'agent-configs':
        this.agentConfigsRequested.emit(this.namespace)
        break
      case 'prompts':
        this.promptsRequested.emit(this.namespace)
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.namespace)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

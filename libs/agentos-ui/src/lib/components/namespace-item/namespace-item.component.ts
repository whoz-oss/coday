import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { ActionCardChipsDirective, ActionCardComponent, ActionCardMenuItem } from '../action-card/action-card.component'

@Component({
  selector: 'agentos-namespace-item',
  imports: [ActionCardComponent, ActionCardChipsDirective, IconButtonComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  readonly namespace = input.required<Namespace>()

  readonly selected = output<Namespace>()
  readonly editRequested = output<Namespace>()
  readonly integrationsRequested = output<Namespace>()
  readonly aiProvidersRequested = output<Namespace>()
  readonly aiModelsRequested = output<Namespace>()
  readonly agentConfigsRequested = output<Namespace>()
  readonly promptsRequested = output<Namespace>()
  readonly deleteRequested = output<Namespace>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: ActionCardMenuItem[] = [
    { key: 'edit', label: 'Edit namespace' },
    { key: 'separator', label: '', variant: 'separator' },
    { key: 'delete', label: 'Delete namespace', variant: 'danger' },
  ]

  protected onSelect(): void {
    this.selected.emit(this.namespace())
  }
  protected onIntegrations(): void {
    this.integrationsRequested.emit(this.namespace())
  }
  protected onAiProviders(): void {
    this.aiProvidersRequested.emit(this.namespace())
  }
  protected onAiModels(): void {
    this.aiModelsRequested.emit(this.namespace())
  }
  protected onAgentConfigs(): void {
    this.agentConfigsRequested.emit(this.namespace())
  }
  protected onPrompts(): void {
    this.promptsRequested.emit(this.namespace())
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.editRequested.emit(this.namespace())
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.namespace())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

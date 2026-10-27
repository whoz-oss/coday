import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import {
  ActionCardActionsDirective,
  ActionCardComponent,
  ActionCardMenuItem,
} from '../action-card/action-card.component'

@Component({
  selector: 'agentos-namespace-item',
  imports: [ActionCardComponent, ActionCardActionsDirective, IconButtonComponent],
  templateUrl: './namespace-item.component.html',
  styleUrl: './namespace-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceItemComponent {
  readonly namespace = input.required<Namespace>()

  readonly selected = output<Namespace>()
  readonly integrationsRequested = output<Namespace>()
  readonly aiProvidersRequested = output<Namespace>()
  readonly aiModelsRequested = output<Namespace>()
  readonly agentConfigsRequested = output<Namespace>()
  readonly promptsRequested = output<Namespace>()
  readonly skillsRequested = output<Namespace>()
  readonly scheduledPromptsRequested = output<Namespace>()
  readonly userGroupsRequested = output<Namespace>()
  readonly authSettingsRequested = output<Namespace>()
  readonly toolInvokeRequested = output<Namespace>()
  readonly deleteRequested = output<Namespace>()

  protected readonly pendingDelete = signal(false)

  /**
   * Namespace settings (members, git, usage, edit, ...) live in the settings layout.
   * The kebab only keeps the destructive action.
   */
  protected readonly menuItems: ActionCardMenuItem[] = [{ key: 'delete', label: 'Delete namespace', variant: 'danger' }]

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
  protected onSkills(): void {
    this.skillsRequested.emit(this.namespace())
  }
  protected onScheduledPrompts(): void {
    this.scheduledPromptsRequested.emit(this.namespace())
  }
  protected onUserGroups(): void {
    this.userGroupsRequested.emit(this.namespace())
  }
  protected onAuthSettings(): void {
    this.authSettingsRequested.emit(this.namespace())
  }
  protected onToolInvoke(): void {
    this.toolInvokeRequested.emit(this.namespace())
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'integrations':
        this.onIntegrations()
        break
      case 'ai-providers':
        this.onAiProviders()
        break
      case 'ai-models':
        this.onAiModels()
        break
      case 'agent-configs':
        this.onAgentConfigs()
        break
      case 'prompts':
        this.onPrompts()
        break
      case 'scheduled-prompts':
        this.onScheduledPrompts()
        break
      case 'user-groups':
        this.onUserGroups()
        break
      case 'auth-settings':
        this.onAuthSettings()
        break
      case 'tool-invoke':
        this.onToolInvoke()
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

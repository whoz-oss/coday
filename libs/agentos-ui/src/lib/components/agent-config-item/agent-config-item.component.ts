import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AgentConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AgentConfigItemComponent — presentational component for a single agent config card.
 *
 * Displays the agent config name and optional description. Edit navigates to the
 * dedicated edit route; delete uses a two-step inline confirmation before emitting upward.
 */
@Component({
  selector: 'agentos-agent-config-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './agent-config-item.component.html',
  styleUrl: './agent-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) config!: AgentConfig
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<AgentConfig>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit agent config', icon: 'edit' },
    { key: 'inspect', label: 'Inspect definition', icon: 'search' },
    { key: 'delete', label: 'Delete agent config', icon: 'delete', variant: 'danger' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', this.config.id, 'edit'])
        break
      case 'inspect':
        this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', this.config.id, 'inspect'])
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

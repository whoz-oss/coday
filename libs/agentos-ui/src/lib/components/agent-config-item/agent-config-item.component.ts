import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AgentConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AgentConfigItemComponent — presentational component for a single agent config card.
 *
 * Displays the agent config name and optional description. Edit navigates to the
 * dedicated edit route; delete uses a two-step inline confirmation before emitting upward.
 *
 * When readOnly is true (platform-level configs shown in namespace context), all
 * mutation actions (edit, delete) are hidden.
 */
@Component({
  selector: 'agentos-agent-config-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './agent-config-item.component.html',
  styleUrl: './agent-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigItemComponent {
  private readonly router = inject(Router)

  readonly config = input.required<AgentConfig>()
  /**
   * namespaceId is required in namespace mode and must be omitted in platform mode.
   * When platformMode is true, routes navigate to /agentos/admin/agent-configs/...
   */
  readonly namespaceId = input<string | undefined>(undefined)
  /** Set to true for platform-level configs (no namespace scope). */
  readonly platformMode = input(false)
  /**
   * When true, edit and delete actions are hidden.
   * Used for platform-level configs displayed in a namespace context (read-only visibility).
   */
  readonly readOnly = input(false)

  readonly deleteRequested = output<AgentConfig>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit agent config', icon: 'edit' },
    { key: 'inspect', label: 'Inspect definition', icon: 'search' },
    { key: 'delete', label: 'Delete agent config', icon: 'delete', variant: 'danger' },
  ]

  protected readonly readOnlyMenuItems: KebabMenuItem[] = [
    { key: 'inspect', label: 'Inspect definition', icon: 'search' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'agent-configs', this.config().id, 'edit'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'agent-configs', this.config().id, 'edit'])
        }
        break
      case 'inspect':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'agent-configs', this.config().id, 'inspect'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'agent-configs', this.config().id, 'inspect'])
        }
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AgentConfig, AgentConfigExecutionModeEnum } from '@whoz-oss/agentos-api-client'
import { BlueprintDirective, IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

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
  imports: [BlueprintDirective, KebabMenuComponent, IconButtonComponent],
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

  /**
   * Badge metadata for the execution mode. Returns null for SIMPLE (no badge shown),
   * a label + mode string for ADVANCED and LOOP so the template can apply the right modifier.
   * Backward compat: if executionMode is absent but advancedExecution is true, show ADVANCED.
   */
  protected readonly executionModeBadge = computed(() => {
    const cfg = this.config()
    const mode =
      cfg.executionMode ??
      (cfg.advancedExecution ? AgentConfigExecutionModeEnum.ADVANCED : AgentConfigExecutionModeEnum.SIMPLE)
    if (mode === AgentConfigExecutionModeEnum.ADVANCED) return { mode, label: 'ADVANCED' }
    if (mode === AgentConfigExecutionModeEnum.LOOP) return { mode, label: 'LOOP' }
    return null
  })

  /**
   * True when the agent's execution mode is LOOP.
   * Determines whether the "Launch" entry appears in the kebab menu.
   */
  protected readonly isLoop = computed(() => {
    const cfg = this.config()
    return (
      cfg.executionMode === AgentConfigExecutionModeEnum.LOOP ||
      // Backward compat: no executionMode field but mode badge shows LOOP is impossible
      // via advancedExecution alone — this guard is just for safety.
      false
    )
  })

  protected get menuItems(): KebabMenuItem[] {
    const items: KebabMenuItem[] = [
      { key: 'edit', label: 'Edit agent config', icon: 'edit' },
      { key: 'inspect', label: 'Inspect definition', icon: 'search' },
    ]
    if (this.isLoop()) {
      items.push({ key: 'launch', label: 'Launch loop', icon: 'play_arrow' })
    }
    items.push({ key: 'delete', label: 'Delete agent config', icon: 'delete', variant: 'danger' })
    return items
  }

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
      case 'launch':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'agent-configs', this.config().id, 'launch'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'agent-configs', this.config().id, 'launch'])
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

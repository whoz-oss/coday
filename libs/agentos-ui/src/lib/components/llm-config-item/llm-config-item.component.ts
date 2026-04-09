import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { LlmConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * LlmConfigItemComponent — presentational component for a single LLM provider card.
 *
 * Displays the provider name and API type. Edit navigates to the dedicated edit route;
 * delete uses a two-step inline confirmation before emitting upward.
 */
@Component({
  selector: 'agentos-llm-config-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './llm-config-item.component.html',
  styleUrl: './llm-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LlmConfigItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) config!: LlmConfig
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<LlmConfig>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit provider', icon: 'edit' },
    { key: 'delete', label: 'Delete provider', icon: 'delete', variant: 'danger' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'llm-configs', this.config.id, 'edit'])
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

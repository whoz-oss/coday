import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AiProvider } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AiProviderItemComponent — presentational component for a single AI provider card.
 *
 * Displays the provider name and API type. Edit navigates to the dedicated edit route;
 * delete uses a two-step inline confirmation before emitting upward.
 */
@Component({
  selector: 'agentos-ai-provider-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-provider-item.component.html',
  styleUrl: './ai-provider-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiProviderItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) config!: AiProvider
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<AiProvider>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit provider', icon: 'edit' },
    { key: 'delete', label: 'Delete provider', icon: 'delete', variant: 'danger' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', this.config.id, 'edit'])
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

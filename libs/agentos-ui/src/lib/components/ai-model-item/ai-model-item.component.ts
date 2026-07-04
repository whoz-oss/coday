import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AiModel } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AiModelItemComponent — presentational component for a single AI model card.
 *
 * Displays the model display name, api name, and optional parameters (temperature,
 * maxTokens). Edit navigates to the dedicated edit route; delete uses a two-step
 * inline confirmation before emitting upward.
 */
@Component({
  selector: 'agentos-ai-model-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-model-item.component.html',
  styleUrl: './ai-model-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelItemComponent {
  private readonly router = inject(Router)

  readonly model = input.required<AiModel>()
  readonly namespaceId = input.required<string>()

  readonly deleteRequested = output<AiModel>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit model', icon: 'edit' },
    { key: 'delete', label: 'Delete model', icon: 'delete', variant: 'danger' },
  ]

  protected get displayTitle(): string {
    return this.model().alias ?? this.model().apiModelName
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId(), 'ai-models', this.model().id, 'edit'])
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.model())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

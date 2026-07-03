import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { AiModel } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AiModelItemComponent — presentational component for a single AI model card.
 *
 * Displays the model display name, api name, and optional parameters (temperature,
 * maxTokens). Delete uses a two-step inline confirmation before emitting upward.
 *
 * Edit is dispatched via `editRequested` when a container provides it; otherwise
 * falls back to navigating to `/:namespaceId/ai-models/:id/edit` (legacy behaviour,
 * kept for backward compatibility with existing namespace containers).
 */
@Component({
  selector: 'agentos-ai-model-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-model-item.component.html',
  styleUrl: './ai-model-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) model!: AiModel
  /** Required for the legacy navigation fallback when `editRequested` has no listeners. */
  @Input() namespaceId: string | undefined

  @Output() deleteRequested = new EventEmitter<AiModel>()
  @Output() editRequested = new EventEmitter<AiModel>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit model', icon: 'edit' },
    { key: 'delete', label: 'Delete model', icon: 'delete', variant: 'danger' },
  ]

  protected get displayTitle(): string {
    return this.model.alias ?? this.model.apiModelName
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        if (this.editRequested.observed) {
          this.editRequested.emit(this.model)
        } else if (this.namespaceId) {
          this.router.navigate(['/agentos', this.namespaceId, 'ai-models', this.model.id, 'edit'])
        }
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.model)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

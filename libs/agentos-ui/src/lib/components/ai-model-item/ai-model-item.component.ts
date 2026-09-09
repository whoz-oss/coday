import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core'
import { AiModel } from '@whoz-oss/agentos-api-client'
import { BlueprintDirective, IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * AiModelItemComponent — presentational component for a single AI model card.
 *
 * Displays the model display name, api name, and optional parameters (temperature,
 * maxTokens). Delete uses a two-step inline confirmation before emitting upward.
 *
 * Edit is dispatched via `editRequested` to the parent container.
 */
@Component({
  selector: 'agentos-ai-model-item',
  imports: [BlueprintDirective, KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-model-item.component.html',
  styleUrl: './ai-model-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelItemComponent {
  readonly model = input.required<AiModel>()
  readonly namespaceId = input<string | undefined>(undefined)
  /**
   * When true, edit and delete actions are hidden.
   * Used for platform-level models displayed in a namespace context (read-only visibility).
   */
  readonly readOnly = input(false)

  readonly deleteRequested = output<AiModel>()
  readonly editRequested = output<AiModel>()

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
        this.editRequested.emit(this.model())
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

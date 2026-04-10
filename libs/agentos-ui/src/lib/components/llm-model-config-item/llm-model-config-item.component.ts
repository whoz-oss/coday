import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { LlmModelConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * LlmModelConfigItemComponent — presentational component for a single LLM model card.
 *
 * Displays the model display name, api name, and optional parameters (temperature,
 * maxTokens). Edit navigates to the dedicated edit route; delete uses a two-step
 * inline confirmation before emitting upward.
 */
@Component({
  selector: 'agentos-llm-model-config-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './llm-model-config-item.component.html',
  styleUrl: './llm-model-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LlmModelConfigItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) model!: LlmModelConfig
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<LlmModelConfig>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit model', icon: 'edit' },
    { key: 'delete', label: 'Delete model', icon: 'delete', variant: 'danger' },
  ]

  protected get displayTitle(): string {
    return this.model.alias ?? this.model.apiName
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'llm-models', this.model.id, 'edit'])
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

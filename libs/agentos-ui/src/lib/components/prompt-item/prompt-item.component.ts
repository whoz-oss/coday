import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { Prompt } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * PromptItemComponent — presentational component for a single prompt card.
 *
 * Displays the prompt name and optional description. Edit navigates to the
 * dedicated edit route; delete uses a two-step inline confirmation before
 * emitting upward to the parent list component.
 *
 * When readOnly is true (platform-level prompts shown in namespace context),
 * all mutation actions (edit, delete) are hidden.
 */
@Component({
  selector: 'agentos-prompt-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './prompt-item.component.html',
  styleUrl: './prompt-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromptItemComponent {
  private readonly router = inject(Router)

  readonly prompt = input.required<Prompt>()
  readonly namespaceId = input<string | undefined>(undefined)
  /** When true, edit navigates to the admin platform route instead of the namespace route. */
  readonly platformMode = input(false)
  /**
   * When true, edit and delete actions are hidden.
   * Used for platform-level prompts displayed in a namespace context (read-only visibility).
   */
  readonly readOnly = input(false)

  readonly deleteRequested = output<Prompt>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit prompt', icon: 'edit' },
    { key: 'delete', label: 'Delete prompt', icon: 'delete', variant: 'danger' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'prompts', this.prompt().id, 'edit'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'prompts', this.prompt().id, 'edit'])
        }
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.prompt())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}

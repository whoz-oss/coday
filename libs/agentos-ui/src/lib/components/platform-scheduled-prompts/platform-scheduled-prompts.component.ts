import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { ScheduledPrompt } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { ScheduledPromptItemComponent } from '../scheduled-prompt-item/scheduled-prompt-item.component'

/**
 * PlatformScheduledPromptsComponent — list view for platform-level scheduled prompts.
 *
 * Loaded at /agentos/admin/scheduled-prompts. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform scheduled prompts have no namespaceId and no userId — they are shared
 * across all namespaces.
 *
 * State is a single signal, loaded once. Mutations (enable/disable) patch the
 * signal locally from the updated entity returned by the API — no full refetch,
 * no risk of stale reads from a second, independently-resolving subscription.
 */
@Component({
  selector: 'agentos-platform-scheduled-prompts',
  imports: [EntityListComponent, ScheduledPromptItemComponent, IconButtonComponent],
  templateUrl: './platform-scheduled-prompts.component.html',
  styleUrl: './platform-scheduled-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformScheduledPromptsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)

  /** Single source of truth for platform scheduled prompts, loaded once in the constructor. */
  private readonly prompts = signal<ScheduledPrompt[]>([])

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly promptItems = computed<EntityListItem[]>(() =>
    this.prompts().map(
      (d): EntityListItem => ({
        id: d.id ?? '',
        name: d.name,
        description: d.description,
        badges: [
          {
            label: d.enabled ? 'Enabled' : 'Disabled',
            variant: d.enabled ? 'success' : 'warning',
          },
        ],
      })
    )
  )

  constructor() {
    this.state
      .listPlatform()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((defs) => this.prompts.set(defs))
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'scheduled-prompts', 'new'])
  }

  protected enablePrompt(definition: ScheduledPrompt): void {
    this.state
      .enable(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((updated) => this.patchPrompt(updated))
  }

  protected disablePrompt(definition: ScheduledPrompt): void {
    this.state
      .disable(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((updated) => this.patchPrompt(updated))
  }

  protected deletePrompt(definition: ScheduledPrompt): void {
    const id = definition.id ?? ''
    this.state
      .delete(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.prompts.update((list) => list.filter((p) => p.id !== id)))
  }

  protected resolvePrompt(id: string): ScheduledPrompt | null {
    return this.prompts().find((p) => p.id === id) ?? null
  }

  private patchPrompt(updated: ScheduledPrompt): void {
    this.prompts.update((list) => list.map((p) => (p.id === updated.id ? updated : p)))
  }
}

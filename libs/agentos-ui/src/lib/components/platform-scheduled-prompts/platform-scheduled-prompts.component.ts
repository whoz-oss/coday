import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { DAY_OF_WEEK_FULL_LABELS, ScheduledPrompt, SchedulerUnit } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformScheduledPromptsComponent — table view for platform-level scheduled prompts.
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
  imports: [SectionHeaderComponent, TableDirective, TagComponent, TableFilterComponent, RowDeleteActionComponent],
  templateUrl: './platform-scheduled-prompts.component.html',
  styleUrl: './platform-scheduled-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformScheduledPromptsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)

  /** Single source of truth for platform scheduled prompts, loaded once in the constructor. */
  protected readonly prompts = signal<ScheduledPrompt[]>([])
  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly rowFilter = createRowFilter(this.prompts, (prompt) =>
    [prompt.name, prompt.description, this.scheduleLabel(prompt), prompt.enabled ? 'Enabled' : 'Disabled']
      .filter(Boolean)
      .join(' ')
  )

  constructor() {
    this.state
      .listPlatform()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (defs) => {
          this.prompts.set(defs)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  /** e.g. "Every week (Monday, Wednesday) at 09:00 UTC". */
  protected scheduleLabel(prompt: ScheduledPrompt): string {
    const { recurrence } = prompt
    const unit = recurrence.unit === SchedulerUnit.WEEK ? 'week' : 'month'
    const days =
      recurrence.unit === SchedulerUnit.WEEK && recurrence.days?.length
        ? ` (${recurrence.days.map((day) => DAY_OF_WEEK_FULL_LABELS[day] ?? day).join(', ')})`
        : ''
    return `Every ${unit}${days} at ${recurrence.timeUtc} UTC`
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'scheduled-prompts', 'new'])
  }

  protected edit(prompt: ScheduledPrompt): void {
    if (!prompt.id) return
    void this.router.navigate(['/agentos', 'admin', 'scheduled-prompts', prompt.id, 'edit'])
  }

  /** Explicit target state, derived from the current one — never a blind flip. */
  protected toggle(prompt: ScheduledPrompt): void {
    const request = prompt.enabled ? this.state.disable(prompt.id ?? '') : this.state.enable(prompt.id ?? '')
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((updated) => this.patchPrompt(updated))
  }

  protected confirmDelete(prompt: ScheduledPrompt): void {
    if (!prompt.id) return
    const id = prompt.id
    this.deletion.execute(id, {
      request$: this.state.delete(id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.prompts.update((list) => list.filter((p) => p.id !== id)),
      errorMessage: `Deleting "${prompt.name}" failed. Try again.`,
    })
  }

  private patchPrompt(updated: ScheduledPrompt): void {
    this.prompts.update((list) => list.map((p) => (p.id === updated.id ? updated : p)))
  }
}

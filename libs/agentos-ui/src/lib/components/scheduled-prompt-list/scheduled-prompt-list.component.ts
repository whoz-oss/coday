import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { DAY_OF_WEEK_FULL_LABELS, ScheduledPrompt, SchedulerUnit } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { catchError, forkJoin, of } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
interface ScheduledPromptRow {
  key: string
  prompt: ScheduledPrompt
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-scheduled-prompt-list',
  imports: [SectionHeaderComponent, TableDirective, TagComponent],
  templateUrl: './scheduled-prompt-list.component.html',
  styleUrl: './scheduled-prompt-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduledPromptListComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)
  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly rows = signal<ScheduledPromptRow[]>([])
  protected readonly isLoading = signal(true)
  constructor() {
    forkJoin({
      platform: this.state.listPlatform().pipe(catchError(() => of([] as ScheduledPrompt[]))),
      namespace: this.state.listByNamespace(this.namespaceId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ platform, namespace }) => {
          this.rows.set([
            ...platform.map((prompt, index) => ({
              key: `platform:${prompt.id ?? index}`,
              prompt,
              scope: 'platform' as const,
            })),
            ...namespace.map((prompt, index) => ({
              key: `namespace:${prompt.id ?? index}`,
              prompt,
              scope: 'namespace' as const,
            })),
          ])
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }
  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'scheduled-prompts', 'new'])
  }
  protected edit(row: ScheduledPromptRow): void {
    if (row.scope === 'platform' || !row.prompt.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'scheduled-prompts', row.prompt.id, 'edit'])
  }
  protected toggle(row: ScheduledPromptRow): void {
    if (row.scope === 'platform') return
    const request = row.prompt.enabled
      ? this.state.disable(row.prompt.id ?? '')
      : this.state.enable(row.prompt.id ?? '')
    request
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((updated) =>
        this.rows.update((rows) =>
          rows.map((current) => (current.key === row.key ? { ...current, prompt: updated } : current))
        )
      )
  }
  protected delete(row: ScheduledPromptRow): void {
    if (row.scope === 'platform' || !row.prompt.id || !confirm(`Delete scheduled prompt “${row.prompt.name}”?`)) return
    this.state
      .delete(row.prompt.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.rows.update((rows) => rows.filter((current) => current.key !== row.key)))
  }
  protected scheduleLabel(prompt: ScheduledPrompt): string {
    const recurrence = prompt.recurrence
    const unit = recurrence.unit === SchedulerUnit.WEEK ? 'week' : 'month'
    const days =
      recurrence.unit === SchedulerUnit.WEEK && recurrence.days?.length
        ? ` (${recurrence.days.map((day) => DAY_OF_WEEK_FULL_LABELS[day] ?? day).join(', ')})`
        : ''
    return `Every ${unit}${days} at ${recurrence.timeUtc} UTC`
  }
}

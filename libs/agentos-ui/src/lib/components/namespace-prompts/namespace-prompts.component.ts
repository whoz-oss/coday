import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Prompt } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, of, switchMap } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'
interface PromptRow {
  key: string
  prompt: Prompt
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-namespace-prompts',
  imports: [
    ButtonDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './namespace-prompts.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespacePromptsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptState = inject(PromptStateService)
  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly rows = signal<PromptRow[]>([])
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.prompt.name, row.prompt.description, row.scope].filter(Boolean).join(' ')
  )
  private readonly allPrompts$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return forkJoin({
        platform: this.promptState.listPlatform().pipe(catchError(() => of([] as Prompt[]))),
        namespace: this.promptState.listByNamespace(this.namespaceId),
      })
    })
  )
  constructor() {
    this.allPrompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
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
    void this.router.navigate(['/agentos', this.namespaceId, 'prompts', 'new'])
  }
  protected edit(row: PromptRow): void {
    if (row.scope === 'platform' || !row.prompt.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'prompts', row.prompt.id, 'edit'])
  }
  protected confirmDelete(row: PromptRow): void {
    if (row.scope === 'platform' || !row.prompt.id) return
    this.deletion.execute(row.key, {
      request$: this.promptState.delete(row.prompt.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${row.prompt.name}" failed. Try again.`,
    })
  }
}

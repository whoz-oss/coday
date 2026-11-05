import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import {
  AiModel,
  AiModelControllerService,
  AiProvider,
  AiProviderControllerService,
} from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, combineLatest, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

interface AiModelRow {
  key: string
  model: AiModel
  title: string
  providerName: string
}

/**
 * PlatformAiModelsComponent — table view for platform-level AI models.
 *
 * Loaded at /agentos/admin/ai-models. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform models have namespaceId IS NULL. Their parent platform AI provider is loaded in
 * parallel to resolve the provider column (falls back to the provider id).
 */
@Component({
  selector: 'agentos-platform-ai-models',
  imports: [ButtonDirective, SectionHeaderComponent, TableDirective, TableFilterComponent, RowDeleteActionComponent],
  templateUrl: './platform-ai-models.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAiModelsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiModelController = inject(AiModelControllerService)
  private readonly aiProviderController = inject(AiProviderControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly rows = signal<AiModelRow[]>([])
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.title, row.model.apiModelName, row.providerName].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          // Providers are scoped to platform level (namespaceId='none') so provider names
          // resolve correctly without falling back to UUIDs.
          return combineLatest([
            this.aiModelController.listPlatformLevelAiModel(),
            this.aiProviderController.listAiProvider('none'),
          ])
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: ([models, providers]) => {
          const providerNames = new Map(providers.map((p: AiProvider) => [p.id ?? '', p.name]))
          this.rows.set(
            models.map((model: AiModel, index) => ({
              key: model.id ?? `model:${index}`,
              model,
              title: model.alias ?? model.apiModelName,
              providerName: providerNames.get(model.aiProviderId) ?? model.aiProviderId,
            }))
          )
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'ai-models', 'new'])
  }

  protected edit(row: AiModelRow): void {
    if (!row.model.id) return
    void this.router.navigate(['/agentos', 'admin', 'ai-models', row.model.id, 'edit'])
  }

  protected confirmDelete(row: AiModelRow): void {
    if (!row.model.id) return
    this.deletion.execute(row.key, {
      request$: this.aiModelController.deleteAiModel(row.model.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${row.title}" failed. Try again.`,
    })
  }
}

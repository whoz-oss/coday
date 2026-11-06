import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AiModel,
  AiProvider,
  AiProviderControllerService,
  AiModelControllerService,
} from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, Observable, of, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

interface AiModelRow {
  key: string
  model: AiModel
  providerName: string
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-namespace-ai-models',
  imports: [
    ButtonDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './namespace-ai-models.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceAiModelsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiModelController = inject(AiModelControllerService)
  private readonly aiProviderController = inject(AiProviderControllerService)
  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  private readonly refresh$ = new BehaviorSubject<void>(undefined)
  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly rows = signal<AiModelRow[]>([])
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.model.alias, row.model.apiModelName, row.providerName, row.scope].filter(Boolean).join(' ')
  )
  private readonly data$: Observable<{
    namespace: AiModel[]
    platform: AiModel[]
    providers: AiProvider[]
    referencedProviders: AiProvider[]
  }> = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return forkJoin({
        namespace: this.aiModelController.listByNamespaceIdAiModel(this.namespaceId),
        platform: this.aiModelController.listPlatformLevelAiModel().pipe(catchError(() => of([] as AiModel[]))),
        providers: this.aiProviderController.listAiProvider(this.namespaceId),
      }).pipe(
        switchMap(({ namespace, platform, providers }) => {
          const providerIds = [...new Set([...platform, ...namespace].map((model) => model.aiProviderId))]
          return forkJoin({
            namespace: of(namespace),
            platform: of(platform),
            providers: of(providers),
            referencedProviders: this.aiProviderController
              .getByIdsAiProvider({ ids: providerIds, withRemoved: false })
              .pipe(catchError(() => of([] as AiProvider[]))),
          })
        })
      )
    })
  )
  constructor() {
    this.data$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ namespace, platform, providers, referencedProviders }) => {
        const names = new Map(
          [...referencedProviders, ...providers].map((provider) => [provider.id ?? '', provider.name])
        )
        this.rows.set([
          ...platform.map((model, index) => ({
            key: `platform:${model.id ?? index}`,
            model,
            providerName: names.get(model.aiProviderId) ?? model.aiProviderId,
            scope: 'platform' as const,
          })),
          ...namespace.map((model, index) => ({
            key: `namespace:${model.id ?? index}`,
            model,
            providerName: names.get(model.aiProviderId) ?? model.aiProviderId,
            scope: 'namespace' as const,
          })),
        ])
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }
  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'ai-models', 'new'])
  }
  protected edit(row: AiModelRow): void {
    if (row.scope === 'platform' || !row.model.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'ai-models', row.model.id, 'edit'])
  }
  protected title(row: AiModelRow): string {
    return row.model.alias ?? row.model.apiModelName
  }
  protected confirmDelete(row: AiModelRow): void {
    if (row.scope === 'platform' || !row.model.id) return
    this.deletion.execute(row.key, {
      request$: this.aiModelController.deleteAiModel(row.model.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${this.title(row)}" failed. Try again.`,
    })
  }
}

import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AiModel,
  AiProvider,
  AiProviderControllerService,
  AiModelControllerService,
} from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, Observable, of, switchMap } from 'rxjs'

interface AiModelRow {
  key: string
  model: AiModel
  providerName: string
  scope: 'platform' | 'namespace'
}
@Component({
  selector: 'agentos-namespace-ai-models',
  imports: [SectionHeaderComponent, TableDirective, TagComponent],
  templateUrl: './namespace-ai-models.component.html',
  styleUrl: './namespace-ai-models.component.scss',
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
  protected readonly rows = signal<AiModelRow[]>([])
  private readonly data$: Observable<{ namespace: AiModel[]; platform: AiModel[]; providers: AiProvider[] }> =
    this.refresh$.pipe(
      switchMap(() => {
        this.isLoading.set(true)
        return forkJoin({
          namespace: this.aiModelController.listByNamespaceIdAiModel(this.namespaceId),
          platform: this.aiModelController.listPlatformLevelAiModel().pipe(catchError(() => of([] as AiModel[]))),
          providers: this.aiProviderController.listAiProvider(this.namespaceId),
        })
      })
    )
  constructor() {
    this.data$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ namespace, platform, providers }) => {
        const names = new Map(providers.map((provider) => [provider.id ?? '', provider.name]))
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
  protected delete(row: AiModelRow): void {
    if (
      row.scope === 'platform' ||
      !row.model.id ||
      !confirm(`Delete AI model “${row.model.alias ?? row.model.apiModelName}”?`)
    )
      return
    this.aiModelController
      .deleteAiModel(row.model.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }
}

import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AiModel,
  AiProvider,
  AiProviderControllerService,
  AiModelControllerService,
} from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, forkJoin, Observable, switchMap, map } from 'rxjs'
import { AiModelItemComponent } from '../ai-model-item/ai-model-item.component'

const PLATFORM_GROUP_KEY = '__platform__'
const PLATFORM_GROUP_LABEL = 'Platform (read-only)'

/**
 * NamespaceLlmModelsComponent — list view for AI models of a namespace.
 *
 * Loaded at /:namespaceId/ai-models. Responsibilities:
 * - Load namespace-level AND platform-level AI models in parallel
 * - Load AiProvider providers to resolve group labels for namespace models
 * - Display namespace models grouped by provider name, platform models in a dedicated group
 * - Platform-level models are displayed read-only (no edit/delete actions)
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/ai-models/new)
 * - Deletion with inline confirmation (delegated to AiModelItemComponent)
 *
 * Create and edit are handled by AiModelFormComponent on dedicated routes.
 * The namespaceId is fixed at creation time and cannot be changed afterwards.
 */
@Component({
  selector: 'agentos-namespace-ai-models',
  imports: [AsyncPipe, EntityListComponent, AiModelItemComponent, IconButtonComponent],
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

  /**
   * Fetches namespace models, platform models, and providers in parallel.
   * Providers are needed to resolve group labels for namespace-level models.
   */
  private readonly data$: Observable<{ namespace: AiModel[]; platform: AiModel[]; providers: AiProvider[] }> =
    this.refresh$.pipe(
      switchMap(() =>
        forkJoin({
          namespace: this.aiModelController.listByNamespaceIdAiModel(this.namespaceId),
          platform: this.aiModelController.listPlatformLevelAiModel(),
          providers: this.aiProviderController.listAiProvider(this.namespaceId),
        })
      )
    )

  /** Mapped to EntityListItem[] with groupKey/groupLabel for ds-entity-list grouping.
   * Platform models appear first in their own group; namespace models follow, grouped by provider. */
  protected readonly modelItems$ = this.data$.pipe(
    map(({ namespace, platform, providers }) => {
      const providerNames = new Map(providers.map((p: AiProvider) => [p.id ?? '', p.name]))
      return [
        ...platform.map(
          (m: AiModel): EntityListItem => ({
            id: m.id ?? '',
            name: m.alias ?? m.apiModelName,
            description: m.apiModelName,
            groupKey: PLATFORM_GROUP_KEY,
            groupLabel: PLATFORM_GROUP_LABEL,
          })
        ),
        ...namespace.map(
          (m: AiModel): EntityListItem => ({
            id: m.id ?? '',
            name: m.alias ?? m.apiModelName,
            description: m.apiModelName,
            groupKey: m.aiProviderId,
            groupLabel: providerNames.get(m.aiProviderId) ?? m.aiProviderId,
          })
        ),
      ]
    })
  )

  /** Full model objects indexed by id — used to resolve itemTemplate events. */
  private namespaceModelsById = new Map<string, AiModel>()
  private platformModelsById = new Map<string, AiModel>()

  constructor() {
    this.data$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ namespace, platform }) => {
      this.namespaceModelsById = new Map(namespace.map((m: AiModel) => [m.id ?? '', m]))
      this.platformModelsById = new Map(platform.map((m: AiModel) => [m.id ?? '', m]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'ai-models', 'new'])
  }

  protected deleteModel(model: AiModel): void {
    this.aiModelController
      .deleteAiModel(model.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveModel(id: string): AiModel | null {
    return this.namespaceModelsById.get(id) ?? this.platformModelsById.get(id) ?? null
  }

  protected isPlatformModel(id: string): boolean {
    return this.platformModelsById.has(id)
  }
}

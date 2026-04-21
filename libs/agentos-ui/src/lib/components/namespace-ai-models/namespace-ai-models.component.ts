import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AiModel, AiProviderControllerService, AiModelControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, combineLatest, map, switchMap } from 'rxjs'
import { AiModelItemComponent } from '../ai-model-item/ai-model-item.component'

/**
 * NamespaceLlmModelsComponent — list view for AI models of a namespace.
 *
 * Loaded at /:namespaceId/ai-models. Responsibilities:
 * - Load all AiModel for the namespace in a single call
 * - Load AiProvider providers in parallel to resolve group labels
 * - Display models grouped by provider name via ds-entity-list grouping
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/ai-models/new)
 * - Deletion with inline confirmation (delegated to AiModelItemComponent)
 *
 * Create and edit are handled by AiModelFormComponent on dedicated routes.
 * The namespaceId is fixed at creation time and cannot be changed afterwards.
 */
@Component({
  selector: 'agentos-namespace-ai-models',
  standalone: true,
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
   * Raw models, kept for delete lookups.
   * Loaded in parallel with providers to resolve group labels without sequential calls.
   */
  private readonly data$ = this.refresh$.pipe(
    switchMap(() =>
      combineLatest([
        this.aiModelController.listByNamespaceIdAiModel(this.namespaceId),
        this.aiProviderController.listByParentAiProvider(this.namespaceId),
      ])
    )
  )

  /** Mapped to EntityListItem[] with groupKey/groupLabel for ds-entity-list grouping. */
  protected readonly modelItems$ = this.data$.pipe(
    map(([models, providers]) => {
      const providerNames = new Map(providers.map((p) => [p.id ?? '', p.name]))
      return models.map(
        (m): EntityListItem => ({
          id: m.id ?? '',
          name: m.alias ?? m.apiModelName,
          description: m.apiModelName,
          groupKey: m.aiProviderId,
          groupLabel: providerNames.get(m.aiProviderId) ?? m.aiProviderId,
        })
      )
    })
  )

  /** Full model objects indexed by id — used to resolve itemTemplate events. */
  private modelsById = new Map<string, AiModel>()

  constructor() {
    this.data$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(([models]) => {
      this.modelsById = new Map(models.map((m) => [m.id ?? '', m]))
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
    return this.modelsById.get(id) ?? null
  }
}

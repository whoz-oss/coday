import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  LlmModelConfig,
  LlmConfigControllerService,
  LlmModelConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, combineLatest, map, switchMap } from 'rxjs'
import { LlmModelConfigItemComponent } from '../llm-model-config-item/llm-model-config-item.component'

/**
 * NamespaceLlmModelsComponent — list view for LLM model configs of a namespace.
 *
 * Loaded at /:namespaceId/llm-models. Responsibilities:
 * - Load all LlmModelConfig for the namespace in a single call
 * - Load LlmConfig providers in parallel to resolve group labels
 * - Display models grouped by provider name via ds-entity-list grouping
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/llm-models/new)
 * - Deletion with inline confirmation (delegated to LlmModelConfigItemComponent)
 *
 * Create and edit are handled by LlmModelConfigFormComponent on dedicated routes.
 * The namespaceId is fixed at creation time and cannot be changed afterwards.
 */
@Component({
  selector: 'agentos-namespace-llm-models',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, LlmModelConfigItemComponent],
  templateUrl: './namespace-llm-models.component.html',
  styleUrl: './namespace-llm-models.component.scss',
})
export class NamespaceLlmModelsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly llmModelConfigController = inject(LlmModelConfigControllerService)
  private readonly llmConfigController = inject(LlmConfigControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /**
   * Raw models, kept for delete lookups.
   * Loaded in parallel with providers to resolve group labels without sequential calls.
   */
  private readonly data$ = this.refresh$.pipe(
    switchMap(() =>
      combineLatest([
        this.llmModelConfigController.listByNamespaceIdLlmModelConfig(this.namespaceId),
        this.llmConfigController.listByParentLlmConfig(this.namespaceId),
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
          name: m.displayName ?? m.alias ?? m.apiName,
          description: m.apiName,
          groupKey: m.llmConfigId,
          groupLabel: providerNames.get(m.llmConfigId) ?? m.llmConfigId,
        })
      )
    })
  )

  /** Full model objects indexed by id — used to resolve itemTemplate events. */
  private modelsById = new Map<string, LlmModelConfig>()

  constructor() {
    this.data$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(([models]) => {
      this.modelsById = new Map(models.map((m) => [m.id ?? '', m]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'llm-models', 'new'])
  }

  protected deleteModel(model: LlmModelConfig): void {
    this.llmModelConfigController
      .deleteLlmModelConfig(model.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveModel(id: string): LlmModelConfig | null {
    return this.modelsById.get(id) ?? null
  }
}

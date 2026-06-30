import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import {
  AiModel,
  AiProvider,
  AiModelControllerService,
  AiProviderControllerService,
} from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, combineLatest, map, Observable, switchMap } from 'rxjs'
import { AiModelItemComponent } from '../ai-model-item/ai-model-item.component'

/**
 * PlatformAiModelsComponent — list view for platform-level AI models.
 *
 * Loaded at /agentos/admin/ai-models. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform models have namespaceId IS NULL. They are grouped by their parent
 * platform AI provider (loaded in parallel to resolve group labels).
 *
 * Edit navigates to the existing AiModelFormComponent via 'none' as namespaceId
 * sentinel (same pattern as platform AI providers).
 */
@Component({
  selector: 'agentos-platform-ai-models',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, AiModelItemComponent],
  templateUrl: './platform-ai-models.component.html',
  styleUrl: './platform-ai-models.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAiModelsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiModelController = inject(AiModelControllerService)
  private readonly aiProviderController = inject(AiProviderControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /**
   * Raw models and their parent providers loaded in parallel.
   * Providers are scoped to platform level (namespaceId='none') so group labels
   * resolve correctly without falling back to UUIDs.
   */
  private readonly data$: Observable<[AiModel[], AiProvider[]]> = this.refresh$.pipe(
    switchMap(() =>
      combineLatest([
        this.aiModelController.listPlatformLevelAiModel(),
        this.aiProviderController.listAiProvider('none'),
      ])
    )
  )

  /** Mapped to EntityListItem[] with groupKey/groupLabel for ds-entity-list grouping. */
  protected readonly modelItems$ = this.data$.pipe(
    map(([models, providers]) => {
      const providerNames = new Map(providers.map((p: AiProvider) => [p.id ?? '', p.name]))
      return models.map(
        (m: AiModel): EntityListItem => ({
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
      this.modelsById = new Map(models.map((m: AiModel) => [m.id ?? '', m]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    // namespaceId='none' is the sentinel for namespaceId IS NULL (platform scope).
    // The form's navigateBack() will return to /agentos/none/ai-models.
    this.router.navigate(['/agentos', 'none', 'ai-models', 'new'])
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

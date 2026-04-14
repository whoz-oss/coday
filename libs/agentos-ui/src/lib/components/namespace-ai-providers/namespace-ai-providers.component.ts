import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AiProvider, AiProviderControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { AiProviderItemComponent } from '../ai-provider-item/ai-provider-item.component'

/**
 * NamespaceAiProvidersComponent — list view for LLM providers of a namespace.
 *
 * Loaded at /:namespaceId/ai-providers. Responsibilities:
 * - Load and display the list of AiProvider via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/ai-providers/new)
 * - Deletion with inline confirmation (delegated to AiProviderItemComponent)
 *
 * Create and edit are handled by AiProviderFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-ai-providers',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, AiProviderItemComponent],
  templateUrl: './namespace-ai-providers.component.html',
  styleUrl: './namespace-ai-providers.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceAiProvidersComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiProviderController = inject(AiProviderControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.aiProviderController.listByParentAiProvider(this.namespaceId))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly configItems$ = this.configs$.pipe(
    map((configs) =>
      configs.map(
        (c): EntityListItem => ({
          id: c.id ?? '',
          name: c.name,
          description: c.apiType,
        })
      )
    )
  )

  /** Full config objects indexed by id — used to resolve itemTemplate events. */
  private configsById = new Map<string, AiProvider>()

  constructor() {
    this.configs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((configs) => {
      this.configsById = new Map(configs.map((c) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'ai-providers', 'new'])
  }

  protected deleteConfig(config: AiProvider): void {
    this.aiProviderController
      .deleteAiProvider(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveConfig(id: string): AiProvider | null {
    return this.configsById.get(id) ?? null
  }
}

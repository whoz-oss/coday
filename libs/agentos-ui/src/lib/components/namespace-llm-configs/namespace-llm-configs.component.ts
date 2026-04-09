import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { LlmConfig, LlmConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { LlmConfigItemComponent } from '../llm-config-item/llm-config-item.component'

/**
 * NamespaceLlmConfigsComponent — list view for LLM providers of a namespace.
 *
 * Loaded at /:namespaceId/llm-configs. Responsibilities:
 * - Load and display the list of LlmConfig via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/llm-configs/new)
 * - Deletion with inline confirmation (delegated to LlmConfigItemComponent)
 *
 * Create and edit are handled by LlmConfigFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-llm-configs',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, LlmConfigItemComponent],
  templateUrl: './namespace-llm-configs.component.html',
  styleUrl: './namespace-llm-configs.component.scss',
})
export class NamespaceLlmConfigsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly llmConfigController = inject(LlmConfigControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.llmConfigController.listByParentLlmConfig(this.namespaceId))
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
  private configsById = new Map<string, LlmConfig>()

  constructor() {
    this.configs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((configs) => {
      this.configsById = new Map(configs.map((c) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'llm-configs', 'new'])
  }

  protected deleteConfig(config: LlmConfig): void {
    this.llmConfigController
      .deleteLlmConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveConfig(id: string): LlmConfig | null {
    return this.configsById.get(id) ?? null
  }
}

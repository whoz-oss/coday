import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Prompt, PromptControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { PromptItemComponent } from '../prompt-item/prompt-item.component'

/**
 * NamespacePromptsComponent — list view for prompts of a namespace.
 *
 * Loaded at /:namespaceId/prompts. Responsibilities:
 * - Load and display the list of Prompt via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/prompts/new)
 * - Deletion with inline two-step confirmation (delegated to PromptItemComponent)
 *
 * Create and edit are handled by PromptFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-prompts',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, PromptItemComponent],
  templateUrl: './namespace-prompts.component.html',
  styleUrl: './namespace-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespacePromptsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptController = inject(PromptControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw prompts, kept for delete lookups. */
  private readonly prompts$ = this.refresh$.pipe(
    switchMap(() => this.promptController.listByNamespacePrompt(this.namespaceId))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly promptItems$ = this.prompts$.pipe(
    map((prompts) =>
      prompts.map(
        (p: Prompt): EntityListItem => ({
          id: p.id ?? '',
          name: p.name,
          description: p.description,
        })
      )
    )
  )

  /** Full prompt objects indexed by id — used to resolve itemTemplate events. */
  private promptsById = new Map<string, Prompt>()

  constructor() {
    this.prompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((prompts: Prompt[]) => {
      this.promptsById = new Map(prompts.map((p: Prompt) => [p.id ?? '', p]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'prompts', 'new'])
  }

  protected deletePrompt(prompt: Prompt): void {
    this.promptController
      .deletePrompt(prompt.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolvePrompt(id: string): Prompt | null {
    return this.promptsById.get(id) ?? null
  }
}

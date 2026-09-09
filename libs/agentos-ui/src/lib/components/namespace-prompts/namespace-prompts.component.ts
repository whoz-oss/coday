import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Prompt } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, switchMap } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { PromptItemComponent } from '../prompt-item/prompt-item.component'

const GROUP_PLATFORM = 'platform'
const GROUP_NAMESPACE = 'namespace'

/**
 * NamespacePromptsComponent — list view for prompts of a namespace.
 *
 * Loaded at /:namespaceId/prompts. Responsibilities:
 * - Load and display namespace-level AND platform-level prompts
 * - Merge both levels into a grouped ds-entity-list (platform first, then namespace)
 * - Platform-level prompts are displayed read-only (no edit/delete actions)
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/prompts/new)
 * - Deletion with inline two-step confirmation (delegated to PromptItemComponent)
 *
 * Create and edit are handled by PromptFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-prompts',
  imports: [AsyncPipe, EntityListComponent, PromptItemComponent],
  templateUrl: './namespace-prompts.component.html',
  styleUrl: './namespace-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespacePromptsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptState = inject(PromptStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Fetches both namespace-level and platform-level prompts in parallel. */
  private readonly allPrompts$ = this.refresh$.pipe(
    switchMap(() =>
      forkJoin({
        platform: this.promptState.listPlatform().pipe(catchError(() => of([] as Prompt[]))),
        namespace: this.promptState.listByNamespace(this.namespaceId),
      })
    )
  )

  /** Mapped to EntityListItem[] for ds-entity-list, platform group first. */
  protected readonly promptItems$ = this.allPrompts$.pipe(
    map(({ platform, namespace }) => [
      ...platform.map(
        (p: Prompt): EntityListItem => ({
          id: p.id ?? '',
          name: p.name,
          description: p.description,
          groupKey: GROUP_PLATFORM,
          groupLabel: 'Platform (read-only)',
        })
      ),
      ...namespace.map(
        (p: Prompt): EntityListItem => ({
          id: p.id ?? '',
          name: p.name,
          description: p.description,
          groupKey: GROUP_NAMESPACE,
          groupLabel: 'Namespace',
        })
      ),
    ])
  )

  /** Full prompt objects indexed by id — used to resolve itemTemplate events. */
  private namespacePromptsById = new Map<string, Prompt>()
  private platformPromptsById = new Map<string, Prompt>()

  constructor() {
    this.allPrompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ platform, namespace }) => {
      this.platformPromptsById = new Map(platform.map((p: Prompt) => [p.id ?? '', p]))
      this.namespacePromptsById = new Map(namespace.map((p: Prompt) => [p.id ?? '', p]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'prompts', 'new'])
  }

  protected deletePrompt(prompt: Prompt): void {
    this.promptState
      .delete(prompt.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolvePrompt(id: string): Prompt | null {
    return this.platformPromptsById.get(id) ?? this.namespacePromptsById.get(id) ?? null
  }

  protected isPlatformPrompt(id: string): boolean {
    return this.platformPromptsById.has(id)
  }
}

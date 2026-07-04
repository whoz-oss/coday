import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Prompt, PromptControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { PromptItemComponent } from '../prompt-item/prompt-item.component'

/**
 * PlatformPromptsComponent — list view for platform-level prompts.
 *
 * Loaded at /agentos/admin/prompts. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform prompts have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-prompts',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, PromptItemComponent],
  templateUrl: './platform-prompts.component.html',
  styleUrl: './platform-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformPromptsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptController = inject(PromptControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform prompts, kept for delete lookups. */
  private readonly prompts$ = this.refresh$.pipe(switchMap(() => this.promptController.listPlatformPrompt()))

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
    this.prompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((prompts) => {
      this.promptsById = new Map(prompts.map((p: Prompt) => [p.id ?? '', p]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'prompts', 'new'])
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

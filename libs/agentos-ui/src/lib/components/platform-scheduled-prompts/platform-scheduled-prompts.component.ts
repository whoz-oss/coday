import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { ScheduledPrompt } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { ScheduledPromptItemComponent } from '../scheduled-prompt-item/scheduled-prompt-item.component'

/**
 * PlatformScheduledPromptsComponent — list view for platform-level scheduled prompts.
 *
 * Loaded at /agentos/admin/scheduled-prompts. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform scheduled prompts have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-scheduled-prompts',
  imports: [AsyncPipe, EntityListComponent, ScheduledPromptItemComponent, IconButtonComponent],
  templateUrl: './platform-scheduled-prompts.component.html',
  styleUrl: './platform-scheduled-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformScheduledPromptsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform scheduled prompts, kept for mutation lookups. */
  private readonly prompts$ = this.refresh$.pipe(switchMap(() => this.state.listPlatform()))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly promptItems$ = this.prompts$.pipe(
    map((defs) =>
      defs.map(
        (d: ScheduledPrompt): EntityListItem => ({
          id: d.id ?? '',
          name: d.name,
          description: d.description,
          badges: [
            {
              label: d.enabled ? 'Enabled' : 'Disabled',
              variant: d.enabled ? 'success' : 'warning',
            },
          ],
        })
      )
    )
  )

  /** Full prompt objects indexed by id — used to resolve itemTemplate events. */
  private promptsById = new Map<string, ScheduledPrompt>()

  constructor() {
    this.prompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((defs) => {
      this.promptsById = new Map(defs.map((d: ScheduledPrompt) => [d.id ?? '', d]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'scheduled-prompts', 'new'])
  }

  protected togglePrompt(definition: ScheduledPrompt): void {
    this.state
      .toggle(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected deletePrompt(definition: ScheduledPrompt): void {
    this.state
      .delete(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolvePrompt(id: string): ScheduledPrompt | null {
    return this.promptsById.get(id) ?? null
  }
}

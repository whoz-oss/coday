import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { ScheduledPrompt } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, switchMap } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'
import { ScheduledPromptItemComponent } from '../scheduled-prompt-item/scheduled-prompt-item.component'

const GROUP_PLATFORM = 'platform'
const GROUP_NAMESPACE = 'namespace'

/**
 * ScheduledPromptListComponent — smart container for scheduled prompts of a namespace.
 *
 * Loaded at /:namespaceId/scheduled-prompts. Responsibilities:
 * - Load and display namespace-level AND platform-level scheduled prompts
 * - Merge both levels into a grouped ds-entity-list (platform first, then namespace)
 * - Platform-level prompts are displayed read-only (no edit/toggle/delete actions)
 * - Navigate to the create form (/:namespaceId/scheduled-prompts/new)
 * - Toggle enable/disable inline (namespace-level only)
 * - Delete with inline confirmation (delegated to ScheduledPromptItemComponent)
 */
@Component({
  selector: 'agentos-scheduled-prompt-list',
  imports: [AsyncPipe, EntityListComponent, ScheduledPromptItemComponent, IconButtonComponent],
  templateUrl: './scheduled-prompt-list.component.html',
  styleUrl: './scheduled-prompt-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduledPromptListComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Fetches both namespace-level and platform-level prompts in parallel. */
  private readonly allPrompts$ = this.refresh$.pipe(
    switchMap(() =>
      forkJoin({
        platform: this.state.listPlatform().pipe(catchError(() => of([] as ScheduledPrompt[]))),
        namespace: this.state.listByNamespace(this.namespaceId),
      })
    )
  )

  /** Mapped to EntityListItem[] for ds-entity-list, platform group first. */
  protected readonly promptItems$ = this.allPrompts$.pipe(
    map(({ platform, namespace }) => [
      ...platform.map(
        (d: ScheduledPrompt): EntityListItem => ({
          id: d.id ?? '',
          name: d.name,
          description: d.description,
          groupKey: GROUP_PLATFORM,
          groupLabel: 'Platform (read-only)',
          badges: [
            {
              label: d.enabled ? 'Enabled' : 'Disabled',
              variant: d.enabled ? 'success' : 'warning',
            },
          ],
        })
      ),
      ...namespace.map(
        (d: ScheduledPrompt): EntityListItem => ({
          id: d.id ?? '',
          name: d.name,
          description: d.description,
          groupKey: GROUP_NAMESPACE,
          groupLabel: 'Namespace',
          badges: [
            {
              label: d.enabled ? 'Enabled' : 'Disabled',
              variant: d.enabled ? 'success' : 'warning',
            },
          ],
        })
      ),
    ])
  )

  /** Full prompt objects indexed by id — used to resolve itemTemplate events. */
  private platformPromptsById = new Map<string, ScheduledPrompt>()
  private namespacePromptsById = new Map<string, ScheduledPrompt>()

  constructor() {
    this.allPrompts$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ platform, namespace }) => {
      this.platformPromptsById = new Map(platform.map((d: ScheduledPrompt) => [d.id ?? '', d]))
      this.namespacePromptsById = new Map(namespace.map((d: ScheduledPrompt) => [d.id ?? '', d]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'scheduled-prompts', 'new'])
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
    return this.platformPromptsById.get(id) ?? this.namespacePromptsById.get(id) ?? null
  }

  protected isPlatformPrompt(id: string): boolean {
    return this.platformPromptsById.has(id)
  }
}

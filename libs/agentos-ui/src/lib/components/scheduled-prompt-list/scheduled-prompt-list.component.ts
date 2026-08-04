import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { ScheduledPrompt } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { catchError, forkJoin, of } from 'rxjs'
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
 * - Platform-level prompts are displayed read-only (no edit/enable-disable/delete actions)
 * - Navigate to the create form (/:namespaceId/scheduled-prompts/new)
 * - Enable/disable inline (idempotent, namespace-level only)
 * - Delete with inline confirmation (delegated to ScheduledPromptItemComponent)
 *
 * State is two signals (platform, namespace), loaded once. Mutations only ever apply
 * to namespace-level prompts (platform prompts are read-only here), and patch the
 * namespace signal locally from the updated entity returned by the API — no full
 * refetch of both scopes on every single enable/disable/delete.
 */
@Component({
  selector: 'agentos-scheduled-prompt-list',
  imports: [EntityListComponent, ScheduledPromptItemComponent, IconButtonComponent],
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

  private readonly platformPrompts = signal<ScheduledPrompt[]>([])
  private readonly namespacePrompts = signal<ScheduledPrompt[]>([])

  /** Mapped to EntityListItem[] for ds-entity-list, platform group first. */
  protected readonly promptItems = computed<EntityListItem[]>(() => [
    ...this.platformPrompts().map(
      (d): EntityListItem => ({
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
    ...this.namespacePrompts().map(
      (d): EntityListItem => ({
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

  constructor() {
    forkJoin({
      platform: this.state.listPlatform().pipe(catchError(() => of([] as ScheduledPrompt[]))),
      namespace: this.state.listByNamespace(this.namespaceId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ platform, namespace }) => {
        this.platformPrompts.set(platform)
        this.namespacePrompts.set(namespace)
      })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'scheduled-prompts', 'new'])
  }

  protected enablePrompt(definition: ScheduledPrompt): void {
    this.state
      .enable(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((updated) => this.patchNamespacePrompt(updated))
  }

  protected disablePrompt(definition: ScheduledPrompt): void {
    this.state
      .disable(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((updated) => this.patchNamespacePrompt(updated))
  }

  protected deletePrompt(definition: ScheduledPrompt): void {
    const id = definition.id ?? ''
    this.state
      .delete(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.namespacePrompts.update((list) => list.filter((p) => p.id !== id)))
  }

  protected resolvePrompt(id: string): ScheduledPrompt | null {
    return this.platformPrompts().find((p) => p.id === id) ?? this.namespacePrompts().find((p) => p.id === id) ?? null
  }

  protected isPlatformPrompt(id: string): boolean {
    return this.platformPrompts().some((p) => p.id === id)
  }

  private patchNamespacePrompt(updated: ScheduledPrompt): void {
    this.namespacePrompts.update((list) => list.map((p) => (p.id === updated.id ? updated : p)))
  }
}

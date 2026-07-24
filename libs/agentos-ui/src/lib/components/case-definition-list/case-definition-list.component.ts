import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { CaseDefinition } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, switchMap } from 'rxjs'
import { CaseDefinitionStateService } from '../../services/case-definition-state.service'
import { CaseDefinitionItemComponent } from '../case-definition-item/case-definition-item.component'

const GROUP_PLATFORM = 'platform'
const GROUP_NAMESPACE = 'namespace'

/**
 * CaseDefinitionListComponent — smart container for case definitions of a namespace.
 *
 * Loaded at /:namespaceId/case-definitions. Responsibilities:
 * - Load and display namespace-level AND platform-level case definitions
 * - Merge both levels into a grouped ds-entity-list (platform first, then namespace)
 * - Platform-level definitions are displayed read-only (no edit/toggle/delete actions)
 * - Navigate to the create form (/:namespaceId/case-definitions/new)
 * - Toggle enable/disable inline (namespace-level only)
 * - Delete with inline confirmation (delegated to CaseDefinitionItemComponent)
 *
 * Create and edit are handled by CaseDefinitionFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-case-definition-list',
  imports: [AsyncPipe, EntityListComponent, CaseDefinitionItemComponent, IconButtonComponent],
  templateUrl: './case-definition-list.component.html',
  styleUrl: './case-definition-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionListComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly caseDefState = inject(CaseDefinitionStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Fetches both namespace-level and platform-level definitions in parallel. */
  private readonly allDefinitions$ = this.refresh$.pipe(
    switchMap(() =>
      forkJoin({
        platform: this.caseDefState.listPlatform().pipe(catchError(() => of([] as CaseDefinition[]))),
        namespace: this.caseDefState.listByNamespace(this.namespaceId),
      })
    )
  )

  /** Mapped to EntityListItem[] for ds-entity-list, platform group first. */
  protected readonly definitionItems$ = this.allDefinitions$.pipe(
    map(({ platform, namespace }) => [
      ...platform.map(
        (d: CaseDefinition): EntityListItem => ({
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
        (d: CaseDefinition): EntityListItem => ({
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

  /** Full definition objects indexed by id — used to resolve itemTemplate events. */
  private platformDefsById = new Map<string, CaseDefinition>()
  private namespaceDefsById = new Map<string, CaseDefinition>()

  constructor() {
    this.allDefinitions$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ platform, namespace }) => {
      this.platformDefsById = new Map(platform.map((d: CaseDefinition) => [d.id ?? '', d]))
      this.namespaceDefsById = new Map(namespace.map((d: CaseDefinition) => [d.id ?? '', d]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'case-definitions', 'new'])
  }

  protected toggleDefinition(definition: CaseDefinition): void {
    this.caseDefState
      .toggle(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected deleteDefinition(definition: CaseDefinition): void {
    this.caseDefState
      .delete(definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveDefinition(id: string): CaseDefinition | null {
    return this.platformDefsById.get(id) ?? this.namespaceDefsById.get(id) ?? null
  }

  protected isPlatformDefinition(id: string): boolean {
    return this.platformDefsById.has(id)
  }
}

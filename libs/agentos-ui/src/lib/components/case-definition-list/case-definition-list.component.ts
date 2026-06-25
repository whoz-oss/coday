import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { CaseDefinition, CaseDefinitionApiService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { CaseDefinitionItemComponent } from '../case-definition-item/case-definition-item.component'

/**
 * CaseDefinitionListComponent — smart container for case definitions of a namespace.
 *
 * Loaded at /:namespaceId/case-definitions. Responsibilities:
 * - Load and display the list of CaseDefinition via ds-entity-list
 * - Toggle enable/disable inline
 * - Navigate to the create form
 * - Delete with inline confirmation (delegated to CaseDefinitionItemComponent)
 */
@Component({
  selector: 'agentos-case-definition-list',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, CaseDefinitionItemComponent],
  templateUrl: './case-definition-list.component.html',
  styleUrl: './case-definition-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionListComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly caseDefinitionApi = inject(CaseDefinitionApiService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw definitions, kept for item-template resolution and mutations. */
  private readonly definitions$ = this.refresh$.pipe(switchMap(() => this.caseDefinitionApi.list(this.namespaceId)))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly definitionItems$ = this.definitions$.pipe(
    map((definitions) =>
      definitions.map(
        (d): EntityListItem => ({
          id: d.id ?? '',
          name: d.name,
          description: `${d.frequency} at ${d.timeUtc} UTC`,
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

  /** Full definition objects indexed by id — used to resolve itemTemplate events. */
  private definitionsById = new Map<string, CaseDefinition>()

  constructor() {
    this.definitions$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((definitions) => {
      this.definitionsById = new Map(definitions.map((d) => [d.id ?? '', d]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'case-definitions', 'new'])
  }

  protected toggleDefinition(definition: CaseDefinition): void {
    this.caseDefinitionApi
      .toggle(this.namespaceId, definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected deleteDefinition(definition: CaseDefinition): void {
    this.caseDefinitionApi
      .delete(this.namespaceId, definition.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveDefinition(id: string): CaseDefinition | null {
    return this.definitionsById.get(id) ?? null
  }
}

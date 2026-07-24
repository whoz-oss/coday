import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { CaseDefinition } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { CaseDefinitionStateService } from '../../services/case-definition-state.service'
import { CaseDefinitionItemComponent } from '../case-definition-item/case-definition-item.component'

/**
 * PlatformCaseDefinitionsComponent — list view for platform-level case definitions.
 *
 * Loaded at /agentos/admin/case-definitions. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform case definitions have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-case-definitions',
  imports: [AsyncPipe, EntityListComponent, CaseDefinitionItemComponent, IconButtonComponent],
  templateUrl: './platform-case-definitions.component.html',
  styleUrl: './platform-case-definitions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformCaseDefinitionsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly caseDefState = inject(CaseDefinitionStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform definitions, kept for mutation lookups. */
  private readonly definitions$ = this.refresh$.pipe(switchMap(() => this.caseDefState.listPlatform()))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly definitionItems$ = this.definitions$.pipe(
    map((defs) =>
      defs.map(
        (d: CaseDefinition): EntityListItem => ({
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

  /** Full definition objects indexed by id — used to resolve itemTemplate events. */
  private definitionsById = new Map<string, CaseDefinition>()

  constructor() {
    this.definitions$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((defs) => {
      this.definitionsById = new Map(defs.map((d: CaseDefinition) => [d.id ?? '', d]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'case-definitions', 'new'])
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
    return this.definitionsById.get(id) ?? null
  }
}

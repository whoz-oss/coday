import { Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { switchMap, BehaviorSubject, map } from 'rxjs'
import { AsyncPipe } from '@angular/common'
import { NamespaceItemComponent } from '../namespace-item/namespace-item.component'

/**
 * NamespaceListComponent — smart container for namespace listing.
 *
 * Responsibilities:
 * - Load and display the list of namespaces via ds-entity-list
 * - Navigate to create/edit pages
 * - Delete with immediate list refresh (confirmation handled by NamespaceItemComponent)
 *
 * Create and edit logic live in NamespaceFormComponent.
 */
@Component({
  selector: 'agentos-namespace-list',
  standalone: true,
  imports: [AsyncPipe, NamespaceItemComponent, EntityListComponent],
  templateUrl: './namespace-list.component.html',
  styleUrl: './namespace-list.component.scss',
})
export class NamespaceListComponent {
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly destroyRef = inject(DestroyRef)

  /** Trigger to refresh the list (emitting a new value forces re-subscription). */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw namespaces, kept for delete lookups. */
  private readonly namespaces$ = this.refresh$.pipe(switchMap(() => this.namespaceController.listAllNamespace()))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly namespaceItems$ = this.namespaces$.pipe(
    map((namespaces) =>
      namespaces.map(
        (ns): EntityListItem => ({
          id: ns.id ?? '',
          name: ns.name,
          description: ns.description,
        })
      )
    )
  )

  /** Full namespace objects indexed by id — used to resolve itemTemplate events. */
  private namespacesById = new Map<string, Namespace>()

  constructor() {
    this.namespaces$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((namespaces) => {
      this.namespacesById = new Map(namespaces.map((ns) => [ns.id ?? '', ns]))
    })
  }

  // --- Navigation ---

  protected navigateToCases(id: string): void {
    this.router.navigate(['/agentos', id, 'cases'])
  }

  protected navigateToCreate(): void {
    this.router.navigate(['/agentos/namespaces/new'])
  }

  protected navigateToEdit(ns: Namespace): void {
    this.router.navigate(['/agentos/namespaces', ns.id ?? '', 'edit'])
  }

  protected openIntegrations(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'integrations'])
  }

  protected openAiProviders(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'ai-providers'])
  }

  protected openLlmModels(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'llm-models'])
  }

  // --- Delete ---

  protected deleteNamespace(ns: Namespace): void {
    this.namespaceController
      .deleteNamespace(ns.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  // --- Item template helpers ---

  protected resolveNamespace(id: string): Namespace | null {
    return this.namespacesById.get(id) ?? null
  }
}

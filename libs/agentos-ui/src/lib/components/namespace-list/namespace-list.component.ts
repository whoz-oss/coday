import { Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ReactiveFormsModule, FormControl, Validators } from '@angular/forms'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { switchMap, BehaviorSubject, map } from 'rxjs'
import { AsyncPipe } from '@angular/common'
import { NamespaceItemComponent } from '../namespace-item/namespace-item.component'

/**
 * NamespaceListComponent — smart container for namespace management.
 *
 * Responsibilities:
 * - Load and display the list of namespaces via ds-entity-list (layout, search, grid)
 * - Inline creation form (name + optional description)
 * - Inline edit form (pre-filled with existing values)
 * - Deletion with immediate list refresh (confirmation handled by NamespaceItemComponent)
 */
@Component({
  selector: 'agentos-namespace-list',
  standalone: true,
  imports: [AsyncPipe, ReactiveFormsModule, IconButtonComponent, NamespaceItemComponent, EntityListComponent],
  templateUrl: './namespace-list.component.html',
  styleUrl: './namespace-list.component.scss',
})
export class NamespaceListComponent {
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly destroyRef = inject(DestroyRef)

  /** Trigger to refresh the list (emitting a new value forces re-subscription). */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw namespaces, kept for edit/delete lookups. */
  private readonly namespaces$ = this.refresh$.pipe(switchMap(() => this.namespaceController.listAll()))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly namespaceItems$ = this.namespaces$.pipe(
    map((namespaces) =>
      namespaces.map(
        (ns): EntityListItem => ({
          id: ns.id,
          name: ns.name,
          description: ns.description,
        })
      )
    )
  )

  /** Full namespace objects indexed by id — used to resolve itemSelected events. */
  private namespacesById = new Map<string, Namespace>()

  constructor() {
    this.namespaces$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((namespaces) => {
      this.namespacesById = new Map(namespaces.map((ns) => [ns.id, ns]))
    })
  }

  // --- Create form ---

  protected readonly nameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly descriptionControl = new FormControl<string>('', { nonNullable: true })

  protected readonly isCreating = signal(false)
  protected readonly isSubmitting = signal(false)

  // --- Edit form ---

  protected readonly editNameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly editDescriptionControl = new FormControl<string>('', { nonNullable: true })

  protected readonly editingNamespace = signal<Namespace | null>(null)
  protected readonly isEditSubmitting = signal(false)

  // --- Navigation ---

  protected select(id: string): void {
    this.router.navigate(['/agentos', id, 'cases'])
  }

  // --- Create ---

  protected openCreateForm(): void {
    this.nameControl.reset()
    this.descriptionControl.reset()
    this.isCreating.set(true)
    this.editingNamespace.set(null)
  }

  protected cancelCreate(): void {
    this.isCreating.set(false)
  }

  protected submitCreate(): void {
    if (this.nameControl.invalid || this.isSubmitting()) return

    const payload = {
      name: this.nameControl.value.trim(),
      ...(this.descriptionControl.value.trim() ? { description: this.descriptionControl.value.trim() } : {}),
    } as Namespace

    this.isSubmitting.set(true)
    this.namespaceController
      .create(payload)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isCreating.set(false)
          this.isSubmitting.set(false)
          this.refresh$.next()
        },
        error: () => this.isSubmitting.set(false),
      })
  }

  // --- Edit ---

  protected openEditForm(ns: Namespace): void {
    this.editNameControl.setValue(ns.name)
    this.editDescriptionControl.setValue(ns.description ?? '')
    this.editingNamespace.set(ns)
    this.isCreating.set(false)
  }

  protected cancelEdit(): void {
    this.editingNamespace.set(null)
  }

  protected submitEdit(): void {
    const ns = this.editingNamespace()
    if (!ns || this.editNameControl.invalid || this.isEditSubmitting()) return

    const payload: Namespace = {
      ...ns,
      name: this.editNameControl.value.trim(),
      description: this.editDescriptionControl.value.trim() || undefined,
    }

    this.isEditSubmitting.set(true)
    this.namespaceController
      .update(ns.id, payload)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.editingNamespace.set(null)
          this.isEditSubmitting.set(false)
          this.refresh$.next()
        },
        error: () => this.isEditSubmitting.set(false),
      })
  }

  // --- Delete ---

  protected deleteNamespace(ns: Namespace): void {
    this.namespaceController
      ._delete(ns.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  // --- Item template helpers ---

  /**
   * Resolve a full Namespace from an id emitted by ds-entity-list.
   * Used by the itemTemplate to pass the typed object to NamespaceItemComponent.
   */
  protected resolveNamespace(id: string): Namespace | null {
    return this.namespacesById.get(id) ?? null
  }
}

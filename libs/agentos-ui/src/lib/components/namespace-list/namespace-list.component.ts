import { Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ReactiveFormsModule, FormControl, Validators } from '@angular/forms'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { switchMap, BehaviorSubject } from 'rxjs'
import { AsyncPipe } from '@angular/common'
import { NamespaceItemComponent } from '../namespace-item/namespace-item.component'

/**
 * NamespaceListComponent — smart container for namespace management.
 *
 * Responsibilities:
 * - Load and display the list of namespaces (via NamespaceItemComponent)
 * - Inline creation form (name + optional description)
 * - Inline edit form (pre-filled with existing values)
 * - Deletion with immediate list refresh (confirmation handled by NamespaceItemComponent)
 */
@Component({
  selector: 'agentos-namespace-list',
  standalone: true,
  imports: [AsyncPipe, ReactiveFormsModule, IconButtonComponent, NamespaceItemComponent],
  templateUrl: './namespace-list.component.html',
  styleUrl: './namespace-list.component.scss',
})
export class NamespaceListComponent {
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly destroyRef = inject(DestroyRef)

  /** Trigger to refresh the list (emitting a new value forces re-subscription). */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly namespaces$ = this.refresh$.pipe(switchMap(() => this.namespaceController.listAll()))

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

  protected select(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'cases'])
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

  protected trackById(_index: number, ns: Namespace): string {
    return ns.id
  }
}

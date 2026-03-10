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
 * - Deletion with immediate list refresh
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

  protected readonly nameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly descriptionControl = new FormControl<string>('', { nonNullable: true })

  protected readonly isCreating = signal(false)
  protected readonly isSubmitting = signal(false)

  protected select(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'cases'])
  }

  protected openCreateForm(): void {
    this.nameControl.reset()
    this.descriptionControl.reset()
    this.isCreating.set(true)
  }

  protected cancelCreate(): void {
    this.isCreating.set(false)
  }

  protected submitCreate(): void {
    if (this.nameControl.invalid || this.isSubmitting()) return

    const payload: Namespace = {
      id: '',
      metadata: { id: '', created: '', modified: '', removed: false },
      name: this.nameControl.value.trim(),
      ...(this.descriptionControl.value.trim() ? { description: this.descriptionControl.value.trim() } : {}),
    }

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

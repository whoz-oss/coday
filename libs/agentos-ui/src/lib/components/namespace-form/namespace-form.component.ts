import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'

/**
 * NamespaceFormComponent — full-page create / edit form for a namespace.
 *
 * Mode is determined by the presence of `:namespaceId` in the route params:
 * - `/agentos/namespaces/new`                  → create mode
 * - `/agentos/namespaces/:namespaceId/edit`    → edit mode (loads existing data)
 *
 * On success, navigates back to /agentos/namespaces.
 */
@Component({
  selector: 'agentos-namespace-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './namespace-form.component.html',
  styleUrl: './namespace-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string>('', { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }

  protected get descriptionControl() {
    return this.form.controls.description
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side fields). */
  private existingNamespace: Namespace | null = null

  ngOnInit(): void {
    const namespaceId = this.route.snapshot.paramMap.get('namespaceId')
    if (namespaceId) {
      this.isEditMode.set(true)
      this.loadNamespace(namespaceId)
    }
  }

  private loadNamespace(id: string): void {
    this.isLoading.set(true)
    this.namespaceController
      .getByIdNamespace(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ns) => {
          this.existingNamespace = ns
          this.nameControl.setValue(ns.name)
          this.descriptionControl.setValue(ns.description ?? '')
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  protected submit(): void {
    if (this.nameControl.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    const call$ = this.isEditMode()
      ? this.namespaceController.updateNamespace(this.existingNamespace!.id ?? '', {
          ...this.existingNamespace!,
          name: this.nameControl.value.trim(),
          description: this.descriptionControl.value.trim() || undefined,
        })
      : this.namespaceController.createNamespace({
          name: this.nameControl.value.trim(),
          ...(this.descriptionControl.value.trim() ? { description: this.descriptionControl.value.trim() } : {}),
        } as Namespace)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos/namespaces'])
  }
}

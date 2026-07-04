import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { UserStateService } from '../../services/user-state.service'

/**
 * UserFormComponent — create and edit form for a user (admin view).
 *
 * Two modes driven by the presence of :userId in the route:
 *   - Create (/agentos/admin/users/new): empty form, calls createUser()
 *   - Edit   (/agentos/admin/users/:userId/edit): pre-filled form, calls updateUser()
 *
 * Navigation:
 *   - On success: navigates back to /agentos/admin/users
 *   - Cancel: same
 *
 * Email is required for creation; all other fields are optional.
 * Email is read-only in edit mode (identity field, not updatable here).
 */
@Component({
  selector: 'agentos-user-form',
  imports: [ReactiveFormsModule],
  templateUrl: './user-form.component.html',
  styleUrl: './user-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userAdminState = inject(UserAdminStateService)
  private readonly userState = inject(UserStateService)

  private readonly userId = this.route.snapshot.params['userId'] as string | undefined

  protected readonly isEditMode = !!this.userId
  protected readonly isSaving = signal(false)
  protected readonly isLoading = signal(false)

  protected readonly form = new FormGroup({
    email: new FormControl<string>('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    externalId: new FormControl<string>('', { nonNullable: true }),
    firstname: new FormControl<string>('', { nonNullable: true }),
    lastname: new FormControl<string>('', { nonNullable: true }),
    bio: new FormControl<string>('', { nonNullable: true }),
    isAdmin: new FormControl<boolean>(false, { nonNullable: true }),
  })

  ngOnInit(): void {
    if (!this.isEditMode) return

    // Self-rule: the backend ignores isAdmin changes from the caller on their own
    // record. Disable the checkbox in self-edit mode so the UX matches the contract.
    // Fallback: if currentUser hasn't been loaded yet (e.g. direct landing), fetch it
    // before deciding — without this, a self-edit could leave the checkbox enabled.
    const applySelfEditDisable = () => {
      if (this.userState.currentUser()?.id === this.userId) {
        this.form.controls.isAdmin.disable()
      }
    }
    if (this.userState.currentUser()) {
      applySelfEditDisable()
    } else {
      this.userState
        .loadMe()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => applySelfEditDisable(),
          // If we can't resolve the current user, disable the checkbox unconditionally
          // so the UI never falsely advertises a permission the backend will reject.
          error: () => this.form.controls.isAdmin.disable(),
        })
    }

    // Try to resolve from already-loaded state first (avoids extra HTTP call).
    const existing = this.userAdminState.users().find((u) => u.id === this.userId)
    if (existing) {
      this.syncForm(existing)
      this.form.controls.email.disable()
      return
    }

    // State not loaded yet — load all and then sync.
    this.isLoading.set(true)
    this.userAdminState
      .loadAll()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          const user = this.userAdminState.users().find((u) => u.id === this.userId)
          if (user) {
            this.syncForm(user)
            this.form.controls.email.disable()
          }
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected get title(): string {
    return this.isEditMode ? 'Edit user' : 'New user'
  }

  protected save(): void {
    if (this.form.invalid || this.isSaving()) return
    this.isSaving.set(true)

    const { email, externalId, firstname, lastname, bio, isAdmin } = this.form.getRawValue()

    const action$ = this.isEditMode
      ? this.userAdminState.updateUser(this.userId!, {
          externalId: externalId || undefined,
          firstname: firstname || undefined,
          lastname: lastname || undefined,
          bio: bio || undefined,
          isAdmin,
        })
      : this.userAdminState.createUser({
          email: email || undefined,
          externalId: externalId || undefined,
          firstname: firstname || undefined,
          lastname: lastname || undefined,
          bio: bio || undefined,
          isAdmin,
        })

    action$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSaving.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos/admin/users'])
  }

  private syncForm(user: {
    email?: string
    externalId?: string
    firstname?: string
    lastname?: string
    bio?: string
    isAdmin?: boolean
  }): void {
    this.form.setValue({
      email: user.email ?? '',
      externalId: user.externalId ?? '',
      firstname: user.firstname ?? '',
      lastname: user.lastname ?? '',
      bio: user.bio ?? '',
      isAdmin: user.isAdmin ?? false,
    })
  }
}

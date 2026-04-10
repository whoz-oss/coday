import { Location } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms'
import { Router } from '@angular/router'
import { UserStateService } from '../../services/user-state.service'

/**
 * UserProfileComponent — full-page view and edit of the current user's profile.
 *
 * Two modes in one component, toggled by isEditing():
 *   - Read mode: displays all user fields (email, externalId read-only + firstname, lastname, bio)
 *   - Edit mode: reactive form for firstname, lastname, bio
 *
 * Navigation:
 *   - Back button uses Location.back() for contextual return (preserves browser history).
 *   - Falls back to /agentos if no history entry exists (direct URL access).
 *
 * Data:
 *   - Loads the current user via UserStateService.loadMe() on init if not already cached.
 *   - Persists changes via UserStateService.updateMe().
 */
@Component({
  selector: 'agentos-user-profile',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserProfileComponent implements OnInit {
  private readonly userState = inject(UserStateService)
  private readonly router = inject(Router)
  private readonly location = inject(Location)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly isEditing = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isSaving = signal(false)

  protected readonly currentUser = this.userState.currentUser

  protected readonly form = new FormGroup({
    firstname: new FormControl<string>('', { nonNullable: true }),
    lastname: new FormControl<string>('', { nonNullable: true }),
    bio: new FormControl<string>('', { nonNullable: true }),
  })

  ngOnInit(): void {
    if (this.currentUser()) {
      this.syncFormFromState()
      return
    }
    this.isLoading.set(true)
    this.userState
      .loadMe()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.syncFormFromState()
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected startEditing(): void {
    this.syncFormFromState()
    this.isEditing.set(true)
  }

  protected cancelEditing(): void {
    this.isEditing.set(false)
  }

  protected save(): void {
    if (this.isSaving()) return
    this.isSaving.set(true)
    this.userState
      .updateMe({
        firstname: this.form.controls.firstname.value.trim() || undefined,
        lastname: this.form.controls.lastname.value.trim() || undefined,
        bio: this.form.controls.bio.value.trim() || undefined,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isSaving.set(false)
          this.isEditing.set(false)
        },
        error: () => this.isSaving.set(false),
      })
  }

  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back()
    } else {
      this.router.navigate(['/agentos'])
    }
  }

  private syncFormFromState(): void {
    const user = this.currentUser()
    if (!user) return
    this.form.setValue({
      firstname: user.firstname ?? '',
      lastname: user.lastname ?? '',
      bio: user.bio ?? '',
    })
  }
}

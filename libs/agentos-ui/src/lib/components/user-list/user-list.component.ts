import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { User } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { computed } from '@angular/core'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { UserItemComponent } from '../user-item/user-item.component'

/**
 * UserListComponent — admin view for listing and managing all users.
 *
 * Loaded at /agentos/admin/users. Responsibilities:
 * - Load and display all users via ds-entity-list
 * - Navigate to the create form (/agentos/admin/users/new)
 * - Navigate to the edit form (/agentos/admin/users/:userId/edit)
 * - Delete with confirmation (delegated to UserItemComponent)
 *
 * Create and edit logic live in UserFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-user-list',
  standalone: true,
  imports: [EntityListComponent, UserItemComponent],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userAdminState = inject(UserAdminStateService)

  protected readonly isLoading = this.userAdminState.isLoading

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly userItems = computed<EntityListItem[]>(() =>
    this.userAdminState.users().map(
      (u): EntityListItem => ({
        id: u.id ?? '',
        name: [u.firstname, u.lastname].filter(Boolean).join(' ') || u.email || '—',
        description: u.email,
      })
    )
  )

  /** Full user objects indexed by id — used to resolve itemTemplate events. */
  private get usersById(): Map<string, User> {
    return new Map(this.userAdminState.users().map((u) => [u.id ?? '', u]))
  }

  ngOnInit(): void {
    this.userAdminState.loadAll().pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
  }

  protected goBack(): void {
    this.router.navigate(['/agentos'])
  }

  protected navigateToCreate(): void {
    this.router.navigate(['/agentos/admin/users/new'])
  }

  protected navigateToEdit(user: User): void {
    this.router.navigate(['/agentos/admin/users', user.id ?? '', 'edit'])
  }

  protected deleteUser(user: User): void {
    this.userAdminState
      .deleteUser(user.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }

  protected resolveUser(id: string): User | null {
    return this.usersById.get(id) ?? null
  }
}

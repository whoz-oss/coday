import { HttpErrorResponse } from '@angular/common/http'
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import {
  NamespaceUserListItem,
  NamespaceUserListItemRoleEnum,
  User,
  UserMembershipRoleRoleEnum,
} from '@whoz-oss/agentos-api-client'
import { AutocompleteInputComponent, AutocompleteItem } from '@whoz-oss/design-system'
import { forkJoin, of } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { MemberUpdateEntry, NamespaceMemberStateService } from '../../services/namespace-member-state.service'
import { UserStateService } from '../../services/user-state.service'
import { NamespaceMemberAutocompleteDataSource } from './namespace-member.data-source'
import { computeMemberDiff, hasAtLeastOneAdmin, MemberRoleEntry, memberLabel } from './namespace-members.util'

/** A member currently in the list, with a display label and their (exclusive) role. */
interface SelectedMember {
  userId: string
  label: string
  email?: string
  role: NamespaceUserListItemRoleEnum
}

/**
 * NamespaceMembersComponent — dedicated screen to manage a namespace's ADMIN/MEMBER roster.
 *
 * Loaded at /:namespaceId/members. Modeled after UserGroupFormComponent's members block (the
 * explicit PO reference), with two key differences:
 *
 * 1. Roles are EXCLUSIVE here (ADMIN xor MEMBER xor absent), unlike UserGroup where ADMIN is
 *    additive to roster membership — so each row has a single role `<select>`, not a
 *    membership-checkbox-plus-admin-flag.
 * 2. Authorization is two-tier server-side (see NamespacePermissionEndpoints.updateMembers):
 *    adding a genuinely NEW user requires SUPER_ADMIN (the directory search itself,
 *    `GET /api/users`, is SUPER_ADMIN-only), while changing the role of — or removing — an
 *    EXISTING member only requires namespace ADMIN (WRITE). The add-member autocomplete is
 *    therefore only rendered for super-admins; a namespace admin who is not a super-admin still
 *    sees the full roster with role-change and remove actions.
 *
 * This is a standalone page (not folded into NamespaceFormComponent) because member management
 * is orthogonal to the name/description/configPath entity form, and cannot apply to a namespace
 * that does not exist yet — consistent with how UserGroups, AgentConfigs, etc. get their own
 * namespace-scoped routes rather than living inside NamespaceFormComponent.
 */
@Component({
  selector: 'agentos-namespace-members',
  imports: [AutocompleteInputComponent],
  templateUrl: './namespace-members.component.html',
  styleUrl: './namespace-members.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceMembersComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly memberState = inject(NamespaceMemberStateService)
  private readonly userState = inject(UserStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly isLoading = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  /** Whether the current user is a platform super-admin — gates the "add member" autocomplete. */
  protected readonly isSuperAdmin = signal(false)

  protected readonly selectedMembers = signal<SelectedMember[]>([])
  private originalMembers: MemberRoleEntry[] = []

  /** Candidate pool for the autocomplete — all platform users, only loaded for super-admins. */
  private readonly candidateUsers = signal<User[]>([])

  protected readonly memberDataSource = new NamespaceMemberAutocompleteDataSource(
    () => this.candidateUsers(),
    () => new Set(this.selectedMembers().map((member) => member.userId))
  )

  ngOnInit(): void {
    this.loadData()
  }

  private loadData(): void {
    this.isLoading.set(true)

    const currentUser$ = this.userState.currentUser() ? of(this.userState.currentUser()) : this.userState.loadMe()

    currentUser$
      .pipe(
        catchError((err) => {
          console.error('[NamespaceMembers] Failed to load current user', err)
          return of(null)
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((user) => {
        const isSuperAdmin = user?.isAdmin === true
        this.isSuperAdmin.set(isSuperAdmin)
        this.loadMembersAndCandidates(isSuperAdmin)
      })
  }

  private loadMembersAndCandidates(isSuperAdmin: boolean): void {
    forkJoin({
      members: this.memberState.listMembers(this.namespaceId),
      // Only super-admins may call GET /api/users (backend enforces SUPER_ADMIN); skip the
      // call entirely for everyone else rather than let it 403.
      allUsers: isSuperAdmin ? this.memberState.listAllUsers() : of([] as User[]),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ members, allUsers }) => {
          this.applyMembers(members)
          this.candidateUsers.set(allUsers)
          this.isLoading.set(false)
        },
        error: (err) => {
          console.error('[NamespaceMembers] Failed to load namespace members', err)
          this.isLoading.set(false)
          this.errorMessage.set('Failed to load namespace members. Please try again.')
        },
      })
  }

  private applyMembers(members: NamespaceUserListItem[]): void {
    this.originalMembers = members.map((member) => ({ userId: member.id, role: member.role }))
    this.selectedMembers.set(
      members.map((member) => ({
        userId: member.id,
        label: memberLabel(member),
        email: member.email,
        role: member.role,
      }))
    )
  }

  // ---------------------------------------------------------------------------
  // Member selection
  // ---------------------------------------------------------------------------

  protected onMemberSelected(item: AutocompleteItem): void {
    if (this.selectedMembers().some((member) => member.userId === item.id)) return
    this.selectedMembers.update((members) => [
      ...members,
      { userId: item.id, label: item.name, email: item.description, role: NamespaceUserListItemRoleEnum.MEMBER },
    ])
  }

  protected removeMember(userId: string): void {
    this.selectedMembers.update((members) => members.filter((member) => member.userId !== userId))
  }

  protected setMemberRole(userId: string, role: string): void {
    const nextRole =
      role === NamespaceUserListItemRoleEnum.ADMIN
        ? NamespaceUserListItemRoleEnum.ADMIN
        : NamespaceUserListItemRoleEnum.MEMBER
    this.selectedMembers.update((members) =>
      members.map((member) => (member.userId === userId ? { ...member, role: nextRole } : member))
    )
  }

  /** True when the edited roster would leave the namespace with zero ADMIN — blocks submit. */
  protected readonly wouldLockOutAdmins = () => !hasAtLeastOneAdmin(this.selectedMembers())

  // ---------------------------------------------------------------------------
  // Submit / cancel
  // ---------------------------------------------------------------------------

  protected submit(): void {
    if (this.isSubmitting()) return

    if (this.wouldLockOutAdmins()) {
      this.errorMessage.set('At least one ADMIN must remain on the namespace. Assign ADMIN to someone before saving.')
      return
    }

    this.isSubmitting.set(true)
    this.errorMessage.set(null)

    const { toUpsert, toRemove } = computeMemberDiff(
      this.originalMembers,
      this.selectedMembers().map((member) => ({ userId: member.userId, role: member.role }))
    )

    if (toUpsert.length === 0 && toRemove.length === 0) {
      this.isSubmitting.set(false)
      this.navigateBack()
      return
    }

    const updateEntries: MemberUpdateEntry[] = [
      ...toUpsert.map((entry) => ({
        userId: entry.userId,
        role: entry.role as UserMembershipRoleRoleEnum,
      })),
      ...toRemove.map((userId) => ({ userId })),
    ]

    this.memberState
      .updateMembers(this.namespaceId, updateEntries)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refreshed) => {
          this.applyMembers(refreshed)
          this.isSubmitting.set(false)
          this.navigateBack()
        },
        error: (err: HttpErrorResponse) => {
          this.isSubmitting.set(false)
          this.errorMessage.set(this.describeError(err))
        },
      })
  }

  private describeError(err: HttpErrorResponse): string {
    if (err.status === 403) {
      return 'You do not have permission to add a new user to this namespace — only a platform super-admin can add someone new. You can still change roles or remove existing members.'
    }
    if (err.status === 422) {
      return (
        err.error?.message ??
        'This change would leave the namespace without any ADMIN. Assign ADMIN to at least one member and try again.'
      )
    }
    return 'An unexpected error occurred while saving namespace members. Please try again.'
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }
}

import { HttpErrorResponse } from '@angular/common/http'
import { LowerCasePipe } from '@angular/common'
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MemberItem, MemberItemRoleEnum, UserMembershipRoleRoleEnum } from '@whoz-oss/agentos-api-client'
import { AutocompleteInputComponent, AutocompleteItem } from '@whoz-oss/design-system'
import { forkJoin, of } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { CaseMemberUpdateEntry, CaseMemberStateService } from '../../services/case-member-state.service'
import { UserStateService } from '../../services/user-state.service'
import {
  MemberCandidate,
  NamespaceMemberAutocompleteDataSource,
} from '../namespace-members/namespace-member.data-source'
import {
  computeMemberDiff,
  hasAtLeastOneAdmin,
  MemberRoleEntry,
  memberLabel,
} from '../namespace-members/namespace-members.util'

/** A member currently in the list, with a display label and their (exclusive) role. */
interface SelectedMember {
  userId: string
  label: string
  email?: string
  role: MemberItemRoleEnum
}

/**
 * CaseMembersComponent — presentational panel to manage a case’s ADMIN/MEMBER roster.
 *
 * Rendered inside the right-side drawer of CaseChatComponent (alongside ExchangeShell).
 * Receives [caseId] as input; emits (closeRequested) when done or cancelled.
 *
 * Roles are EXCLUSIVE (ADMIN xor MEMBER xor absent), each row has a single role <select>.
 * Any case admin can add a member. The candidate pool is the members of the case's namespace
 * ([namespaceId]) for everyone, super-admins included, which is the boundary the backend enforces.
 */
@Component({
  selector: 'agentos-case-members',
  imports: [AutocompleteInputComponent, LowerCasePipe],
  templateUrl: './case-members.component.html',
  styleUrl: './case-members.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseMembersComponent {
  private readonly destroyRef = inject(DestroyRef)
  private readonly memberState = inject(CaseMemberStateService)
  private readonly userState = inject(UserStateService)

  readonly caseId = input.required<string>()
  /** Namespace the case belongs to — source of the invitation candidates. */
  readonly namespaceId = input.required<string>()

  /** Emitted when the user saves or cancels — the parent closes the panel. */
  readonly closeRequested = output<void>()

  /** Id of the currently authenticated user — their row is read-only (no role change, no remove). */
  protected readonly currentUserId = this.userState.currentUser

  protected readonly isLoading = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  protected readonly selectedMembers = signal<SelectedMember[]>([])
  private originalMembers: MemberRoleEntry[] = []

  /** Candidate pool for the autocomplete — the members of the case's namespace. */
  private readonly candidateUsers = signal<MemberCandidate[]>([])

  protected readonly memberDataSource = new NamespaceMemberAutocompleteDataSource(
    () => this.candidateUsers(),
    () => new Set(this.selectedMembers().map((m) => m.userId))
  )

  constructor() {
    effect(() => {
      this.caseId() // tracked — re-runs when caseId changes
      untracked(() => this.loadData()) // untracked — signal writes inside are permitted
    })
  }

  private loadData(): void {
    this.isLoading.set(true)
    this.errorMessage.set(null)

    // The current user marks the read-only self row: make sure it is loaded.
    if (!this.userState.currentUser()) {
      this.userState
        .loadMe()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ error: (err) => console.error('[CaseMembers] Failed to load current user', err) })
    }

    this.loadMembersAndCandidates()
  }

  private loadMembersAndCandidates(): void {
    // A failing candidate load (e.g. 404 for a case admin outside the namespace) must not break
    // the panel: the roster stays editable, the autocomplete is just empty.
    const candidates$ = this.memberState.listNamespaceMembers(this.namespaceId()).pipe(
      catchError((err) => {
        console.warn('[CaseMembers] Failed to load invitation candidates', err)
        return of([] as MemberCandidate[])
      })
    )

    forkJoin({
      members: this.memberState.listMembers(this.caseId()),
      candidates: candidates$,
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ members, candidates }) => {
          this.applyMembers(members)
          this.candidateUsers.set(candidates)
          this.isLoading.set(false)
        },
        error: (err) => {
          console.error('[CaseMembers] Failed to load case members', err)
          this.isLoading.set(false)
          this.errorMessage.set('Failed to load case members. Please try again.')
        },
      })
  }

  private applyMembers(members: MemberItem[]): void {
    this.originalMembers = members.map((m) => ({ userId: m.id, role: m.role }))
    this.selectedMembers.set(
      members.map((m) => ({
        userId: m.id,
        label: memberLabel(m),
        email: m.email,
        role: m.role,
      }))
    )
  }

  // ---------------------------------------------------------------------------
  // Member selection
  // ---------------------------------------------------------------------------

  protected onMemberSelected(item: AutocompleteItem): void {
    if (this.selectedMembers().some((m) => m.userId === item.id)) return
    this.selectedMembers.update((members) => [
      ...members,
      { userId: item.id, label: item.name, email: item.description, role: MemberItemRoleEnum.MEMBER },
    ])
  }

  protected removeMember(userId: string): void {
    this.selectedMembers.update((members) => members.filter((m) => m.userId !== userId))
  }

  protected setMemberRole(userId: string, event: Event): void {
    const role = (event.target as HTMLSelectElement).value
    const nextRole = role === MemberItemRoleEnum.ADMIN ? MemberItemRoleEnum.ADMIN : MemberItemRoleEnum.MEMBER
    this.selectedMembers.update((members) => members.map((m) => (m.userId === userId ? { ...m, role: nextRole } : m)))
  }

  /** True when the edited roster would leave the case with zero ADMIN — blocks submit. */
  protected readonly wouldLockOutAdmins = computed(() => !hasAtLeastOneAdmin(this.selectedMembers()))

  // ---------------------------------------------------------------------------
  // Submit / cancel
  // ---------------------------------------------------------------------------

  protected submit(): void {
    if (this.isSubmitting()) return

    if (this.wouldLockOutAdmins()) {
      this.errorMessage.set('At least one ADMIN must remain on the case. Assign ADMIN to someone before saving.')
      return
    }

    this.isSubmitting.set(true)
    this.errorMessage.set(null)

    const { toUpsert, toRemove } = computeMemberDiff(
      this.originalMembers,
      this.selectedMembers().map((m) => ({ userId: m.userId, role: m.role }))
    )

    if (toUpsert.length === 0 && toRemove.length === 0) {
      this.isSubmitting.set(false)
      this.closeRequested.emit()
      return
    }

    const updateEntries: CaseMemberUpdateEntry[] = [
      ...toUpsert.map((entry) => ({ userId: entry.userId, role: entry.role as UserMembershipRoleRoleEnum })),
      ...toRemove.map((userId) => ({ userId })),
    ]

    this.memberState
      .updateMembers(this.caseId(), updateEntries)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refreshed) => {
          this.applyMembers(refreshed)
          this.isSubmitting.set(false)
          this.closeRequested.emit()
        },
        error: (err: HttpErrorResponse) => {
          this.isSubmitting.set(false)
          this.errorMessage.set(this.describeError(err))
        },
      })
  }

  private describeError(err: HttpErrorResponse): string {
    if (err.status === 403) {
      return 'You do not have permission to change who has access to this case — only a case admin can.'
    }
    if (err.status === 422) {
      return err.error?.message ?? 'This change was rejected. Check the members and roles, then try again.'
    }
    return 'An unexpected error occurred while saving case members. Please try again.'
  }

  protected cancel(): void {
    this.closeRequested.emit()
  }
}

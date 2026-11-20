import { NgTemplateOutlet } from '@angular/common'
import { HttpErrorResponse } from '@angular/common/http'
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  MemberItem,
  UserGroupMemberRoleEnum,
  UserGroupMembersListResponse,
  UserGroupSearchResult,
} from '@whoz-oss/agentos-api-client'
import { AutocompleteInputComponent, AutocompleteItem } from '@whoz-oss/design-system'
import { Observable } from 'rxjs'
import { UserGroupStateService } from '../../services/user-group-state.service'
import { ListFooterComponent } from '../list-footer/list-footer.component'
import { UserGroupMemberAutocompleteDataSource } from './user-group-member.data-source'
import {
  computeAdminDelta,
  computeMemberDiff,
  MemberBaseline,
  mergeMemberPage,
  SelectedMember,
} from './user-group-form.util'

/** Max name length — mirrors the backend @Size(max = 250) on UserGroupCreateRequest. */
const MAX_NAME_LENGTH = 250

/**
 * UserGroupFormComponent — full-page create / edit form for a user group.
 *
 * Namespace-scoped only (no platform mode). Mode is driven by the presence of `:userGroupId`:
 * - /:namespaceId/user-groups/new              → create
 * - /:namespaceId/user-groups/:userGroupId/edit → edit
 *
 * The form manages the group name, its deployed agents (a grouped checklist of platform +
 * namespace agent configs, which can be shared across multiple groups),
 * and its members (an inline list with a per-member MEMBER/ADMIN role select + a type-ahead over
 * the namespace's users). In edit mode only the first page of members is loaded; "Load more" fetches
 * the next ones. On submit, member and admin changes are diffed against the roles the server returned
 * (the baseline), so members that were never loaded are left untouched. Create sends the full ADMIN set.
 *
 * Slightly over the ~200-line component guideline: HTTP orchestration is already extracted to
 * UserGroupStateService and pure transforms to user-group-form.util.ts. What remains is the
 * agent- and member-selection handlers, which the template binds to directly and cannot move
 * out without a child-component split not warranted here. Kept as one cohesive form.
 */
@Component({
  selector: 'agentos-user-group-form',
  imports: [NgTemplateOutlet, ReactiveFormsModule, AutocompleteInputComponent, ListFooterComponent],
  templateUrl: './user-group-form.component.html',
  styleUrl: './user-group-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserGroupFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userGroupState = inject(UserGroupStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  private readonly userGroupId = this.route.snapshot.paramMap.get('userGroupId')
  /** Optional caller-supplied return URL (e.g. the admin console); falls back to the namespace page. */
  private readonly returnTo = this.route.snapshot.queryParamMap.get('returnTo')

  protected readonly isEditMode = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(MAX_NAME_LENGTH)],
    }),
  })

  protected get nameControl(): FormControl<string> {
    return this.form.controls.name
  }

  // --- Deployed agents ---
  protected readonly platformAgents = signal<AgentConfig[]>([])
  protected readonly namespaceAgents = signal<AgentConfig[]>([])
  protected readonly selectedAgentIds = signal<Set<string>>(new Set())

  // --- Members ---
  protected readonly candidateUsers = signal<MemberItem[]>([])
  protected readonly selectedMembers = signal<SelectedMember[]>([])
  /** Server role of every member loaded so far; membership and admin diffs are computed from it. */
  protected readonly baseline = signal<MemberBaseline>(new Map())
  protected readonly nextCursor = signal<string | null>(null)
  protected readonly totalMembers = signal(0)
  protected readonly isLoadingMoreMembers = signal(false)
  protected readonly loadMoreMembersFailed = signal(false)

  protected readonly memberDataSource = new UserGroupMemberAutocompleteDataSource(
    () => this.candidateUsers(),
    () => new Set(this.selectedMembers().map((member) => member.externalId))
  )

  ngOnInit(): void {
    this.isEditMode.set(!!this.userGroupId)
    this.loadFormData()
  }

  private loadFormData(): void {
    this.isLoading.set(true)
    this.userGroupState
      .loadFormData(this.namespaceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ namespaceAgents, platformAgents, users }) => {
          this.namespaceAgents.set(namespaceAgents)
          this.platformAgents.set(platformAgents)
          this.candidateUsers.set(users)
          if (this.userGroupId) {
            this.loadExistingGroup(this.userGroupId)
          } else {
            this.isLoading.set(false)
          }
        },
        error: (err) => {
          // Don't swallow silently: log so the failed load is diagnosable (#1076), matching
          // loadExistingGroup below.
          console.error('[UserGroupForm] Failed to load form data', err)
          this.isLoading.set(false)
          this.errorMessage.set('Failed to load form data. Please try again.')
        },
      })
  }

  private loadExistingGroup(id: string): void {
    this.userGroupState
      .loadExistingGroup(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ group, members }) => {
          this.nameControl.setValue(group.name)
          this.selectedAgentIds.set(new Set(group.agentIds))
          this.applyMemberPage(members)
          this.isLoading.set(false)
        },
        error: (err) => {
          // Don't swallow silently: log so the failed load is diagnosable, then leave the
          // half-loaded edit form rather than showing empty fields.
          console.error('[UserGroupForm] Failed to load user group for editing', err)
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  protected loadMoreMembers(): void {
    const next = this.nextCursor()
    if (!this.userGroupId || !next || this.isLoadingMoreMembers()) return
    this.isLoadingMoreMembers.set(true)
    this.loadMoreMembersFailed.set(false)
    this.userGroupState
      .loadMembersPage(this.userGroupId, next)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.applyMemberPage(page)
          this.isLoadingMoreMembers.set(false)
        },
        error: (err) => {
          console.error('[UserGroupForm] Failed to load more members', err)
          this.isLoadingMoreMembers.set(false)
          this.loadMoreMembersFailed.set(true)
        },
      })
  }

  private applyMemberPage(page: UserGroupMembersListResponse): void {
    const merged = mergeMemberPage(this.selectedMembers(), this.baseline(), page.data)
    this.selectedMembers.set(merged.rows)
    this.baseline.set(merged.baseline)
    this.nextCursor.set(page.metadata.next ?? null)
    this.totalMembers.set(page.metadata.totalCount)
  }

  // ---------------------------------------------------------------------------
  // Agent selection
  // ---------------------------------------------------------------------------

  protected isAgentSelected(agent: AgentConfig): boolean {
    return this.selectedAgentIds().has(agent.id ?? '')
  }

  protected toggleAgent(agent: AgentConfig): void {
    const id = agent.id
    if (!id) return
    const next = new Set(this.selectedAgentIds())
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    this.selectedAgentIds.set(next)
  }

  // ---------------------------------------------------------------------------
  // Member selection
  // ---------------------------------------------------------------------------

  protected onMemberSelected(item: AutocompleteItem): void {
    if (this.selectedMembers().some((member) => member.externalId === item.id)) return
    this.selectedMembers.update((members) => [
      ...members,
      // roleEdited: the role is the admin's choice, a later page holding this member must not override it.
      {
        externalId: item.id,
        label: item.name,
        email: item.description,
        role: UserGroupMemberRoleEnum.MEMBER,
        roleEdited: true,
      },
    ])
  }

  protected removeMember(externalId: string): void {
    this.selectedMembers.update((members) => members.filter((member) => member.externalId !== externalId))
  }

  protected setMemberRole(externalId: string, role: string): void {
    const nextRole =
      role === UserGroupMemberRoleEnum.ADMIN ? UserGroupMemberRoleEnum.ADMIN : UserGroupMemberRoleEnum.MEMBER
    this.selectedMembers.update((members) =>
      members.map((member) =>
        member.externalId === externalId ? { ...member, role: nextRole, roleEdited: true } : member
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Submit / cancel
  // ---------------------------------------------------------------------------

  protected submit(): void {
    if (this.nameControl.invalid || this.isSubmitting()) return
    this.isSubmitting.set(true)
    this.errorMessage.set(null)

    const name = this.nameControl.value.trim()
    const agentIds = [...this.selectedAgentIds()]

    const call$ =
      this.isEditMode() && this.userGroupId
        ? this.updateGroup(this.userGroupId, name, agentIds)
        : this.createGroup(name, agentIds)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: HttpErrorResponse) => {
        this.isSubmitting.set(false)
        if (err.status === 409) {
          this.errorMessage.set(`A user group named "${name}" already exists in this namespace.`)
        } else if (err.status === 400 || err.status === 422) {
          this.errorMessage.set(err.error?.message ?? 'Invalid user group data.')
        } else {
          this.errorMessage.set('An unexpected error occurred. Please try again.')
        }
      },
    })
  }

  private createGroup(name: string, agentIds: string[]): Observable<UserGroupSearchResult> {
    const members = this.selectedMembers()
    return this.userGroupState.create({
      namespaceId: this.namespaceId,
      name,
      agentIds,
      memberExternalIdsToAdd: members.map((member) => member.externalId),
      adminExternalIds: members
        .filter((member) => member.role === UserGroupMemberRoleEnum.ADMIN)
        .map((member) => member.externalId),
    })
  }

  private updateGroup(id: string, name: string, agentIds: string[]): Observable<UserGroupSearchResult> {
    const members = this.selectedMembers()
    const baseline = this.baseline()
    const { toAdd, toRemove } = computeMemberDiff(
      [...baseline.keys()],
      members.map((member) => member.externalId)
    )
    const admins = computeAdminDelta(baseline, members)
    return this.userGroupState.update(id, {
      name,
      agentIds,
      memberExternalIdsToAdd: toAdd,
      memberExternalIdsToRemove: toRemove,
      adminExternalIdsToAdd: admins.toAdd,
      adminExternalIdsToRemove: admins.toRemove,
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.returnTo) {
      this.router.navigateByUrl(this.returnTo)
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'user-groups'])
    }
  }
}

import { inject, Injectable } from '@angular/core'
import {
  CaseMembershipControllerService,
  MemberItem,
  NamespaceMembershipControllerService,
  UserControllerService,
  UserMembershipRole,
  UserMembershipRoleRoleEnum,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/** One entry in a batch update: upsert (with role) or revoke (without role). */
export interface CaseMemberUpdateEntry {
  userId: string
  /** Omit to revoke all roles for this user. */
  role?: UserMembershipRoleRoleEnum
}

/**
 * CaseMemberStateService — API-layer facade for case ADMIN/MEMBER management.
 *
 * Follows the two-layer pattern (see NamespaceMemberStateService): components go through this
 * facade rather than injecting CaseMembershipControllerService or UserControllerService directly.
 *
 * The backend endpoint accepts a flat `Array<UserMembershipRole>` where each entry carries
 * a userId and an optional role. A missing role means "revoke all relations for this user".
 *
 * `listAllUsers()` wraps `UserControllerService.listAllUser()`, which is SUPER_ADMIN-only on
 * the backend — callers must only invoke it when the current user is a super-admin.
 * `listNamespaceMembers()` is the candidate pool for everyone else: the members of the case's
 * namespace (Namespace READ), the only users a non-super-admin case admin may invite.
 */
@Injectable({ providedIn: 'root' })
export class CaseMemberStateService {
  private readonly membership = inject(CaseMembershipControllerService)
  private readonly namespaceMembership = inject(NamespaceMembershipControllerService)
  private readonly userController = inject(UserControllerService)

  /** Current members (and their role) of a case. */
  listMembers(caseId: string): Observable<MemberItem[]> {
    return this.membership.getMembersCaseMembership(caseId)
  }

  /**
   * All platform users — SUPER_ADMIN only on the backend. Only call this when the current
   * user is confirmed to be a super-admin.
   */
  listAllUsers() {
    return this.userController.listAllUser()
  }

  /**
   * Members (ADMIN or MEMBER) of a namespace — the invitation candidates of a non-super-admin.
   * Fails (404) when the caller cannot read the namespace.
   */
  listNamespaceMembers(namespaceId: string): Observable<MemberItem[]> {
    return this.namespaceMembership.getMembersNamespaceMembership(namespaceId)
  }

  /**
   * Batch upsert / revoke in a single round-trip. Returns the refreshed member list.
   *
   * Pass entries with a role to add or change, and entries without a role to revoke.
   */
  updateMembers(caseId: string, entries: CaseMemberUpdateEntry[]): Observable<MemberItem[]> {
    const payload: UserMembershipRole[] = entries.map((e) => ({
      userId: e.userId,
      ...(e.role !== undefined ? { role: e.role } : {}),
    }))
    return this.membership.updateMembersCaseMembership(caseId, payload)
  }
}

import { inject, Injectable } from '@angular/core'
import {
  CaseMembershipControllerService,
  MemberItem,
  NamespaceMembershipControllerService,
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
 * facade rather than injecting CaseMembershipControllerService or NamespaceMembershipControllerService
 * directly.
 *
 * The backend endpoint accepts a flat `Array<UserMembershipRole>` where each entry carries
 * a userId and an optional role. A missing role means "revoke all relations for this user".
 *
 * `listNamespaceMembers()` is the invitation candidate pool for every caller, super-admins included:
 * the members of the case's namespace (Namespace READ), the only users the backend lets a case admin
 * invite.
 */
@Injectable({ providedIn: 'root' })
export class CaseMemberStateService {
  private readonly membership = inject(CaseMembershipControllerService)
  private readonly namespaceMembership = inject(NamespaceMembershipControllerService)

  /** Current members (and their role) of a case. */
  listMembers(caseId: string): Observable<MemberItem[]> {
    return this.membership.getMembersCaseMembership(caseId)
  }

  /**
   * Members (ADMIN or MEMBER) of a namespace — the invitation candidates.
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

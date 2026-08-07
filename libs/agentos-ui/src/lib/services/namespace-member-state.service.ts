import { inject, Injectable } from '@angular/core'
import {
  MemberItem,
  NamespaceMembershipControllerService,
  UserControllerService,
  UserMembershipRole,
  UserMembershipRoleRoleEnum,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/** One entry in a batch update: upsert (with role) or revoke (without role). */
export interface MemberUpdateEntry {
  userId: string
  /** Omit to revoke all roles for this user. */
  role?: UserMembershipRoleRoleEnum
}

/**
 * NamespaceMemberStateService — API-layer facade for namespace ADMIN/MEMBER management.
 *
 * Follows the two-layer pattern (see UserGroupStateService): components go through this
 * facade rather than injecting NamespaceMembershipControllerService or UserControllerService
 * directly.
 *
 * The backend endpoint accepts a flat `Array<UserMembershipRole>` where each entry carries
 * a userId and an optional role. A missing role means "revoke all relations for this user".
 *
 * `listAllUsers()` wraps `UserControllerService.listAllUser()`, which is SUPER_ADMIN-only on
 * the backend — callers (the members component) must only invoke it when the current user is
 * a super-admin, otherwise the request 403s. This service does not enforce that itself; it is
 * a UI-layer concern (see NamespaceMembersComponent).
 */
@Injectable({ providedIn: 'root' })
export class NamespaceMemberStateService {
  private readonly membership = inject(NamespaceMembershipControllerService)
  private readonly userController = inject(UserControllerService)

  /** Current members (and their role) of a namespace. */
  listMembers(namespaceId: string): Observable<MemberItem[]> {
    return this.membership.getMembersNamespaceMembership(namespaceId)
  }

  /**
   * All platform users — SUPER_ADMIN only on the backend. Only call this when the current
   * user is confirmed to be a super-admin (see NamespaceMembersComponent.isSuperAdmin).
   */
  listAllUsers() {
    return this.userController.listAllUser()
  }

  /**
   * Batch upsert / revoke in a single round-trip. Returns the refreshed member list.
   *
   * Pass entries with a role to add or change, and entries without a role to revoke.
   */
  updateMembers(namespaceId: string, entries: MemberUpdateEntry[]): Observable<MemberItem[]> {
    const payload: UserMembershipRole[] = entries.map((e) => ({
      userId: e.userId,
      ...(e.role !== undefined ? { role: e.role } : {}),
    }))
    return this.membership.updateMembersNamespaceMembership(namespaceId, payload)
  }
}
